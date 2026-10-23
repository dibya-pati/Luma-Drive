// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Decodes live AA Annex-B frames to WebRTC's texture capturer. No camera or projection API is used. */
internal class AaVideoDecoder(
    private val helper: SurfaceTextureHelper,
    private val source: VideoSource,
    private val requestKeyframe: () -> Unit,
    private val frameRateLimit: FrameRateLimit = FrameRateLimit()
) : AutoCloseable {
    private data class Frame(val bytes: ByteArray, val durationNs: Long, val epoch: Long)
    private val lock = Any()
    private val queue = ArrayDeque<Frame>()
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aa-h264-decoder").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)
    private val drainScheduled = AtomicBoolean(false)
    private val decoded = AtomicLong()
    private val dropped = AtomicLong()
    @Volatile private var codec: MediaCodec? = null
    private var outputSurface: Surface? = null
    private val admission = AaVideoAdmission()
    @Volatile private var codecNeedsRestart = false
    @Volatile private var allowCapturedFrames = false
    private var outputPump: ScheduledFuture<*>? = null
    private var outputPumpEpoch = 0L
    private var ptsUs = 0L
    private var epoch = 0L
    @Volatile private var flushRequested = false

    init {
        helper.setTextureSize(1920, 1080)
        source.capturerObserver.onCapturerStarted(true)
        helper.startListening { frame ->
            if (!closed.get() && allowCapturedFrames) {
                if (frameRateLimit.admit()) source.capturerObserver.onFrameCaptured(frame)
            }
        }
    }

    fun offer(data: ByteArray, durationMs: Double) {
        if (closed.get() || data.isEmpty()) return
        val parsed = H264Metadata.parse(data)
        var overloaded = false
        var accepted = false
        synchronized(lock) {
            val metadataChanged = admission.updateMetadata(parsed.codecMetadata)
            if (metadataChanged && codec != null) {
                queue.clear(); epoch++; admission.resync(); frameRateLimit.reset(); codecNeedsRestart = true; allowCapturedFrames = false; overloaded = true
            }
            if (queue.size >= MAX_QUEUE) {
                queue.clear(); epoch++; admission.resync(); frameRateLimit.reset(); codecNeedsRestart = true; allowCapturedFrames = false; dropped.incrementAndGet(); overloaded = true
            }
            if (admission.admit(parsed.types)) {
                queue.addLast(Frame(data.copyOf(), (durationMs.coerceIn(1.0, 1000.0) * 1_000_000.0).toLong(), epoch))
                accepted = true
            } else dropped.incrementAndGet()
        }
        if (overloaded) requestKeyframe()
        if (!accepted) return
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (drainScheduled.compareAndSet(false, true)) worker.execute {
            try { drain() } finally {
                drainScheduled.set(false)
                if (!closed.get() && synchronized(lock) { queue.isNotEmpty() }) scheduleDrain()
            }
        }
    }

    private fun drain() {
        while (!closed.get()) {
            if (codecNeedsRestart) { stopCodec(); codecNeedsRestart = false; flushRequested = false; ptsUs = 0L }
            if (flushRequested) { flushCodec(); flushRequested = false }
            val frame = synchronized(lock) { if (queue.isEmpty()) null else queue.removeFirst() } ?: return
            if (frame.epoch != synchronized(lock) { epoch }) { dropped.incrementAndGet(); continue }
            try {
                var decoder = codec
                if (decoder == null) {
                    decoder = startCodec(frame.epoch)
                    if (decoder == null) { loseFrame(); continue }
                }
                val index = decoder.dequeueInputBuffer(10_000)
                if (index < 0) { loseFrame(); continue }
                val input = decoder.getInputBuffer(index)
                if (input == null) { loseFrame(); continue }
                if (input.remaining() < frame.bytes.size) { decoder.queueInputBuffer(index, 0, 0, ptsUs, 0); loseFrame(); continue }
                input.clear(); input.put(frame.bytes)
                ptsUs += (frame.durationNs / 1000L).coerceAtLeast(1L)
                decoder.queueInputBuffer(index, 0, frame.bytes.size, ptsUs, 0)
                if (closed.get() || frame.epoch != synchronized(lock) { epoch }) { flushRequested = true; continue }
                drainOutput(decoder, frame.epoch, 10_000)
            } catch (_: Exception) {
                stopCodec(); loseFrame()
            }
        }
    }

    private fun loseFrame() {
        synchronized(lock) { queue.clear(); epoch++; admission.resync(); frameRateLimit.reset(); codecNeedsRestart = true; allowCapturedFrames = false }
        dropped.incrementAndGet(); requestKeyframe()
    }

    private fun startCodec(epoch: Long): MediaCodec? {
        val (cs, cp) = synchronized(lock) { admission.parameterSets() } ?: return null
        var surface: Surface? = null
        var created: MediaCodec? = null
        return try {
            val activeSurface = Surface(helper.surfaceTexture)
            surface = activeSurface
            val dimensions = H264SpsDimensions.parse(cs) ?: (1920 to 1080)
            helper.setTextureSize(dimensions.first, dimensions.second)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, dimensions.first, dimensions.second).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(AaVideoAdmission.ensureAnnexBStartCode(cs)))
                setByteBuffer("csd-1", ByteBuffer.wrap(AaVideoAdmission.ensureAnnexBStartCode(cp)))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            created = c
            c.configure(format, activeSurface, null, 0); c.start(); codec = c; outputSurface = activeSurface
            startOutputPump(c, epoch)
            c
        } catch (_: Exception) {
            runCatching { created?.stop() }; runCatching { created?.release() }; runCatching { surface?.release() }
            if (codec === created) codec = null
            if (outputSurface === surface) outputSurface = null
            null
        }
    }

    private fun startOutputPump(c: MediaCodec, epoch: Long) {
        outputPump?.cancel(false)
        outputPumpEpoch = epoch
        outputPump = worker.scheduleAtFixedRate({
            if (closed.get() || codec !== c || outputPumpEpoch != epoch) return@scheduleAtFixedRate
            if (epoch != synchronized(lock) { this.epoch }) {
                stopCodec(); codecNeedsRestart = false; ptsUs = 0L
                return@scheduleAtFixedRate
            }
            try { drainOutput(c, epoch, 0) } catch (_: Exception) { stopCodec(); loseFrame() }
        }, OUTPUT_PUMP_INITIAL_MS, OUTPUT_PUMP_PERIOD_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun drainOutput(c: MediaCodec, epoch: Long, waitUs: Long) {
        val info = MediaCodec.BufferInfo()
        var output = c.dequeueOutputBuffer(info, waitUs)
        while (!closed.get() && epoch == synchronized(lock) { this.epoch }) {
            when {
                output >= 0 -> {
                    allowCapturedFrames = true
                    c.releaseOutputBuffer(output, true)
                    decoded.incrementAndGet()
                    output = c.dequeueOutputBuffer(info, 0)
                }
                output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val out = c.outputFormat
                    val width = out.getInteger(MediaFormat.KEY_WIDTH).coerceIn(1, 4096)
                    val height = out.getInteger(MediaFormat.KEY_HEIGHT).coerceIn(1, 4096)
                    helper.setTextureSize(width, height)
                    output = c.dequeueOutputBuffer(info, 0)
                }
                else -> break
            }
        }
    }

    private fun flushCodec() { try { codec?.flush() } catch (_: Exception) { stopCodec() } }
    private fun stopCodec() {
        outputPump?.cancel(false); outputPump = null; outputPumpEpoch++
        val c = codec; codec = null
        try { c?.stop() } catch (_: Exception) { }
        try { c?.release() } catch (_: Exception) { }
        try { outputSurface?.release() } catch (_: Exception) { }
        outputSurface = null
    }

    fun setMaxFps(value: Int) { frameRateLimit.setMaxFps(value) }

    fun reset(requestFreshKeyframe: Boolean = true) {
        synchronized(lock) { queue.clear(); epoch++; admission.clear(); frameRateLimit.reset(); codecNeedsRestart = true; allowCapturedFrames = false }
        if (requestFreshKeyframe) requestKeyframe()
    }

    fun stats() = mapOf("decodedFrames" to decoded.get(), "decoderDrops" to dropped.get(), "decoderQueue" to synchronized(lock) { queue.size }) + frameRateLimit.stats()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        allowCapturedFrames = false
        synchronized(lock) { queue.clear() }
        helper.stopListening()
        runCatching { worker.submit { stopCodec() }.get(2, java.util.concurrent.TimeUnit.SECONDS) }
        worker.shutdown()
        source.capturerObserver.onCapturerStopped()
    }

    companion object { private const val MAX_QUEUE = 8; private const val OUTPUT_PUMP_INITIAL_MS = 10L; private const val OUTPUT_PUMP_PERIOD_MS = 15L }
}

/** Small Annex-B SPS reader used only to configure the hardware decoder's initial dimensions. */
internal object H264SpsDimensions {
    fun nalPayload(nal: ByteArray): ByteArray {
        var offset = 0
        while (offset + 3 < nal.size) {
            if (nal[offset] == 0.toByte() && nal[offset + 1] == 0.toByte() && (nal[offset + 2] == 1.toByte() || (nal[offset + 2] == 0.toByte() && nal[offset + 3] == 1.toByte()))) {
                offset += if (nal[offset + 2] == 1.toByte()) 3 else 4
                break
            }
            offset++
        }
        return nal.copyOfRange(offset, nal.size)
    }

    fun parse(nal: ByteArray): Pair<Int, Int>? = runCatching {
        val encoded = nalPayload(nal).drop(1).toByteArray()
        val rbsp = ArrayList<Byte>(encoded.size)
        var zeros = 0
        encoded.forEach { b ->
            val v = b.toInt() and 255
            if (zeros >= 2 && v == 3) { zeros = 0; return@forEach }
            rbsp.add(b); zeros = if (v == 0) zeros + 1 else 0
        }
        val bits = Bits(rbsp.toByteArray())
        val profile = bits.read(8); bits.skip(16); bits.ue()
        var chroma = 1
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = bits.ue(); if (chroma == 3) bits.skip(1)
            bits.ue(); bits.ue(); bits.skip(1)
            if (bits.read(1) == 1) {
                val count = if (chroma == 3) 12 else 8
                repeat(count) { if (bits.read(1) == 1) bits.scalingList(if (it < 6) 16 else 64) }
            }
        }
        bits.ue()
        val poc = bits.ue()
        if (poc == 0) bits.ue() else if (poc == 1) {
            bits.skip(1); bits.se(); bits.se(); repeat(bits.ue().coerceAtMost(255)) { bits.se() }
        }
        bits.ue(); bits.skip(1)
        val widthMbs = bits.ue() + 1
        val heightMap = bits.ue() + 1
        val frameOnly = bits.read(1) == 1
        if (!frameOnly) bits.skip(1)
        bits.skip(1)
        var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
        if (bits.read(1) == 1) { cropLeft = bits.ue(); cropRight = bits.ue(); cropTop = bits.ue(); cropBottom = bits.ue() }
        val subWidth = if (chroma == 1 || chroma == 2) 2 else 1
        val subHeight = if (chroma == 1) 2 else 1
        val cropUnitX = if (chroma == 0) 1 else subWidth
        val cropUnitY = (if (chroma == 0) 1 else subHeight) * if (frameOnly) 1 else 2
        val width = widthMbs * 16 - (cropLeft + cropRight) * cropUnitX
        val height = heightMap * 16 * (if (frameOnly) 1 else 2) - (cropTop + cropBottom) * cropUnitY
        require(width in 16..4096 && height in 16..4096)
        width to height
    }.getOrNull()

    private class Bits(private val data: ByteArray) {
        private var pos = 0
        fun read(n: Int): Int { var x = 0; repeat(n) { x = (x shl 1) or ((data[pos / 8].toInt() ushr (7 - pos % 8)) and 1); pos++ }; return x }
        fun skip(n: Int) { repeat(n) { read(1) } }
        fun ue(): Int { var zeros = 0; while (read(1) == 0) { zeros++; require(zeros < 31) }; return (1 shl zeros) - 1 + if (zeros == 0) 0 else read(zeros) }
        fun se(): Int { val code = ue(); return if (code % 2 == 0) -(code / 2) else (code + 1) / 2 }
        fun scalingList(size: Int) { var last = 8; var next = 8; repeat(size) { if (next != 0) next = (last + se() + 256) % 256; last = if (next == 0) last else next } }
    }
}

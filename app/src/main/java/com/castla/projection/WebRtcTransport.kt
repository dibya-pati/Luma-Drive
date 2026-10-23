// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import android.content.Context
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Local, serverless WebRTC transport for browser video, controls, and raw PCM. */
internal class WebRtcTransport(
    context: Context,
    private val refreshVideo: () -> Unit,
    private val control: (String, String) -> Unit,
    private val viewerOpened: (String) -> Unit,
    private val viewerClosed: (String) -> Unit,
    initialMaxFps: Int = FrameRateLimit.MAX_ALLOWED_FPS
) : AutoCloseable {
    private data class Session(val id: String, val clientId: String, val peer: PeerConnection, var pcm: DataChannel? = null, var control: DataChannel? = null,
        @Volatile var connected: Boolean = false, val connectionEpoch: java.util.concurrent.atomic.AtomicLong = AtomicLong(), var videoSender: RtpSender? = null)
    private val lock = Any()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "local-webrtc").apply { isDaemon = true } }
    private val audioQueue = BoundedPcmQueue(MAX_AUDIO_QUEUE_BYTES, MAX_AUDIO_AGE_NS)
    private val audioDrainScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val audioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "webrtc-pcm").apply { isDaemon = true } }
    private val sessions = LinkedHashMap<String, Session>()
    private val openedClients = HashSet<String>()
    private val sentVideo = AtomicLong(); private val sentPcm = AtomicLong(); private val missedPcm = AtomicLong()
    private val senderPolicyFailures = AtomicLong()
    private val context = context.applicationContext
    private val frameRateLimit = FrameRateLimit(initialMaxFps)
    private val egl = EglBase.create()
    private val factory: PeerConnectionFactory
    private val source: VideoSource
    private val track: VideoTrack
    private val helper: SurfaceTextureHelper
    private val decoder: AaVideoDecoder
    @Volatile private var closed = false

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this.context).createInitializationOptions())
        // This call enters native WebRTC code, so load JNI before suppressing native diagnostic output.
        Logging.enableLogToDebugOutput(Logging.Severity.LS_NONE)
        val options = PeerConnectionFactory.Options().apply {
            // Enumerate interface addresses directly so hotspot and retained local TUN aliases are candidates.
            disableNetworkMonitor = true
        }
        factory = PeerConnectionFactory.builder().setOptions(options)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
        // This is a decoded AA video source, not a camera capture source. Mark it as a
        // screencast so WebRTC preserves source dimensions when adapting under pressure.
        source = factory.createVideoSource(true)
        track = factory.createVideoTrack("aa-live-video", source).apply { setEnabled(true) }
        helper = SurfaceTextureHelper.create("aa-video-input", egl.eglBaseContext)
            ?: error("Unable to create WebRTC video texture helper")
        decoder = AaVideoDecoder(helper, source, refreshVideo, frameRateLimit)
    }

    fun answer(offerSdp: String, clientId: String): JSONObject {
        require(offerSdp.length in 1..256_000) { "Invalid SDP offer" }
        require(clientId.length in 1..128) { "Invalid client id" }
        check(!closed) { "WebRTC transport is closed" }
        val task = executor.submit<JSONObject> { createAnswer(offerSdp, clientId) }
        return try { task.get(12, TimeUnit.SECONDS) } catch (e: Exception) { task.cancel(true); throw e }
    }

    private fun createAnswer(sdp: String, clientId: String): JSONObject {
        synchronized(lock) { check(sessions.size < MAX_SESSIONS) { "Too many viewers" } }
        val id = UUID.randomUUID().toString()
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        val iceComplete = CountDownLatch(1)
        lateinit var session: Session
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                    session.connected = true; session.connectionEpoch.incrementAndGet()
                }
                if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    session.connected = false
                    val epoch = session.connectionEpoch.incrementAndGet()
                    runCatching { executor.schedule({ if (session.connectionEpoch.get() == epoch && !session.connected) closeSessionNow(id, clientId) }, 3, TimeUnit.SECONDS) }
                }
                if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED) closeSessionAsync(id, clientId)
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) { if (state == PeerConnection.IceGatheringState.COMPLETE) iceComplete.countDown() }
            override fun onIceCandidate(candidate: IceCandidate) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onDataChannel(channel: DataChannel) {
                when (channel.label()) {
                    "pcm" -> synchronized(lock) { if (valid(id, clientId)) { session.pcm = channel; observePcm(channel) } else channel.dispose() }
                    "control" -> synchronized(lock) { if (valid(id, clientId)) { session.control = channel; observeControl(channel, id, clientId) } else channel.dispose() }
                    else -> channel.dispose()
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        }
        val peer = factory.createPeerConnection(config, observer) ?: error("Unable to create peer connection")
        session = Session(id, clientId, peer)
        synchronized(lock) { sessions[id] = session }
        try {
            session.videoSender = peer.addTrack(track, listOf("aa-live")) ?: error("Unable to add video track")
            val remoteSet = CountDownLatch(1); var remoteError: String? = null
            peer.setRemoteDescription(sdpObserver(remoteSet) { remoteError = it }, SessionDescription(SessionDescription.Type.OFFER, sdp))
            check(await(remoteSet, deadlineNs) && remoteError == null) { "Unable to set remote SDP" }
            val answerLatch = CountDownLatch(1); var answer: SessionDescription? = null; var answerError: String? = null
            peer.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) { answer = desc; answerLatch.countDown() }
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String) { answerError = error; answerLatch.countDown() }
                override fun onSetFailure(error: String) = Unit
            }, MediaConstraints())
            check(await(answerLatch, deadlineNs) && answerError == null && answer != null) { "Unable to create SDP answer" }
            val localSet = CountDownLatch(1); var localError: String? = null
            peer.setLocalDescription(sdpObserver(localSet) { localError = it }, answer)
            check(await(localSet, deadlineNs) && localError == null) { "Unable to set local SDP" }
            applySenderFramerate(session)
            check(await(iceComplete, deadlineNs)) { "ICE gathering timed out" }
            executor.schedule({ if (!session.connected) closeSessionNow(id, clientId) }, 60, TimeUnit.SECONDS)
            val local = peer.localDescription ?: error("Missing local SDP")
            val firstForClient = synchronized(lock) { openedClients.add(clientId) }
            if (firstForClient) { viewerOpened(clientId); refreshVideo() }
            return JSONObject().put("type", "answer").put("sdp", local.description).put("session", id)
        } catch (e: Exception) {
            val notifyClosed = synchronized(lock) {
                sessions.remove(id)
                sessions.values.none { it.clientId == clientId } && openedClients.remove(clientId)
            }
            runCatching { peer.close() }; runCatching { peer.dispose() }
            if (notifyClosed) viewerClosed(clientId)
            throw e
        }
    }

    private fun sdpObserver(latch: CountDownLatch, fail: (String) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = Unit
        override fun onSetSuccess() { latch.countDown() }
        override fun onCreateFailure(error: String) { fail(error); latch.countDown() }
        override fun onSetFailure(error: String) { fail(error); latch.countDown() }
    }

    private fun await(latch: CountDownLatch, deadlineNs: Long): Boolean {
        val remaining = deadlineNs - System.nanoTime()
        return remaining > 0 && latch.await(remaining, TimeUnit.NANOSECONDS)
    }

    private fun observePcm(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = Unit
            override fun onMessage(buffer: DataChannel.Buffer) = Unit
        })
    }

    private fun observeControl(channel: DataChannel, id: String, clientId: String) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = Unit
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary || buffer.data.remaining() > MAX_CONTROL_BYTES) return
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                if (valid(id, clientId)) control(clientId, bytes.toString(Charsets.UTF_8))
            }
        })
    }

    private fun valid(id: String, clientId: String) = synchronized(lock) { !closed && sessions[id]?.clientId == clientId }
    private fun closeSessionAsync(id: String, clientId: String) { if (!closed) runCatching { executor.execute { closeSessionNow(id, clientId) } } }

    fun closeSession(session: String, clientId: String): Boolean = executor.submit<Boolean> { closeSessionNow(session, clientId) }.get(5, TimeUnit.SECONDS)
    private fun closeSessionNow(id: String, clientId: String): Boolean {
        val removed = synchronized(lock) { sessions[id]?.takeIf { it.clientId == clientId }?.also { sessions.remove(id) } } ?: return false
        runCatching { removed.pcm?.unregisterObserver(); removed.pcm?.dispose() }
        runCatching { removed.control?.unregisterObserver(); removed.control?.dispose() }
        runCatching { removed.peer.close() }; runCatching { removed.peer.dispose() }
        val (notifyClosed, noSessions) = synchronized(lock) {
            val noSessions = sessions.isEmpty()
            val notifyClosed = sessions.values.none { it.clientId == clientId } && openedClients.remove(clientId)
            notifyClosed to noSessions
        }
        if (noSessions) clearAudioQueue()
        if (notifyClosed) viewerClosed(clientId)
        return true
    }

    fun video(data: ByteArray, duration: Double) {
        if (closed) return
        // Keep decoding the live AA stream while browser peers come and go. AA source
        // session boundaries call resetMedia(); browser session boundaries do not.
        decoder.offer(data, duration)
        sentVideo.incrementAndGet()
    }

    /** Broadcast the existing 20-byte framed PCM packet without codec conversion. */
    fun audio(packet: ByteArray) {
        if (closed || packet.isEmpty()) return
        if (packet.size > MAX_PCM_MESSAGE) { missedPcm.incrementAndGet(); return }
        val targets = synchronized(lock) { sessions.values.filter { it.pcm?.state() == DataChannel.State.OPEN }.map { it.id }.toSet() }
        if (targets.isEmpty()) { missedPcm.incrementAndGet(); return }
        if (!audioQueue.offer(packet, targets, System.nanoTime())) { missedPcm.incrementAndGet(); return }
        scheduleAudioDrain()
    }

    private fun scheduleAudioDrain() {
        if (audioDrainScheduled.compareAndSet(false, true)) runCatching { audioExecutor.execute {
            try {
                while (!closed) {
                    val item = audioQueue.poll() ?: break
                    val packet = item.bytes
                    if (!audioQueue.isFresh(item, System.nanoTime())) { missedPcm.incrementAndGet(); continue }
                    item.sessionIds.forEach { sessionId ->
                        val channel = synchronized(lock) { sessions[sessionId]?.pcm?.takeIf { it.state() == DataChannel.State.OPEN } }
                        if (channel == null) { missedPcm.incrementAndGet(); return@forEach }
                        try {
                            // Freshness is checked immediately before native send; already-started SCTP sends cannot be recalled by reset.
                            if (!audioQueue.isFresh(item, System.nanoTime())) missedPcm.incrementAndGet()
                            else if (channel.bufferedAmount() + packet.size > MAX_CHANNEL_BUFFERED_BYTES) missedPcm.incrementAndGet()
                            else if (channel.send(DataChannel.Buffer(ByteBuffer.wrap(packet.copyOf()), true))) sentPcm.incrementAndGet()
                            else missedPcm.incrementAndGet()
                        } catch (_: Exception) { missedPcm.incrementAndGet() }
                    }
                }
            } finally {
                audioDrainScheduled.set(false)
                if (!closed && audioQueue.size() > 0) scheduleAudioDrain()
            }
        } }.onFailure { audioDrainScheduled.set(false) }
    }

    private fun clearAudioQueue() {
        audioQueue.reset()
    }

    fun resetMedia() { clearAudioQueue(); decoder.reset() }

    /** Updates the raw-frame gate immediately and schedules RTP sender changes off the caller thread. */
    fun setMaxFps(value: Int) {
        decoder.setMaxFps(value)
        if (!closed) runCatching { executor.execute {
            val active = synchronized(lock) { sessions.values.toList() }
            active.forEach(::applySenderFramerate)
        } }
    }

    private fun applySenderFramerate(session: Session) {
        runCatching {
            val sender = session.videoSender ?: return
            val parameters = sender.parameters ?: return
            parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            parameters.encodings.forEach { it.maxFramerate = frameRateLimit.maxFps }
            check(sender.setParameters(parameters)) { "WebRTC rejected sender parameters" }
        }.onFailure { senderPolicyFailures.incrementAndGet() }
    }

    fun diagnostics(): JSONObject {
        val ds = decoder.stats()
        val count = synchronized(lock) { sessions.size }
        return JSONObject().put("sessions", count).put("maxFps", frameRateLimit.maxFps)
            .put("framesForwarded", ds["framesForwarded"]).put("framerateDrops", ds["framerateDrops"])
            .put("videoInputFrames", sentVideo.get()).put("pcmPacketsSent", sentPcm.get())
            .put("pcmPacketsDropped", missedPcm.get())
            .put("senderPolicyFailures", senderPolicyFailures.get()).put("decoder", JSONObject(ds))
    }

    override fun close() {
        if (closed) return
        closed = true
        clearAudioQueue()
        runCatching { executor.submit { synchronized(lock) { sessions.keys.toList() }.forEach { id ->
            val session = synchronized(lock) { sessions[id] } ?: return@forEach
            closeSessionNow(id, session.clientId)
        } }.get(10, TimeUnit.SECONDS) }
        audioExecutor.shutdownNow()
        decoder.close(); helper.dispose(); track.dispose(); source.dispose(); factory.dispose(); egl.release()
        executor.shutdown()
    }

    companion object {
        private const val MAX_SESSIONS = 4
        private const val MAX_CONTROL_BYTES = 2048
        private const val MAX_AUDIO_QUEUE_BYTES = 64 * 1024
        private const val MAX_PCM_MESSAGE = 64 * 1024
        private const val MAX_CHANNEL_BUFFERED_BYTES = 64 * 1024
        private const val MAX_AUDIO_AGE_NS = 250_000_000L
    }
}

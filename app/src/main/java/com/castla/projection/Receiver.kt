// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import android.os.SystemClock
import com.castla.projection.protocol.*
import com.castla.projection.credential.LocalIdentity
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

internal class Receiver(private val server: BridgeServer, private val scope: CoroutineScope, private val context: android.content.Context) {
    @Volatile var phase = "Stopped"; private set
    @Volatile var width = 1920; private set
    @Volatile var height = 1080; private set
    @Volatile var bits = 16; private set
    @Volatile var rate = 48000; private set
    @Volatile private var session: AasdkProjectionSession? = null
    private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true
    private val streamSessions = ConcurrentHashMap<Int, Int>()
    private val probes = ConcurrentHashMap<Int, AudioProbe>()
    private val setupEvidence = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
    private val protocolEvents = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
    @Volatile private var inputReady = false
    @Volatile private var videoPackets = 0L
    @Volatile private var peerVersion = ""
    @Volatile private var lastRx = 0L
    @Volatile private var discoveryRequestedFields: List<Int> = emptyList()
    @Volatile private var lastError = ""
    @Volatile private var connectionStage = ""
    @Volatile private var identitySource = "NONE"
    private var lastTimestamp = 0L
    private var timestampUnits = "unknown"
    private val lastVideoRefresh = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var lastVideoAt = 0L

    private val generation = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var connectionAlive = false
    @Volatile private var idleDisconnecting = false
    val isDisconnectingForNoClients: Boolean get() = idleDisconnecting
    @Volatile private var shutdownReply: CompletableDeferred<Unit>? = null
    @Volatile private var reconnectAttempts = 0
    private fun updatePhase(epoch: Long, value: String) { if (generation.get() == epoch) phase = value }

    @Synchronized fun start(resolution: Int, audioBits: Int, audioRate: Int) {
        stop()
        idleDisconnecting = false
        require(resolution in listOf(720, 1080) && audioBits in listOf(16, 24) && audioRate in listOf(48000, 96000))
        require(audioBits != 16 || audioRate == 48000)
        val epoch = generation.get()
        height = resolution; width = if (resolution == 1080) 1920 else 1280; bits = audioBits; rate = audioRate
        reconnectAttempts = 0; lastError = ""; lastVideoAt = 0
        updatePhase(epoch, "Connecting to Android Auto")
        job = scope.launch(Dispatchers.IO) {
            while (isActive && generation.get() == epoch) {
                var ownedSession: AasdkProjectionSession? = null
                var fatal = false
                streamSessions.clear(); probes.clear(); setupEvidence.clear(); protocolEvents.clear()
                videoPackets = 0; connectionStage = ""; identitySource = "NONE"
                inputReady = false; connectionAlive = false; lastTimestamp = 0; timestampUnits = "unknown"
                server.resetMedia()
                try {
                    val identity = LocalIdentity.load(context)
                    identitySource = identity.source.name
                    val opened = AasdkProjectionSessionConnector(onProgress = {
                        if (generation.get() == epoch) { connectionStage = it; phase = it }
                    }).open("127.0.0.1", 5277, identity,
                        if (BuildConfig.DEBUG) AasdkTlsBuildBoundary.DEBUG else AasdkTlsBuildBoundary.RELEASE,
                        AasdkTlsPeerTrustPolicy.PinnedAuthority("49e52efc13ad2ed09f204c3b10698bd84bb7105f510558aa14b8119a5c4ad17f"))
                    ownedSession = opened.session
                    ensureActive()
                    if (generation.get() != epoch) break
                    val current = opened.session
                    session = current; connectionAlive = true; lastError = ""
                    peerVersion = "${opened.peerMajorVersion}.${opened.peerMinorVersion}"
                    discoveryRequestedFields = Wire.fields(Wire.body(opened.serviceDiscoveryRequest.payload)).map { it.number }
                    current.sendMessage(0, false, Wire.discovery(width, height, if (height == 1080) 220 else 200, rate, bits))
                    connectionStage = "Service discovery response sent"; recordProtocol("tx", 0, 6, false)
                    updatePhase(epoch, "Connected — waiting for projected frames")
                    RecoveryJournal.record(context, "aa-connected")
                    lastRx = SystemClock.elapsedRealtime()
                    coroutineScope {
                        val ping = launch { while (isActive) {
                            delay(5000)
                            if (SystemClock.elapsedRealtime() - lastRx >= 35000) {
                                RecoveryJournal.record(context, "aa-watchdog-expired"); current.close(); error("Android Auto stopped responding")
                            }
                            current.sendMessage(0, false, Wire.msg(0x000B, Wire.v(1, SystemClock.elapsedRealtimeNanos() / 1000)))
                        } }
                        try { while (isActive) {
                            val message = current.receiveMessage(); ensureActive()
                            lastRx = SystemClock.elapsedRealtime(); dispatch(current, message)
                        } } finally { ping.cancel() }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    ensureActive() // An intentionally closed old attempt must never overwrite a new one.
                    if (generation.get() != epoch) break
                    if (idleDisconnecting) break
                    lastError = e.javaClass.simpleName
                    fatal = identitySource == "NONE" || generateSequence<Throwable>(e) { it.cause }.any { it is javax.net.ssl.SSLException }
                    val hint = when {
                        identitySource == "NONE" -> "Client certificate needs attention. Open Settings."
                        fatal -> "Android Auto authentication failed. Check the client certificate."
                        e is java.net.ConnectException -> "Start Android Auto's head-unit server. Luma will retry."
                        else -> "Android Auto disconnected. Reconnecting…"
                    }
                    updatePhase(epoch, hint)
                    RecoveryJournal.record(context, "aa-disconnected", e.javaClass.simpleName)
                } finally {
                    runCatching { ownedSession?.close() }
                    if (generation.get() == epoch && session === ownedSession) {
                        inputReady = false; connectionAlive = false; session = null
                    }
                }
                if (fatal || idleDisconnecting) break
                reconnectAttempts++
                delay(minOf(15000L, 1000L shl minOf(reconnectAttempts - 1, 4)))
            }
        }
    }
    @Synchronized fun stop() {
        generation.incrementAndGet(); inputReady = false; connectionAlive = false
        val old = session; session = null; job?.cancel(); job = null; phase = "Stopped"
        if (old != null) SocketCleanup.run { old.close() }
    }
    @Synchronized fun disconnectForNoClients() {
        if (idleDisconnecting) return
        idleDisconnecting = true
        val epoch = generation.get()
        val current = session
        val reply = CompletableDeferred<Unit>(); shutdownReply = reply
        phase = "Disconnecting — no displays connected"
        scope.launch(Dispatchers.IO) {
            // The same AA shutdown request a head unit sends when ending a session.
            // Do not force-stop Maps/media or stop AA's developer TCP listener.
            if (current != null) withTimeoutOrNull(1000) {
                runCatching { current.sendMessage(0, false, Wire.msg(0x000F, Wire.v(1, 1))); reply.await() }
            }
            synchronized(this@Receiver) {
                if (generation.get() == epoch) {
                    stop(); server.resetMedia(); phase = "Waiting for a display"
                    RecoveryJournal.record(context, "aa-no-clients-disconnected")
                }
                if (shutdownReply === reply) shutdownReply = null
            }
        }
    }
    fun touch(x: Double, y: Double, action: Int) {
        require(x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0 && action in listOf(0, 1, 2, 3))
        val current = session ?: return
        if (!inputReady) return
        scope.launch(Dispatchers.IO) { runCatching {
            current.sendMessage(1, false, Wire.touch((x * (width - 1)).toInt(), (y * (height - 1)).toInt(), action, SystemClock.elapsedRealtimeNanos() / 1000))
        } }
    }
    fun key(code: Int) {
        require(code in listOf(3, 4, 19, 20, 21, 22, 23, 84, 85, 87, 88, 126, 127))
        val current = session ?: return
        if (!inputReady) return
        scope.launch(Dispatchers.IO) { runCatching {
            current.sendMessage(1, false, Wire.key(code, true, SystemClock.elapsedRealtimeNanos() / 1000))
            current.sendMessage(1, false, Wire.key(code, false, SystemClock.elapsedRealtimeNanos() / 1000))
        } }
    }
    fun refreshVideo() {
        val current = session ?: return
        val now = SystemClock.elapsedRealtime()
        val previous = lastVideoRefresh.get()
        if (now - previous < 2000 || !lastVideoRefresh.compareAndSet(previous, now)) return
        scope.launch(Dispatchers.IO) { runCatching {
            current.sendMessage(3, false, Wire.msg(0x8008, Wire.v(1, 1) + Wire.v(2, 1)))
        } }
    }
    private suspend fun dispatch(current: AasdkProjectionSession, message: AasdkSessionMessage) {
        val id = Wire.id(message.payload); val channel = message.channelId
        if (id > 1) recordProtocol("rx", channel, id, message.controlMessage)
        val body = if (id > 1) Wire.body(message.payload) else byteArrayOf()
        if (message.controlMessage) {
            if (id == 7) current.sendMessage(channel, true, Wire.msg(8, Wire.v(1, 0)))
            return
        }
        if (channel == 0) {
            when (id) {
                0x0010 -> shutdownReply?.complete(Unit)
                0x000B -> current.sendMessage(0, false, Wire.msg(0x000C, body))
                0x000F -> { RecoveryJournal.record(context, "aa-peer-ended-session"); current.sendMessage(0, false, Wire.msg(0x0010)); error("Android Auto ended the session") }
                0x0012 -> current.sendMessage(0, false, Wire.msg(0x0013, Wire.v(1, if (Wire.first(body, 1) == 4L) 3 else 1)))
                0x000D -> current.sendMessage(0, false, Wire.msg(0x000E, Wire.v(1, 2)))
            }
            return
        }
        if (channel == 1 && id == 0x8002) {
            current.sendMessage(1, false, Wire.msg(0x8003, Wire.v(1, 0))); inputReady = true; return
        }
        if (channel == 2 && id == 0x8001) {
            current.sendMessage(2, false, Wire.msg(0x8002, Wire.v(1, 0)))
            if (Wire.first(body, 1) == 10L) {
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                current.sendMessage(2, false, Wire.msg(0x8003, Wire.b(10, Wire.v(1, if (hour < 7 || hour >= 19) 1 else 0))))
            }
            return
        }
        if (channel == 7) {
            when (id) {
                0x8000 -> current.sendMessage(7, false, Wire.msg(0x8003, Wire.v(1, 2) + Wire.v(2, 1) + Wire.v(3, 0)))
                // The channel participates in setup even when no microphone source is active.
                // Explicitly report unavailable; never send fabricated or silently captured audio.
                0x8005 -> current.sendMessage(7, false, Wire.msg(0x8006, Wire.v(1, 0) + Wire.v(2, if (Wire.first(body, 1) == 1L) 1 else 0)))
            }
            return
        }
        if (channel !in 3..6) return
        when (id) {
            0x8007 -> if (channel == 3) current.sendMessage(3, false, Wire.msg(0x8008, Wire.v(1, 1)))
            0x8000 -> {
                setupEvidence.add(JSONObject().put("channel", channel).put("event", "setupRequest")
                    .put("varints", JSONArray(Wire.fields(body).filter { it.value != null }.map { "${it.number}=${it.value}" })))
                current.sendMessage(channel, false, Wire.msg(0x8003, Wire.v(1, 2) + Wire.v(2, 1) + Wire.v(3, 0)))
                if (channel == 3) current.sendMessage(3, false, Wire.msg(0x8008, Wire.v(1, 1)))
            }
            0x8001 -> {
                streamSessions[channel] = (Wire.first(body, 1) ?: 0).toInt()
                setupEvidence.add(JSONObject().put("channel", channel).put("event", "startIndication")
                    .put("varints", JSONArray(Wire.fields(body).filter { it.value != null }.map { "${it.number}=${it.value}" })))
                if (channel != 3) probes[channel] = AudioProbe(if (channel == 4) rate else 16000, if (channel == 4) bits else 16, if (channel == 4) 2 else 1)
            }
            0x8002 -> streamSessions.remove(channel)
            0, 1 -> {
                val stream = streamSessions[channel] ?: return
                val offset = if (id == 0) 10 else 2
                require(message.payload.size > offset) { "Empty media payload" }
                val pts = if (id == 0) ByteBuffer.wrap(message.payload, 2, 8).long else null
                val data = message.payload.copyOfRange(offset, message.payload.size)
                if (channel == 3) {
                    var duration = 1000.0 / 30
                    if (pts != null && lastTimestamp > 0 && pts > lastTimestamp) {
                        val delta = pts - lastTimestamp
                        timestampUnits = when { delta > 1_000_000 -> "nanoseconds"; delta > 1000 -> "microseconds"; else -> "milliseconds" }
                        duration = delta.toDouble() / when (timestampUnits) { "nanoseconds" -> 1e6; "microseconds" -> 1e3; else -> 1.0 }
                    }
                    if (pts != null) lastTimestamp = pts
                    server.video(data, duration.coerceIn(1.0, 1000.0))
                    videoPackets++; lastVideoAt = SystemClock.elapsedRealtime(); phase = "Streaming Android Auto"
                } else {
                    val probe = probes[channel] ?: return
                    probe.accept(data, pts, SystemClock.elapsedRealtime())
                    server.audio(channel, probe.rate, probe.bits, probe.channels, data)
                }
                current.sendMessage(channel, false, Wire.ack(stream))
            }
        }
    }
    private fun recordProtocol(direction: String, channel: Int, id: Int, control: Boolean) {
        if (protocolEvents.size >= 64) protocolEvents.removeAt(0)
        protocolEvents.add(JSONObject().put("direction", direction).put("channel", channel)
            .put("messageId", id).put("control", control))
    }
    fun diagnostics(): JSONObject = JSONObject().put("connectionAlive", connectionAlive).put("reconnectAttempts", reconnectAttempts).put("phase", phase).put("error", lastError).put("peerProtocol", peerVersion)
        .put("connectionStage", connectionStage).put("identitySource", identitySource).put("width", width).put("height", height).put("inputReady", inputReady).put("videoPackets", videoPackets)
        .put("lastVideoAgeMs", if (lastVideoAt > 0) SystemClock.elapsedRealtime() - lastVideoAt else JSONObject.NULL)
        .put("videoTimestampUnitInferred", timestampUnits).put("offeredMediaBitDepth", bits).put("offeredMediaSampleRate", rate)
        .put("offeredVideoFps", Wire.OFFERED_VIDEO_FPS)
        .put("serviceDiscoveryRequestFieldNumbers", JSONArray(discoveryRequestedFields))
        .put("peerSetupMessages", JSONArray(setupEvidence.takeLast(32))).put("protocolEvents", JSONArray(protocolEvents))
        .put("audio", JSONObject().apply { probes.forEach { (channel, probe) -> put(channel.toString(), JSONObject(probe.facts())) } })
        .put("browserTransport", server.transportStats())
}

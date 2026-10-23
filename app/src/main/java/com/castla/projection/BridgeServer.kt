// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

/** HTTP application server. Media and browser controls are carried by WebRTC. */
internal class BridgeServer(private val service: ProjectionService, val token: String) : NanoHTTPD(PORT) {
    companion object { const val PORT = 9092; const val HEADER_BYTES = 20; private const val MAX_RTC_BODY = 128 * 1024; private const val MAX_DISPLAY_BODY = 128 }
    lateinit var receiver: Receiver
    private val sequence = AtomicInteger()
    private val reportedPlayback = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private val audioCrc = java.util.Collections.synchronizedMap(object : LinkedHashMap<Int, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Long>?) = size > 256
    })
    private val confirmed = AtomicLong(); private val mismatches = AtomicLong()
    private val shuttingDown = java.util.concurrent.atomic.AtomicBoolean(false)
    private val displayPrefs = service.getSharedPreferences("display-settings", android.content.Context.MODE_PRIVATE)
    private val rtcLifecycleLock = Any()
    @Volatile private var rtcInstance: WebRtcTransport? = null
    private val rtcDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        WebRtcTransport(service, { receiver.refreshVideo() }, { id, text -> handleControl(id, text) },
            { id -> service.clientSeen(id) }, { id -> reportedPlayback.remove(id); service.clientLeft(id) }, maxFps())
    }

    private fun authorized(s: IHTTPSession): Boolean {
        val supplied = s.headers["authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ") ?: return false
        return java.security.MessageDigest.isEqual(supplied.toByteArray(), token.toByteArray())
    }
    private fun originAllowed(s: IHTTPSession): Boolean = s.headers["origin"]?.let { it == "http://${s.headers["host"]}" } ?: true
    private fun strictOriginAllowed(s: IHTTPSession): Boolean = RtcRequestPolicy.sameOrigin(s.headers["origin"], s.headers["host"])
    private fun json(status: Response.Status, body: JSONObject) = newFixedLengthResponse(status, "application/json", body.toString()).apply { addHeader("Cache-Control", "no-store") }
    private fun body(s: IHTTPSession, maxBytes: Int): String? {
        val length = s.headers["content-length"]?.toLongOrNull() ?: return null
        if (!RtcRequestPolicy.validBodyLength(length, maxBytes)) return null
        val parsed = HashMap<String, String>()
        runCatching { s.parseBody(parsed) }.getOrElse { return null }
        val raw = parsed["postData"] ?: return null
        if (raw.toByteArray(Charsets.UTF_8).size.toLong() != length) return null
        return raw
    }
    private fun clientId(raw: String?) = RtcRequestPolicy.validClientId(raw)
    private fun maxFps(): Int = FrameRateLimit.validate(displayPrefs.getInt("webrtcMaxFps", FrameRateLimit.MAX_ALLOWED_FPS))
    private fun displaySettings(fps: Int = maxFps()) = JSONObject().put("maxFps", fps)
        .put("minFps", FrameRateLimit.MIN_FPS).put("maxAllowedFps", FrameRateLimit.MAX_ALLOWED_FPS).put("step", FrameRateLimit.STEP_FPS)

    override fun serve(s: IHTTPSession): Response {
        if (s.uri == "/api/display") {
            if (s.method == Method.GET) {
                if (!authorized(s) || !originAllowed(s)) return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Pair your phone first"))
                return json(Response.Status.OK, displaySettings())
            }
            if (s.method != Method.POST) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "application/json", "{}")
            if (!authorized(s) || !strictOriginAllowed(s)) return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Pair your phone first"))
            val raw = body(s, MAX_DISPLAY_BODY) ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Invalid display settings"))
            val request = runCatching { JSONObject(raw) }.getOrNull() ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Invalid display settings"))
            val requested = request.opt("maxFps")
            val integer = when (requested) {
                is Int -> requested
                is Long -> requested.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                else -> null
            }
            val fps = integer?.let { runCatching { FrameRateLimit.validate(it) }.getOrNull() }
                ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "maxFps must be 10, 15, 20, 25, or 30"))
            if (!displayPrefs.edit().putInt("webrtcMaxFps", fps).commit())
                return json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "Unable to save display settings"))
            rtcInstance?.setMaxFps(fps)
            return json(Response.Status.OK, displaySettings(fps))
        }
        if (s.uri == "/api/rtc/offer") {
            if (s.method != Method.POST) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "application/json", "{}")
            if (!authorized(s) || !strictOriginAllowed(s)) return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Pair your phone first"))
            val raw = body(s, MAX_RTC_BODY) ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Invalid offer"))
            val request = runCatching { JSONObject(raw) }.getOrNull() ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Invalid offer"))
            val id = clientId(request.optString("client", null))
            val sdp = request.optString("sdp", "")
            if (request.optString("type") != "offer" || id == null || sdp.isBlank() || sdp.length > MAX_RTC_BODY)
                return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Invalid offer"))
            if (shuttingDown.get()) return json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "Transport is shutting down"))
            return try { json(Response.Status.OK, answer(sdp, id)) }
            catch (_: Exception) { json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Unable to establish media session")) }
        }
        if (s.uri == "/api/rtc/session" || s.uri == "/api/rtc/close") {
            val isDelete = s.uri == "/api/rtc/session" && s.method == Method.DELETE
            val isPost = s.uri == "/api/rtc/close" && s.method == Method.POST
            if (!isDelete && !isPost) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "application/json", "{}")
            if (!authorized(s) || !strictOriginAllowed(s)) return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Pair your phone first"))
            val request = if (isDelete) JSONObject().put("session", s.parameters["session"]?.firstOrNull()).put("client", s.parameters["client"]?.firstOrNull())
                else runCatching { JSONObject(body(s, 512) ?: "") }.getOrNull()
            if (request == null) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Invalid session"))
            val session = RtcRequestPolicy.validSession(request.optString("session", ""))
            val client = clientId(request.optString("client", null))
            if (session == null || client == null) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Invalid session"))
            val transport = rtcInstance ?: return json(Response.Status.OK, JSONObject().put("closed", false))
            val closed = synchronized(rtcLifecycleLock) {
                if (shuttingDown.get()) false else transport.closeSession(session, client)
            }
            return json(Response.Status.OK, JSONObject().put("closed", closed))
        }
        if (s.uri == "/api/presence") {
            if (s.method != Method.POST || !authorized(s) || !originAllowed(s))
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"error\":\"Pair your phone first\"}")
            val id = clientId(s.parameters["client"]?.firstOrNull())
            if (id == null) return json(Response.Status.BAD_REQUEST, JSONObject())
            if (s.parameters["state"]?.firstOrNull() == "left") service.clientLeft(id) else service.clientSeen(id)
            return json(Response.Status.OK, service.clientFacts())
        }
        if (s.uri == "/api/pair") {
            if (s.method != Method.POST || !originAllowed(s)) return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"error\":\"Pair from the phone page\"}")
            val raw = body(s, 128) ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Enter the six-digit code\"}")
            val accepted = runCatching { service.pairing.redeem(JSONObject(raw).optString("code")) }.getOrDefault(false)
            return json(if (accepted) Response.Status.OK else Response.Status.UNAUTHORIZED,
                if (accepted) JSONObject().put("token", token) else JSONObject().put("error", "Code expired or incorrect. Open a new code on the phone."))
        }
        if (s.uri == "/api/status") {
            if (!authorized(s) || !originAllowed(s)) return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Pair using the link shown on the phone"))
            val localAccess = LocalAccessService.facts()
            return json(Response.Status.OK, receiver.diagnostics().put("clients", service.clientFacts()).put("appVersion", BuildConfig.VERSION_NAME)
                .put("network", localAccess.optJSONObject("network") ?: JSONObject()).put("localAccess", localAccess)
                .put("recovery", RecoveryJournal.read(service)).put("playbackFaults", RecoveryJournal.readPlayback(service))
                .put("hostname", LocalNameResponder.status).put("rtc", rtcDiagnostics()))
        }
        if (s.uri == "/api/weather") {
            if (s.method != Method.GET || !authorized(s) || !originAllowed(s)) return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Pair your phone first"))
            val theme = if (service.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
            return json(Response.Status.OK, service.weather.snapshot().put("theme", theme).put("clients", service.clientFacts()))
        }
        if (s.method != Method.GET) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "GET only")
        val file = when (s.uri) {
            "/", "/index.html" -> "index.html"; "/aa", "/aa/", "/aa.html" -> "aa.html"
            "/launcher.js" -> "launcher.js"; "/launcher.css" -> "launcher.css"; "/browser-pairing.js" -> "browser-pairing.js"
            "/presence.js" -> "presence.js"; "/weather.js" -> "weather.js"; "/display-settings.js" -> "display-settings.js"; "/client.js" -> "client.js"; "/pcm.js" -> "pcm.js"
            "/style.css" -> "style.css"; "/mark.svg", "/favicon.ico" -> "mark.svg"
            else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        val mime = when { file.endsWith(".js") -> "application/javascript"; file.endsWith(".css") -> "text/css"; file.endsWith(".svg") -> "image/svg+xml"; else -> "text/html" }
        return newChunkedResponse(Response.Status.OK, mime, service.assets.open("web/$file")).apply {
            addHeader("Cache-Control", "no-store"); addHeader("Referrer-Policy", "no-referrer")
            addHeader("Content-Security-Policy", "default-src 'self'; connect-src 'self'; media-src 'self' blob:; script-src 'self'; style-src 'self'; object-src 'none'; frame-ancestors 'none'")
        }
    }

    private fun handleControl(id: String, text: String) {
        if (text.length > 2048) return
        service.clientSeen(id)
        try {
            val body = JSONObject(text)
            when (body.optString("type")) {
                "keepAlive" -> Unit
                "refreshVideo" -> receiver.refreshVideo()
                "touch" -> receiver.touch(body.getDouble("x"), body.getDouble("y"), body.getInt("action"))
                "key" -> receiver.key(body.getInt("code"))
                "playbackError" -> {
                    val summary = PlaybackFault.summarize(body.optString("stage"), body.optString("kind"), body.optString("name"), body.optString("renderer"),
                        body.optInt("channel", -1), body.optInt("bits", -1), body.optInt("channels", -1), body.optLong("rate", -1), body.optBoolean("secure")) ?: return
                    val reports = reportedPlayback.computeIfAbsent(id) { java.util.Collections.synchronizedSet(mutableSetOf()) }
                    synchronized(reports) { if (reports.size >= 8 || !reports.add(summary)) return }
                    RecoveryJournal.recordPlayback(service, summary)
                }
                "audioAck" -> {
                    val expected = audioCrc[body.getInt("seq")]
                    if (expected != null) { if (body.getLong("crc") == expected) confirmed.incrementAndGet() else mismatches.incrementAndGet() }
                }
            }
        } catch (_: Exception) { /* malformed input cannot reach the receiver */ }
    }

    private fun answer(sdp: String, clientId: String): JSONObject = synchronized(rtcLifecycleLock) {
        check(!shuttingDown.get()) { "Transport is shutting down" }
        val transport = rtcInstance ?: rtcDelegate.value.also { rtcInstance = it }
        transport.setMaxFps(maxFps())
        transport.answer(sdp, clientId)
    }

    /** Create the decoder before AA starts sending SPS/PPS and its initial IDR. */
    fun prepareMedia() = synchronized(rtcLifecycleLock) {
        check(!shuttingDown.get()) { "Transport is shutting down" }
        val transport = rtcInstance ?: rtcDelegate.value.also { rtcInstance = it }
        transport.setMaxFps(maxFps())
    }

    private fun rtcDiagnostics(): JSONObject = rtcInstance?.let { transport ->
        runCatching { transport.diagnostics().put("initialized", true) }
            .getOrElse { JSONObject().put("initialized", false) }
    } ?: JSONObject().put("initialized", false).put("maxFps", maxFps()).put("framesForwarded", 0).put("framerateDrops", 0)

    @Synchronized fun resetMedia() { audioCrc.clear(); confirmed.set(0); mismatches.set(0); runCatching { rtcInstance?.resetMedia() } }
    @Synchronized fun video(data: ByteArray, duration: Double) { rtcInstance?.let { runCatching { it.video(data, duration) } } }
    fun audio(channel: Int, rate: Int, bits: Int, count: Int, pcm: ByteArray) {
        val seq = sequence.incrementAndGet()
        audioCrc[seq] = CRC32().apply { update(pcm) }.value
        val packet = ByteBuffer.allocate(HEADER_BYTES + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(2).put(channel.toByte()).put(bits.toByte()).put(count.toByte()).putInt(rate).putInt(seq).putDouble(0.0).put(pcm).array()
        rtcInstance?.let { runCatching { it.audio(packet) } }
    }
    fun transportStats(): JSONObject = JSONObject().put("pcmChecksumMatches", confirmed.get()).put("pcmChecksumMismatches", mismatches.get()).put("videoTranscoded", true)
    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return
        SocketCleanup.run {
            runCatching { stop() }
            synchronized(rtcLifecycleLock) {
                val transport = rtcInstance
                rtcInstance = null
                runCatching { transport?.close() }
            }
        }
    }
}

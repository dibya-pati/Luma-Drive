// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import fi.iki.elonen.NanoWSD
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/** Public diagnostic page contains no account data, pairing tokens or media. */
internal class LocalAccessServer(private val service: LocalAccessService) : NanoWSD(LocalAccessService.PORT), AutoCloseable {
    private val clients = CopyOnWriteArraySet<WebSocket>()
    private val requests = AtomicLong()
    override fun serveHttp(s: IHTTPSession): Response {
        if (s.method != Method.GET) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "GET only")
        requests.incrementAndGet()
        val file = when (s.uri) { "/", "/test" -> "index.html"; "/probe.js" -> "probe.js"; else -> null }
        if (file == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        return newChunkedResponse(Response.Status.OK, if (file.endsWith(".js")) "application/javascript" else "text/html", service.assets.open("probe/$file")).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Referrer-Policy", "no-referrer")
            addHeader("Content-Security-Policy", "default-src 'self'; connect-src 'self'; script-src 'self'; style-src 'none'; object-src 'none'; frame-ancestors 'none'")
        }
    }
    override fun openWebSocket(s: IHTTPSession): WebSocket = object : WebSocket(s) {
        private val allowed = s.uri == "/echo" &&
            (s.headers["origin"] == null || s.headers["origin"] == "http://${s.headers["host"]}")
        override fun onOpen() {
            if (!allowed || clients.size >= 8) { close(WebSocketFrame.CloseCode.PolicyViolation, "Test page only", false); return }
            clients.add(this)
        }
        override fun onMessage(frame: WebSocketFrame) {
            if (frame.textPayload == "ping") send("pong")
            else close(WebSocketFrame.CloseCode.PolicyViolation, "Ping only", false)
        }
        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) { clients.remove(this) }
        override fun onException(exception: java.io.IOException?) { clients.remove(this) }
        override fun onPong(frame: WebSocketFrame?) {}
    }
    fun counts() = "Page requests: ${requests.get()} · test sockets: ${clients.size}"
    /** Called only by the dedicated echo worker; returns after the port is released. */
    override fun close() {
        val closing = clients.toList(); clients.clear()
        closing.forEach { runCatching { it.handshakeRequest.inputStream.close() } }
        stop()
    }
}

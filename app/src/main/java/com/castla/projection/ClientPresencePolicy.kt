// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

/** Tracks browser clients while allowing the receiver a short gap before teardown. */
class ClientPresencePolicy(
    private val clientTtlMs: Long = CLIENT_TTL_MS,
    private val graceMs: Long = DISCONNECT_GRACE_MS,
) {
    data class Decision(
        val activeCount: Int,
        val shouldDisconnect: Boolean = false,
        val shouldResume: Boolean = false,
    )

    private val lastSeen = mutableMapOf<String, Long>()
    private var running = false
    private var disconnected = false
    private var emptySince: Long? = null

    @Synchronized
    fun start(now: Long): Decision {
        lastSeen.clear()
        running = true
        disconnected = false
        emptySince = now
        return Decision(activeCount = 0)
    }

    @Synchronized
    fun seen(clientId: String, now: Long): Decision {
        if (!running) return Decision(activeCount = 0)
        val due = advance(now)
        if (clientId !in lastSeen && lastSeen.size >= MAX_CLIENTS) {
            return Decision(lastSeen.size, shouldDisconnect = due)
        }
        val wasDisconnected = disconnected
        lastSeen[clientId] = now
        emptySince = null
        disconnected = false
        return Decision(
            activeCount = lastSeen.size,
            shouldDisconnect = due,
            shouldResume = wasDisconnected,
        )
    }

    @Synchronized
    fun left(clientId: String, now: Long): Decision {
        if (!running) return Decision(activeCount = 0)
        val due = advance(now)
        val removed = lastSeen.remove(clientId) != null
        if (removed && lastSeen.isEmpty()) emptySince = now
        return Decision(lastSeen.size, shouldDisconnect = due)
    }

    @Synchronized
    fun tick(now: Long): Decision {
        if (!running) return Decision(activeCount = 0)
        val due = advance(now)
        return Decision(lastSeen.size, shouldDisconnect = due)
    }

    @Synchronized
    fun stop() {
        lastSeen.clear()
        running = false
        disconnected = false
        emptySince = null
    }

    private fun advance(now: Long): Boolean {
        val expiredAt = lastSeen.values
            .filter { now >= it && now - it >= clientTtlMs }
            .maxOrNull()
        if (expiredAt != null) {
            lastSeen.entries.removeAll { (_, seenAt) -> now >= seenAt && now - seenAt >= clientTtlMs }
            if (lastSeen.isEmpty()) emptySince = expiredAt
        }
        val since = emptySince
        if (!disconnected && lastSeen.isEmpty() && since != null && now >= since && now - since >= graceMs) {
            disconnected = true
            return true
        }
        return false
    }

    companion object {
        const val CLIENT_TTL_MS = 15_000L
        const val DISCONNECT_GRACE_MS = 30_000L
        const val MAX_CLIENTS = 64
    }
}

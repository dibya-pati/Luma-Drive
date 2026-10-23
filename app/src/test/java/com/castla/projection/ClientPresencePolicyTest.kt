// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import org.junit.Assert.*
import org.junit.Test

class ClientPresencePolicyTest {
    @Test fun `startup without viewers disconnects after grace exactly once`() {
        val policy = ClientPresencePolicy()
        assertEquals(0, policy.start(1_000).activeCount)
        assertFalse(policy.tick(30_999).shouldDisconnect)
        assertTrue(policy.tick(31_000).shouldDisconnect)
        assertFalse(policy.tick(90_000).shouldDisconnect)
    }

    @Test fun `launcher and AA viewer keep receiver active until both leave`() {
        val policy = ClientPresencePolicy()
        policy.start(0)
        assertEquals(1, policy.seen("launcher", 1).activeCount)
        assertEquals(2, policy.seen("aa-viewer", 2).activeCount)
        assertEquals(1, policy.left("launcher", 3).activeCount)
        policy.seen("aa-viewer", 15_000)
        policy.seen("aa-viewer", 29_999)
        assertEquals(0, policy.left("aa-viewer", 30_001).activeCount)
        assertFalse(policy.tick(60_000).shouldDisconnect)
        assertTrue(policy.tick(60_001).shouldDisconnect)
    }

    @Test fun `heartbeat refresh prevents ttl expiry and late heartbeat resumes once`() {
        val policy = ClientPresencePolicy()
        policy.start(0)
        policy.seen("browser", 1)
        policy.seen("browser", 14_000)
        assertFalse(policy.tick(28_999).shouldDisconnect)
        assertTrue(policy.tick(44_000).shouldDisconnect)
        val resumed = policy.seen("browser", 44_001)
        assertEquals(1, resumed.activeCount)
        assertTrue(resumed.shouldResume)
        assertFalse(policy.seen("browser", 44_002).shouldResume)
    }

    @Test fun `silent client grace starts at its last heartbeat`() {
        val policy = ClientPresencePolicy()
        policy.start(0)
        policy.seen("browser", 1_000)
        assertFalse(policy.tick(30_999).shouldDisconnect)
        assertTrue(policy.tick(31_000).shouldDisconnect)
    }

    @Test fun `rejoin during grace cancels pending disconnect`() {
        val policy = ClientPresencePolicy()
        policy.start(0)
        policy.seen("browser", 1)
        policy.left("browser", 2)
        policy.seen("aa-viewer", 30_000)
        policy.seen("aa-viewer", 44_000)
        assertFalse(policy.tick(58_999).shouldDisconnect)
        assertFalse(policy.tick(59_000).shouldDisconnect)
        assertTrue(policy.tick(74_000).shouldDisconnect)
    }

    @Test fun `stop clears presence and ignores later events`() {
        val policy = ClientPresencePolicy()
        policy.start(0)
        policy.seen("browser", 1)
        policy.stop()
        assertEquals(0, policy.tick(100_000).activeCount)
        assertEquals(0, policy.seen("browser", 100_001).activeCount)
    }

    @Test fun `active client set is capped at sixty four unique ids`() {
        val policy = ClientPresencePolicy()
        policy.start(0)
        repeat(ClientPresencePolicy.MAX_CLIENTS) { index ->
            assertEquals(index + 1, policy.seen("client-$index", index.toLong() + 1).activeCount)
        }
        assertEquals(ClientPresencePolicy.MAX_CLIENTS, policy.seen("overflow", 100).activeCount)
        assertEquals(ClientPresencePolicy.MAX_CLIENTS, policy.seen("client-0", 101).activeCount)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import org.junit.Assert.*
import org.junit.Test

class RtcRequestPolicyTest {
    @Test fun signalingRequiresSameOriginAndBoundedBodies() {
        assertTrue(RtcRequestPolicy.sameOrigin("http://phone.local:9092", "phone.local:9092"))
        assertFalse(RtcRequestPolicy.sameOrigin(null, "phone.local:9092"))
        assertFalse(RtcRequestPolicy.sameOrigin("https://phone.local:9092", "phone.local:9092"))
        assertFalse(RtcRequestPolicy.sameOrigin("http://evil.local", "phone.local:9092"))
        assertTrue(RtcRequestPolicy.validBodyLength(1, 128 * 1024))
        assertTrue(RtcRequestPolicy.validBodyLength(128 * 1024L, 128 * 1024))
        assertFalse(RtcRequestPolicy.validBodyLength(0, 128 * 1024))
        assertFalse(RtcRequestPolicy.validBodyLength(128 * 1024L + 1, 128 * 1024))
        assertFalse(RtcRequestPolicy.validBodyLength(null, 128 * 1024))
    }

    @Test fun clientAndSessionIdsRejectMalformedValues() {
        assertEquals("browser_client-123", RtcRequestPolicy.validClientId("browser_client-123"))
        assertNull(RtcRequestPolicy.validClientId("short"))
        assertNull(RtcRequestPolicy.validClientId("browser client 123"))
        assertEquals("session_12345678", RtcRequestPolicy.validSession("session_12345678"))
        assertNull(RtcRequestPolicy.validSession("session?token=secret"))
        assertTrue(RtcRequestPolicy.ownsSession("browser_client-123", "browser_client-123"))
        assertFalse(RtcRequestPolicy.ownsSession("browser_client-123", "other_client_123"))
        assertFalse(RtcRequestPolicy.ownsSession(null, "browser_client-123"))
    }
}

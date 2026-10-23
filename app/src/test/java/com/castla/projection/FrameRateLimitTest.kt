// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import org.junit.Assert.*
import org.junit.Test

class FrameRateLimitTest {
    @Test fun firstFrameIsImmediateAndThirtyFpsHasNoRoundingHalving() {
        var now = 7L
        val gate = FrameRateLimit(30) { now }
        assertTrue(gate.admit())
        repeat(30) {
            now += 33_333_333L
            gate.admit()
        }
        assertTrue((gate.stats()["framesForwarded"] as Long) in 29L..31L)
    }

    @Test fun fifteenFpsDropsHalfAndJitterDoesNotCauseArbitraryHalving() {
        var now = 0L
        val gate = FrameRateLimit(15) { now }
        assertTrue(gate.admit())
        repeat(60) {
            now += 33_000_000L
            gate.admit()
        }
        assertTrue((gate.stats()["framesForwarded"] as Long) in 29L..31L)
        assertTrue((gate.stats()["framerateDrops"] as Long) in 29L..31L)
    }

    @Test fun thirtyFpsGateAbsorbsOneMillisecondJitter() {
        var now = 0L
        val gate = FrameRateLimit(30) { now }
        assertTrue(gate.admit())
        repeat(30) { index ->
            now += if (index % 3 == 1) 33_000_000L else 34_000_000L
            gate.admit()
        }
        assertTrue((gate.stats()["framesForwarded"] as Long) in 29L..31L)
    }

    @Test fun changeAndResetAdmitNextFrameImmediately() {
        var now = 0L
        val gate = FrameRateLimit(30) { now }
        assertTrue(gate.admit())
        assertFalse(gate.admit())
        gate.setMaxFps(10)
        assertTrue(gate.admit())
        assertFalse(gate.admit())
        now += 98_999_999L
        assertFalse(gate.admit())
        now += 1_000_001L
        assertTrue(gate.admit())
        gate.reset()
        assertTrue(gate.admit())
    }

    @Test fun monotonicWrapAndBackwardsJumpResetTimingEpoch() {
        var now = Long.MAX_VALUE - 10_000_000L
        val gate = FrameRateLimit(30) { now }
        assertTrue(gate.admit())
        now = Long.MIN_VALUE + 30_000_000L // signed wrap, 40ms elapsed
        assertTrue(gate.admit())
        now -= 100_000_000L // clock moved backwards within the new epoch
        assertTrue(gate.admit())
    }

    @Test fun onlySupportedRatesAreAccepted() {
        for (fps in listOf(10, 15, 20, 25, 30)) assertEquals(fps, FrameRateLimit.validate(fps))
        for (fps in listOf(0, 9, 11, 17, 29, 31, 60)) assertThrows(IllegalArgumentException::class.java) { FrameRateLimit.validate(fps) }
    }
}

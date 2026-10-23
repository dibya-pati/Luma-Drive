// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection
import org.junit.Assert.*
import org.junit.Test
class AudioProbeTest {
    @Test fun measuresThroughputWithoutClaimingPrecision() {
        val probe = AudioProbe(48000,24,2)
        probe.accept(ByteArray(2880),1000,0)
        probe.accept(ByteArray(2880),101000,10)
        val facts = probe.facts()
        assertEquals(288000, facts["expectedBytesPerSecond"])
        assertEquals(288000.0, facts["wallClockBytesPerSecond"])
        assertEquals(100000L, facts["timestampSpanRaw"])
        assertEquals(false, facts["precisionVerified"])
    }
    @Test fun recordsAlignmentAndLowByteEvidence() {
        val probe = AudioProbe(48000,24,2)
        probe.accept(byteArrayOf(1,0,0,1,0),null,10)
        val facts = probe.facts()
        assertEquals(1L, facts["unalignedPacketsForAdvertisedFormat"])
        assertEquals(2L, facts["lowByteNonzeroFor24BitInterpretation"])
        assertEquals(0.0, facts["wallClockBytesPerSecond"])
    }
}

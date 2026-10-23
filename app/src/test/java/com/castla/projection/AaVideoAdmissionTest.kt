// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import org.junit.Assert.*
import org.junit.Test

class AaVideoAdmissionTest {
    @Test fun metadataOnlyPacketsAreRetainedUntilFreshIdr() {
        val gate = AaVideoAdmission()
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte())
        assertTrue(gate.updateMetadata(mapOf(7 to sps)))
        assertTrue(gate.updateMetadata(mapOf(8 to pps)))
        assertFalse(gate.admit(setOf(7)))
        assertFalse(gate.admit(setOf(8)))
        val retained = gate.parameterSets()!!
        assertArrayEquals(sps, retained.first)
        assertArrayEquals(pps, retained.second)
        assertTrue(gate.admit(setOf(5)))
        assertTrue(gate.admit(setOf(1)))
    }

    @Test fun queueLossResyncsUntilANewIdr() {
        val gate = AaVideoAdmission()
        gate.updateMetadata(mapOf(7 to byteArrayOf(1), 8 to byteArrayOf(2)))
        assertTrue(gate.admit(setOf(5)))
        assertTrue(gate.resync())
        assertTrue(gate.waitingForIdr())
        assertFalse(gate.admit(setOf(1)))
        assertTrue(gate.admit(setOf(5)))
        assertFalse(gate.waitingForIdr())
    }

    @Test fun csdAlwaysHasAnnexBStartCode() {
        val startCode = byteArrayOf(0, 0, 0, 1)
        val nal = byteArrayOf(0x67, 0x42)
        assertArrayEquals(startCode + nal, AaVideoAdmission.ensureAnnexBStartCode(nal))
        assertArrayEquals(startCode + nal, AaVideoAdmission.ensureAnnexBStartCode(startCode + nal))
        assertArrayEquals(startCode + nal, AaVideoAdmission.ensureAnnexBStartCode(byteArrayOf(0, 0, 1) + nal))
    }

    @Test fun spsDimensionsMatch720pAnd1080p() {
        val prefix = byteArrayOf(0, 0, 0, 1)
        val sps720 = prefix + "6742c01fda014016ec0440000003004000000f23c60ca8".hexBytes()
        val sps1080 = prefix + "6742c029da01e0089f970110000003001000000303c8f1832a".hexBytes()
        assertEquals(1280 to 720, H264SpsDimensions.parse(sps720))
        assertEquals(1920 to 1080, H264SpsDimensions.parse(sps1080))
    }

    private fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

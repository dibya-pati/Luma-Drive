// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import org.junit.Assert.*
import org.junit.Test

class LiveMediaGateTest {
    @Test fun metadataExtractionNeverRetainsSlicesFromMixedPacket() {
        val packet = byteArrayOf(0,0,0,1,0x67,1,2, 0,0,1,0x68,3, 0,0,0,1,0x65,9,8, 0,0,1,0x41,7)
        val metadata = H264Metadata.extract(packet)
        assertEquals(setOf(7, 8), metadata.keys)
        assertEquals(setOf(7), H264Metadata.types(metadata.getValue(7)))
        assertEquals(setOf(8), H264Metadata.types(metadata.getValue(8)))
        assertFalse(metadata.values.any { 5 in H264Metadata.types(it) || 1 in H264Metadata.types(it) })
    }

    @Test fun newViewerWaitsForFreshIdrAndResyncRequiresAnotherIdr() {
        val gate = LiveVideoGate()
        assertTrue(gate.isWaiting())
        assertFalse(gate.accept(setOf(7, 8, 1)))
        assertFalse(gate.accept(setOf(1)))
        assertTrue(gate.accept(setOf(7, 8, 5)))
        assertFalse(gate.isWaiting())
        assertTrue(gate.accept(setOf(1)))
        gate.resync()
        assertFalse(gate.accept(setOf(1)))
        assertTrue(gate.accept(setOf(5)))
    }

    @Test fun parserIgnoresEmptyAndTrailingStartCodesAcrossPrefixSizes() {
        val packet = byteArrayOf(0,0,1, 0,0,0,1,0x67,1, 0,0,0,1, 0,0,1)
        val parsed = H264Metadata.parse(packet)
        assertEquals(setOf(7), parsed.types)
        assertEquals(setOf(7), parsed.codecMetadata.keys)
        assertEquals(setOf(7), H264Metadata.types(parsed.codecMetadata.getValue(7)))
    }
}

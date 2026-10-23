// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import org.junit.Assert.*
import org.junit.Test

class LocalDnsTest {
    private val ip = byteArrayOf(100,99,9,9)
    @Test fun probeClaimsOnlyOurLocalIpv4Name() {
        val p = LocalDns.parse(LocalDns.probe("luma.local", ip))
        assertTrue(LocalDns.requested(p,"luma.local")); assertFalse(LocalDns.requested(p,"other.local"))
        assertFalse(LocalDns.conflict(p,"luma.local",ip))
    }
    @Test fun responseHasAuthoritativeAddressAndDoesNotTriggerAnotherResponse() {
        val p = LocalDns.parse(LocalDns.answer("luma.local",ip))
        assertEquals(0x8400,p.flags); assertArrayEquals(ip,p.records.single().data)
        assertFalse(LocalDns.requested(p,"luma.local"))
    }
    @Test fun conflictingAddressIsDetected() {
        val p = LocalDns.parse(LocalDns.answer("luma.local",byteArrayOf(192.toByte(),0,2,1)))
        assertTrue(LocalDns.conflict(p,"luma.local",ip))
        assertFalse(LocalDns.conflict(p,"other.local",ip))
    }
    @Test fun legacyUnicastKeepsQueryIdAndQuestion() {
        val q=LocalDns.Question("luma.local",1,1)
        val p=LocalDns.parse(LocalDns.answer("luma.local",ip,id=123,legacy=q))
        assertEquals(123,p.id); assertEquals(q,p.questions.single())
    }
    @Test fun malformedAndLoopingCompressedNamesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { LocalDns.parse(ByteArray(5)) }
        val loop=ByteArray(18);loop[5]=1;loop[12]=0xc0.toByte();loop[13]=12
        assertThrows(IllegalArgumentException::class.java) { LocalDns.parse(loop) }
        val cut=LocalDns.probe("luma.local",ip).dropLast(2).toByteArray()
        assertThrows(IllegalArgumentException::class.java) { LocalDns.parse(cut) }
    }
    @Test fun neverAnnouncesNonLocalOrInvalidNames() {
        assertThrows(IllegalArgumentException::class.java) { LocalDns.answer("example.com",ip) }
        assertThrows(IllegalArgumentException::class.java) { LocalDns.answer("bad/name.local",ip) }
    }
    @Test fun boundedRandomInputCannotEscapeAsIndexErrors() {
        val random=java.util.Random(42)
        repeat(2000) {
            val input=ByteArray(random.nextInt(1600));random.nextBytes(input)
            try { LocalDns.parse(input) } catch (e: Exception) { assertTrue(e.javaClass.name,e is IllegalArgumentException) }
        }
    }
}

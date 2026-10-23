// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import org.junit.Assert.*
import org.junit.Test

class LocalRouteSessionTest {
    private class Factory : LocalRouteSession.Factory {
        val events = ArrayList<String>()
        var failEstablish: String? = null
        var failDuplicate = false
        var failClose = false
        override fun establish(address: String): LocalRouteSession.Handle {
            events.add("open:$address")
            if (failEstablish == address) error("establish failed")
            return handle(address)
        }
        private fun handle(name: String): LocalRouteSession.Handle = object : LocalRouteSession.Handle {
            override fun duplicate(): LocalRouteSession.Handle {
                events.add("dup:$name")
                if (failDuplicate) error("dup failed")
                return handle("$name-copy")
            }
            override fun close() { events.add("close:$name"); if (failClose) error("close failed") }
        }
    }
    @Test fun standardOwnsExactlyOneDescriptorAndCloseIsIdempotent() {
        val f = Factory(); val session = LocalRouteSession(f)
        session.start(LocalRouteSession.Mode.STANDARD); session.close(); session.close()
        assertEquals(listOf("open:100.99.9.9", "close:100.99.9.9"), f.events)
        assertNull(session.mode)
    }
    @Test fun retainedPreservesBothAddressHandlesUntilStop() {
        val f = Factory(); val s = LocalRouteSession(f)
        s.start(LocalRouteSession.Mode.RETAINED)
        assertEquals(listOf("open:100.99.9.9", "dup:100.99.9.9", "open:100.99.9.10"), f.events)
        s.close()
        assertEquals(listOf("close:100.99.9.10", "close:100.99.9.9-copy", "close:100.99.9.9"), f.events.takeLast(3))
    }
    @Test fun repeatedStartDoesNotReplaceTheActiveVpn() {
        val f = Factory(); val s = LocalRouteSession(f)
        s.start(LocalRouteSession.Mode.STANDARD); s.start(LocalRouteSession.Mode.STANDARD)
        assertEquals(1, f.events.size); s.close()
    }
    @Test fun switchingModesClosesPreviousResourcesFirst() {
        val f = Factory(); val s = LocalRouteSession(f)
        s.start(LocalRouteSession.Mode.RETAINED); s.start(LocalRouteSession.Mode.STANDARD)
        assertEquals("close:100.99.9.9", f.events[f.events.lastIndex - 1])
        assertEquals("open:100.99.9.9", f.events.last()); s.close()
    }
    @Test fun lostInterfaceRebuildsEvenWhenRequestedModeHasNotChanged() {
        val f = Factory(); val s = LocalRouteSession(f)
        s.start(LocalRouteSession.Mode.RETAINED)
        s.start(LocalRouteSession.Mode.RETAINED, healthy = false)
        assertEquals(4, f.events.count { it.startsWith("open:") })
        assertEquals(3, f.events.count { it.startsWith("close:") }); s.close()
    }
    @Test fun failedFirstInterfaceLeavesNoResources() {
        val f = Factory().apply { failEstablish = LocalRouteSession.ADDRESS }; val s = LocalRouteSession(f)
        assertThrows(IllegalStateException::class.java) { s.start(LocalRouteSession.Mode.STANDARD) }
        assertNull(s.mode); s.close(); assertEquals(1, f.events.size)
    }
    @Test fun failedDuplicateRollsBackOriginal() {
        val f = Factory().apply { failDuplicate = true }; val s = LocalRouteSession(f)
        assertThrows(IllegalStateException::class.java) { s.start(LocalRouteSession.Mode.RETAINED) }
        assertEquals("close:100.99.9.9", f.events.last()); assertNull(s.mode)
    }
    @Test fun failedSecondInterfaceRollsBackBothAddressHandlesAndAllowsRetry() {
        val f = Factory().apply { failEstablish = LocalRouteSession.COMPAT_ADDRESS }; val s = LocalRouteSession(f)
        assertThrows(IllegalStateException::class.java) { s.start(LocalRouteSession.Mode.RETAINED) }
        assertEquals(listOf("close:100.99.9.9-copy", "close:100.99.9.9"), f.events.takeLast(2))
        assertNull(s.mode); f.failEstablish = null; s.start(LocalRouteSession.Mode.STANDARD)
        assertEquals(LocalRouteSession.Mode.STANDARD, s.mode); s.close()
    }
    @Test fun closeFailureStillReleasesRemainingHandles() {
        val f = Factory(); val s = LocalRouteSession(f)
        s.start(LocalRouteSession.Mode.RETAINED); f.failClose = true; s.close(); s.close()
        assertEquals(3, f.events.count { it.startsWith("close:") }); assertNull(s.mode)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class EchoTestSessionTest {
    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!check() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Listener did not reach expected state", check())
    }
    @Test fun blockedStartAndStopDoNotBlockTheCallerOrPublishALateListener() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closed = AtomicInteger()
        val session = EchoTestSession {
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            AutoCloseable { closed.incrementAndGet() }
        }
        try {
            session.start(); assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(EchoTestSession.State.STARTING, session.state)
            val before = System.nanoTime(); session.stop()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before) < 200)
            assertEquals(EchoTestSession.State.STOPPING, session.state)
            release.countDown(); await { session.state == EchoTestSession.State.OFF }
            assertNull(session.resource); assertEquals(1, closed.get())
        } finally { release.countDown(); session.close() }
    }
    @Test fun restartWaitsForTheOldPortToClose() {
        val closing = CountDownLatch(1); val release = CountDownLatch(1); val opens = AtomicInteger()
        var port = 0
        val session = EchoTestSession {
            val socket = ServerSocket(port); port = socket.localPort
            val number = opens.incrementAndGet()
            AutoCloseable {
                if (number == 1) { closing.countDown(); check(release.await(3,TimeUnit.SECONDS)) }
                socket.close()
            }
        }
        try {
            session.start(); await { session.state == EchoTestSession.State.RUNNING }
            session.stop(); assertTrue(closing.await(1,TimeUnit.SECONDS))
            val before = System.nanoTime(); session.start()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before) < 200)
            assertEquals(1,opens.get()); assertEquals(EchoTestSession.State.STARTING,session.state)
            release.countDown(); await { session.state == EchoTestSession.State.RUNNING }
            assertEquals(2,opens.get())
            session.stop(); await { session.state == EchoTestSession.State.OFF }
            ServerSocket(port).close() // A reported stop must actually release the port.
        } finally { release.countDown(); session.close() }
    }
    @Test fun repeatedStartDoesNotCreateDuplicateListeners() {
        val opens = AtomicInteger(); val closed = AtomicInteger()
        val session = EchoTestSession { opens.incrementAndGet(); AutoCloseable { closed.incrementAndGet() } }
        try {
            repeat(20) { session.start() }; await { session.state == EchoTestSession.State.RUNNING }
            assertEquals(1,opens.get()); session.stop(); await { session.state == EchoTestSession.State.OFF }
            assertEquals(1,closed.get())
        } finally { session.close() }
    }
    @Test fun failedStartCanBeRetriedWithoutRebuildingTheVpn() {
        val attempts = AtomicInteger()
        val session = EchoTestSession {
            if(attempts.incrementAndGet()==1) throw java.io.IOException("bind failed")
            AutoCloseable { }
        }
        try {
            session.start(); await { session.state == EchoTestSession.State.FAILED }
            assertEquals("IOException",session.error)
            session.start(); await { session.state == EchoTestSession.State.RUNNING }
            assertEquals(2,attempts.get()); assertEquals("",session.error)
        } finally { session.close() }
    }
    @Test fun serviceDestructionClosesPendingStartAndRejectsNewStarts() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closes = AtomicInteger()
        val session = EchoTestSession {
            entered.countDown(); check(release.await(3,TimeUnit.SECONDS))
            AutoCloseable { closes.incrementAndGet() }
        }
        try {
            session.start(); assertTrue(entered.await(1,TimeUnit.SECONDS)); session.close(); session.start()
            release.countDown(); await { session.state == EchoTestSession.State.OFF }
            assertEquals(1,closes.get()); assertNull(session.resource)
        } finally { release.countDown(); session.close() }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ViewerPumpTest {
    @Test fun blockedBrowserWriteCannotBlockProducerOrCleanup() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val ended = CountDownLatch(1)
        val aborted = AtomicInteger(); val closes = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Int>>()
        val pump = ViewerPump(2, write = {
            entered.countDown()
            // Socket writes need not react to Thread.interrupt; require an actual abort.
            var done = false
            while (!done) try { done = release.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        }, abort = { aborted.incrementAndGet(); release.countDown() }, finished = { ended.countDown() },
            closed = { reason, depth -> closes.add(reason to depth) })
        pump.start(); pump.enqueue(byteArrayOf(1)); assertTrue(entered.await(1, TimeUnit.SECONDS))
        val before = System.nanoTime()
        repeat(4) { pump.enqueue(byteArrayOf(2)) }
        assertTrue("AA producer must not wait for the writer", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 200)
        assertTrue(ended.await(1, TimeUnit.SECONDS)); pump.close(); assertEquals(1, aborted.get())
        assertEquals(listOf("queue-full" to 2), closes.toList())
    }
    @Test fun queuedBootstrapFramesRemainOrdered() {
        val result = java.util.concurrent.CopyOnWriteArrayList<Int>(); val got = CountDownLatch(3)
        val pump = ViewerPump(write = { result.add(it[0].toInt()); got.countDown() }, abort = {}, finished = {})
        repeat(3) { pump.enqueue(byteArrayOf(it.toByte())) }; pump.start()
        assertTrue(got.await(1, TimeUnit.SECONDS)); assertEquals(listOf(0,1,2),result.toList()); pump.close()
    }
    @Test fun flushDropsQueuedAudioAndVideoButKeepsFutureMedia() {
        val result = java.util.concurrent.CopyOnWriteArrayList<Int>(); val got = CountDownLatch(1)
        val pump = ViewerPump(write = { result.add(it[0].toInt()); got.countDown() }, abort = {}, finished = {})
        pump.enqueue(byteArrayOf(1)) // queued video
        pump.enqueue(byteArrayOf(2)) // queued audio
        assertEquals(2, pump.flushPending())
        pump.enqueue(byteArrayOf(3))
        pump.start()
        assertTrue(got.await(1, TimeUnit.SECONDS))
        Thread.sleep(20)
        assertEquals(listOf(3), result.toList())
        pump.close()
    }
}

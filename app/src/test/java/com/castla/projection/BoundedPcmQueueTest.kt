// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BoundedPcmQueueTest {
    @Test fun boundsQueuedBytesAndRetainsOnlyOriginalSessions() {
        val queue = BoundedPcmQueue(maxBytes = 8, maxAgeNs = 100)
        assertTrue(queue.offer(ByteArray(5), setOf("viewer-a"), 0))
        assertFalse(queue.offer(ByteArray(4), setOf("viewer-b"), 1))
        val item = queue.poll()!!
        assertEquals(setOf("viewer-a"), item.sessionIds)
    }

    @Test fun resetInvalidatesQueuedAndInflightItems() {
        val queue = BoundedPcmQueue(maxBytes = 32, maxAgeNs = 100)
        queue.offer(byteArrayOf(1, 2), setOf("viewer-a"), 1)
        val item = queue.poll()!!
        assertTrue(queue.isFresh(item, 2))
        queue.reset()
        assertFalse(queue.isFresh(item, 3))
    }

    @Test fun dropsPacketsPastAgeLimit() {
        val queue = BoundedPcmQueue(maxBytes = 32, maxAgeNs = 10)
        queue.offer(byteArrayOf(1), setOf("viewer-a"), 1)
        assertFalse(queue.isFresh(queue.poll()!!, 12))
    }

    @Test fun blockedNativeSendDoesNotHoldProducerQueueLock() {
        val queue = BoundedPcmQueue(maxBytes = 32, maxAgeNs = 1_000_000_000)
        queue.offer(byteArrayOf(1), setOf("viewer-a"), System.nanoTime())
        val item = queue.poll()!!
        val enteredSend = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val sender = Thread {
            if (queue.isFresh(item, System.nanoTime())) {
                enteredSend.countDown()
                releaseSend.await(2, TimeUnit.SECONDS)
            }
        }
        sender.start()
        assertTrue(enteredSend.await(1, TimeUnit.SECONDS))
        val start = System.nanoTime()
        assertTrue(queue.offer(byteArrayOf(2), setOf("viewer-a"), System.nanoTime()))
        queue.reset()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 100)
        releaseSend.countDown()
        sender.join(1000)
    }
}

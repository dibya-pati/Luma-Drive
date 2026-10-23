// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** A slow browser may lose its connection, but can never hold up AA acknowledgements. */
internal class ViewerPump(
    capacity: Int = 96,
    private val write: (ByteArray) -> Unit,
    private val abort: () -> Unit,
    private val finished: () -> Unit,
    private val closed: (reason: String, queueDepth: Int) -> Unit = { _, _ -> },
) : AutoCloseable {
    private data class Pending(val bytes: ByteArray, val generation: Long)
    private val queue = ArrayBlockingQueue<Pending>(capacity)
    private val queueLock = Any()
    private var generation = 0L
    private val active = AtomicBoolean(true)
    private val started = AtomicBoolean()
    private val writer = Thread({
        try {
            while (active.get()) {
                val pending = queue.take()
                val current = synchronized(queueLock) { pending.generation == generation && active.get() }
                if (current) write(pending.bytes)
            }
        }
        catch (_: Exception) { close("write-failed") }
        finally { close("read-closed") }
    }, "LumaBrowserWriter").apply { isDaemon = true }
    fun start() { if (active.get() && started.compareAndSet(false, true)) writer.start() }
    fun enqueue(bytes: ByteArray) {
        val full = synchronized(queueLock) { active.get() && !queue.offer(Pending(bytes, generation)) }
        if (full) close("queue-full")
    }
    /** Drop queued media on resync; a packet already selected by the writer may still be sent. */
    fun flushPending(): Int = synchronized(queueLock) {
        generation++
        val count = queue.size
        queue.clear()
        count
    }
    override fun close() = close("read-closed")
    fun close(reason: String) {
        if (!active.compareAndSet(true, false)) return
        val safeReason = reason.takeIf { it in CLOSE_REASONS } ?: "read-error"
        val depth = synchronized(queueLock) { val count = queue.size; queue.clear(); count }
        writer.interrupt()
        SocketCleanup.run {
            try { closed(safeReason, depth) } finally {
                try { abort() } finally { finished() }
            }
        }
    }

    companion object { private val CLOSE_REASONS = setOf("queue-full", "write-failed", "read-closed", "read-error", "read-timeout", "read-eof") }
}

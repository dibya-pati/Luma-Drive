// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import java.util.concurrent.atomic.AtomicLong

/** Paces decoded texture frames against monotonic deadlines with bounded callback jitter credit. */
internal class FrameRateLimit(
    initialMaxFps: Int = MAX_ALLOWED_FPS,
    private val clockNs: () -> Long = System::nanoTime
) {
    @Volatile private var fps = validate(initialMaxFps)
    private var nextDeadlineNs: Long? = null
    private var lastObservedNs: Long? = null
    private val forwarded = AtomicLong()
    private val drops = AtomicLong()

    val maxFps: Int get() = fps

    @Synchronized fun admit(): Boolean {
        val now = clockNs()
        val prior = lastObservedNs
        if (prior != null && now - prior < 0L) {
            // A backwards clock jump starts a fresh epoch and admits one frame immediately.
            lastObservedNs = now
            nextDeadlineNs = now + intervalNs(fps)
            forwarded.incrementAndGet()
            return true
        }
        lastObservedNs = now
        val deadline = nextDeadlineNs
        if (deadline == null) {
            nextDeadlineNs = now + intervalNs(fps)
            forwarded.incrementAndGet()
            return true
        }
        val lateByNs = now - deadline // signed subtraction is safe across nanoTime wrap
        if (lateByNs >= -EARLY_CREDIT_NS) {
            val interval = intervalNs(fps)
            // A late frame after a pause earns no catch-up burst; restart one period from now.
            nextDeadlineNs = if (lateByNs >= interval) now + interval else deadline + interval
            forwarded.incrementAndGet()
            return true
        }
        drops.incrementAndGet()
        return false
    }

    @Synchronized fun setMaxFps(value: Int) {
        fps = validate(value)
        nextDeadlineNs = null
        lastObservedNs = null
    }

    @Synchronized fun reset() { nextDeadlineNs = null; lastObservedNs = null }

    fun stats() = mapOf("framesForwarded" to forwarded.get(), "framerateDrops" to drops.get())

    private fun intervalNs(rate: Int) = 1_000_000_000L / rate

    companion object {
        const val MIN_FPS = 10
        const val MAX_ALLOWED_FPS = 30
        const val STEP_FPS = 5
        private const val EARLY_CREDIT_NS = 1_000_000L
        private val allowed = (MIN_FPS..MAX_ALLOWED_FPS step STEP_FPS).toSet()

        fun validate(value: Int): Int {
            require(value in allowed) { "FPS must be one of 10, 15, 20, 25, or 30" }
            return value
        }
    }
}

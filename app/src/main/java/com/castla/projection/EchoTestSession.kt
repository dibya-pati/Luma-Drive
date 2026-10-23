// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import java.util.concurrent.Executors

/** Serializes listener ownership without making UI callers wait for socket work. */
internal class EchoTestSession<T : AutoCloseable>(private val open: () -> T) : AutoCloseable {
    enum class State { OFF, STARTING, RUNNING, STOPPING, FAILED }
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "LumaEchoControl").apply { isDaemon = true } }
    private var desired = false
    private var closed = false
    private var queued = false
    @Volatile var state = State.OFF; private set
    @Volatile var resource: T? = null; private set
    @Volatile var error = ""; private set

    fun start() = synchronized(lock) {
        if (closed || desired) return@synchronized
        desired = true; error = ""; state = State.STARTING; schedule()
    }
    fun stop() = synchronized(lock) {
        desired = false
        if (state != State.OFF) state = State.STOPPING
        schedule()
    }
    private fun schedule() {
        if (queued || worker.isShutdown) return
        queued = true; worker.execute { reconcile() }
    }
    private fun reconcile() {
        while (true) {
            val starting = synchronized(lock) {
                if (desired == (resource != null)) {
                    state = if (desired) State.RUNNING else State.OFF
                    queued = false
                    if (closed) worker.shutdown()
                    return
                }
                desired
            }
            try {
                if (starting) {
                    val opened = open()
                    synchronized(lock) { resource = opened }
                } else {
                    val old = synchronized(lock) { resource.also { resource = null } }
                    old?.close()
                }
            } catch (failure: Exception) {
                synchronized(lock) {
                    error = failure.javaClass.simpleName
                    desired = false; state = State.FAILED; queued = false
                    if (closed) worker.shutdown()
                }
                return
            }
        }
    }
    override fun close() = synchronized(lock) {
        closed = true; stop()
    }
}

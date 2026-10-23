// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

/** Owns only this experiment's descriptors; every partial failure rolls back. */
internal class LocalRouteSession(private val factory: Factory) : AutoCloseable {
    enum class Mode { STANDARD, RETAINED }
    interface Handle : AutoCloseable { fun duplicate(): Handle }
    interface Factory { fun establish(address: String): Handle }
    companion object {
        // Shared address space, not an unrelated Internet host. Device routing is unverified.
        const val ADDRESS = "100.99.9.9"
        const val COMPAT_ADDRESS = "100.99.9.10"
    }
    private val handles = ArrayList<Handle>()
    var mode: Mode? = null; private set
    @Synchronized fun start(requested: Mode, healthy: Boolean = true) {
        if (mode == requested && healthy) return
        close()
        try {
            val address = factory.establish(ADDRESS).also { handles.add(it) }
            if (requested == Mode.RETAINED) {
                handles.add(address.duplicate())
                handles.add(factory.establish(COMPAT_ADDRESS))
            }
            mode = requested
        } catch (failure: Exception) { close(); throw failure }
    }
    @Synchronized override fun close() {
        mode = null
        val closing = handles.toList().asReversed()
        handles.clear()
        closing.forEach { runCatching { it.close() } }
    }
}

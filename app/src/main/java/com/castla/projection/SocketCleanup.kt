// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import java.util.concurrent.Executors

/** Socket shutdown must never run on Android's UI or the AA receive/ACK thread. */
internal object SocketCleanup {
    private val executor = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "LumaSocketCleanup").apply { isDaemon = true }
    }
    fun run(action: () -> Unit) { executor.execute { runCatching(action) } }
}

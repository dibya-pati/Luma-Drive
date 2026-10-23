// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import android.content.Context
import android.os.Build
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/** Public Android 16+ API, reflected only to retain Android 9+ install compatibility. */
internal object HotspotControl {
    @Volatile private var ownedRequest: Any? = null
    fun supported() = Build.VERSION.SDK_INT >= 36
    fun start(context: Context, done: (Boolean) -> Unit) {
        if (!supported()) { RecoveryJournal.record(context, "hotspot-start-unavailable", "unsupported_api"); done(false); return }
        val completed = java.util.concurrent.atomic.AtomicBoolean()
        val finish: (Boolean) -> Unit = { if (completed.compareAndSet(false, true)) done(it) }
        try {
            RecoveryJournal.record(context, "hotspot-start-attempt")
            val manager = context.getSystemService("tethering") ?: error("Tethering service unavailable")
            val requestClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
            val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(0)
            val request = builderClass.getMethod("build").invoke(builder)
            val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val callback = callback(callbackClass) { name, args ->
                when (name) {
                    "onTetheringStarted" -> {
                        ownedRequest = request
                        RecoveryJournal.record(context, "hotspot-start-success")
                        finish(true)
                    }
                    "onTetheringFailed" -> {
                        val errorCode = (args?.firstOrNull() as? Number)?.toInt()?.toString() ?: "unknown"
                        RecoveryJournal.record(context, "hotspot-start-failed", errorCode)
                        finish(false)
                    }
                }
            }
            manager.javaClass.getMethod("startTethering", requestClass, Executor::class.java, callbackClass)
                .invoke(manager, request, context.mainExecutor, callback)
        } catch (e: Exception) {
            RecoveryJournal.record(context, "hotspot-start-failed", e.javaClass.simpleName)
            finish(false)
        }
    }
    fun stopOwned(context: Context) {
        val request = ownedRequest
        if (request == null) { RecoveryJournal.record(context, "hotspot-stop-skipped", "not_owned"); return }
        ownedRequest = null
        RecoveryJournal.record(context, "hotspot-stop-requested")
        runCatching {
            val manager = context.getSystemService("tethering") ?: error("Tethering service unavailable")
            val requestClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
            val callbackClass = Class.forName("android.net.TetheringManager\$StopTetheringCallback")
            manager.javaClass.getMethod("stopTethering", requestClass, Executor::class.java, callbackClass)
                .invoke(manager, request, context.mainExecutor, callback(callbackClass) { name, args ->
                    when (name) {
                        "onStopTetheringSucceeded" -> RecoveryJournal.record(context, "hotspot-stop-success")
                        "onStopTetheringFailed" -> {
                            val errorCode = (args?.firstOrNull() as? Number)?.toInt()?.toString() ?: "unknown"
                            RecoveryJournal.record(context, "hotspot-stop-failed", errorCode)
                        }
                    }
                })
        }.onFailure {
            RecoveryJournal.record(context, "hotspot-stop-failed", it.javaClass.simpleName)
        }
    }
    private fun callback(type: Class<*>, event: (String, Array<out Any?>?) -> Unit): Any = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "LumaTetheringCallback"
            else -> { event(method.name, args); null }
        }
    }
}

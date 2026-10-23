// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/** Privacy-safe connectivity transitions and coarse interface health for local access. */
internal class NetworkDiagnostics(
    private val context: Context,
    private val interfaceFacts: () -> JSONObject
) : AutoCloseable {
    companion object {
        private const val SAMPLE_MS = 15_000L
    }

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var started = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var upstreamNetworkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var upstreamCapabilitiesMonitorReason = "not_registered"
    private var tetheringManager: Any? = null
    private var tetheringCallback: Any? = null
    @Volatile private var defaultState = NetworkState(false, "none", false, false, false)
    @Volatile private var tetherUpstreamState = NetworkState(false, "none", false, false, false)
    @Volatile private var tetherUpstreamKnown = false
    @Volatile private var tetherUpstreamNetwork: Network? = null
    @Volatile private var wifiTethered: Boolean? = null
    @Volatile private var tetheringCallbackSupported = false
    @Volatile private var tetheringMonitorReason = if (Build.VERSION.SDK_INT < 36) "api_below_36" else "not_registered"
    private var previousSample = ""
    private val sampler = object : Runnable {
        override fun run() {
            synchronized(this@NetworkDiagnostics) {
                if (!started) return
                recordSampleIfChanged()
                handler.postDelayed(this, SAMPLE_MS)
            }
        }
    }

    private data class NetworkState(
        val present: Boolean,
        val transports: String,
        val internet: Boolean,
        val validated: Boolean,
        val captive: Boolean,
        val capabilitiesAvailable: Boolean = false
    ) {
        fun signature() = "present=$present;transports=$transports;internet=$internet;validated=$validated;captive=$captive;capabilitiesAvailable=$capabilitiesAvailable"
        fun json() = JSONObject().put("present", present).put("transports", transports)
            .put("internet", internet).put("validated", validated).put("captive", captive)
            .put("capabilitiesAvailable", capabilitiesAvailable)
    }

    @Synchronized fun start() {
        if (started) return
        started = true
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateDefaultNetwork()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = updateDefaultNetwork()
            override fun onLost(network: Network) = updateDefaultNetwork()
        }
        networkCallback = callback
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onFailure { RecoveryJournal.record(context, "network-callback-unavailable", it.javaClass.simpleName) }
        val upstreamCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateTetherUpstreamIfCurrent(network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (network == tetherUpstreamNetwork) updateTetherUpstream(network, caps)
            }
            override fun onLost(network: Network) {
                if (network == tetherUpstreamNetwork) updateTetherUpstream(network, null)
            }
        }
        upstreamNetworkCallback = upstreamCallback
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().clearCapabilities()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build(),
                upstreamCallback
            )
            upstreamCapabilitiesMonitorReason = "registered"
        }.onFailure {
            upstreamCapabilitiesMonitorReason = if (it is SecurityException) "permission_denied" else "registration_failed"
            RecoveryJournal.record(context, "upstream-callback-unavailable", upstreamCapabilitiesMonitorReason)
        }
        registerTetheringCallback()
        updateDefaultNetwork()
        recordSampleIfChanged()
        handler.postDelayed(sampler, SAMPLE_MS)
    }

    private fun updateDefaultNetwork() {
        if (!started) return
        val next = runCatching {
            val network = connectivity.activeNetwork ?: return@runCatching NetworkState(false, "none", false, false, false)
            val caps = connectivity.getNetworkCapabilities(network)
                ?: return@runCatching NetworkState(true, "unknown", false, false, false)
            networkState(caps)
        }.getOrElse { NetworkState(false, "unknown", false, false, false) }
        synchronized(this) {
            val changed = next.signature() != defaultState.signature()
            defaultState = next
            if (changed) RecoveryJournal.record(context, "network-default-change", "d${compactState(next)}")
            recordSampleIfChanged()
        }
    }

    private fun registerTetheringCallback() {
        if (Build.VERSION.SDK_INT < 36) { tetheringMonitorReason = "api_below_36"; return }
        runCatching {
            val manager = context.getSystemService("tethering") ?: error("unavailable")
            val callbackType = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
            val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "LumaTetheringDiagnosticsCallback"
                    "onTetheredInterfacesChanged" -> {
                        if (started) {
                            val wifiType = runCatching { manager.javaClass.getField("TETHERING_WIFI").getInt(null) }.getOrDefault(0)
                            val wifi = (args?.firstOrNull() as? Iterable<*>)?.count { item ->
                                runCatching { item?.javaClass?.getMethod("getType")?.invoke(item) as? Int == wifiType }.getOrDefault(false)
                            } ?: 0
                            val active = wifi > 0
                            val changed = wifiTethered != active
                            wifiTethered = active
                            if (changed) RecoveryJournal.record(context, "hotspot-state-change", if (active) "wifi_on" else "wifi_off")
                            synchronized(this) { recordSampleIfChanged() }
                        }
                        null
                    }
                    "onUpstreamChanged" -> {
                        if (started) {
                            val network = args?.firstOrNull() as? Network
                            tetherUpstreamNetwork = network
                            tetherUpstreamKnown = true
                            if (network == null) updateTetherUpstream(null, null) else updateTetherUpstreamIfCurrent(network)
                        }
                        null
                    }
                    else -> null
                }
            }
            val register = manager.javaClass.getMethod("registerTetheringEventCallback", Executor::class.java, callbackType)
            register.invoke(manager, context.mainExecutor, callback)
            tetheringManager = manager
            tetheringCallback = callback
            tetheringCallbackSupported = true
            // This upstream fact is supplied by the registered callback, independent of the default network.
            tetheringMonitorReason = ""
        }.onFailure {
            tetheringCallbackSupported = false
            tetheringMonitorReason = "registration_failed"
            RecoveryJournal.record(context, "hotspot-monitor-unavailable", tetheringMonitorReason)
        }
    }

    private fun recordSampleIfChanged() {
        val interfaces = runCatching { interfaceFacts() }.getOrDefault(JSONObject())
        val signature = buildString {
            append("d").append(compactState(defaultState))
            append("u").append(if (tetherUpstreamKnown) compactState(tetherUpstreamState) else "?")
            append("w").append(wifiTethered?.let { if (it) "1" else "0" } ?: "?")
            append("n").append(interfaces.optInt("interfaceCount", -1).coerceIn(-1, 999))
            append("q").append(interfaces.optInt("upInterfaceCount", -1).coerceIn(-1, 999))
            append("v").append(interfaces.optInt("vpnInterfaceCount", -1).coerceIn(-1, 999))
            append("a").append(if (interfaces.optBoolean("addressPresent")) "1" else "0")
            append("b").append(if (interfaces.optBoolean("compatAddressPresent")) "1" else "0")
        }
        if (signature != previousSample) {
            previousSample = signature
            RecoveryJournal.record(context, "network-snapshot-change", signature)
        }
    }

    @Synchronized fun facts(): JSONObject {
        val interfaces = runCatching { interfaceFacts() }.getOrDefault(JSONObject())
        return JSONObject().put("defaultNetwork", defaultState.json())
            .put("tetherUpstreamSupported", tetheringCallbackSupported)
            .put("tetherUpstreamCallbackSupported", tetheringCallbackSupported)
            .put("tetherUpstream", if (tetherUpstreamKnown) tetherUpstreamState.json() else JSONObject.NULL)
            .put("upstreamCapabilitiesMonitorReason", upstreamCapabilitiesMonitorReason)
            .put("interfaceCount", interfaces.optInt("interfaceCount", 0))
            .put("upInterfaceCount", interfaces.optInt("upInterfaceCount", 0))
            .put("vpnInterfaceCount", interfaces.optInt("vpnInterfaceCount", 0))
            .put("addressPresent", interfaces.optBoolean("addressPresent"))
            .put("compatAddressPresent", interfaces.optBoolean("compatAddressPresent"))
            .put("wifiTetheringSupported", tetheringCallbackSupported)
            .put("wifiTetheringMonitorReason", tetheringMonitorReason)
            .put("wifiTethered", wifiTethered ?: JSONObject.NULL)
            .put("tetherClientCountSupported", false)
    }

    private fun updateTetherUpstreamIfCurrent(network: Network) {
        if (!started || network != tetherUpstreamNetwork) return
        val capabilities = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
        updateTetherUpstream(network, capabilities)
    }

    private fun updateTetherUpstream(network: Network?, capabilities: NetworkCapabilities?) {
        if (!started) return
        if (network != null && network != tetherUpstreamNetwork) return
        val next = if (network == null) NetworkState(false, "none", false, false, false)
        else capabilities?.let { networkState(it) } ?: NetworkState(true, "unknown", false, false, false)
        synchronized(this) {
            if (next.signature() != tetherUpstreamState.signature()) {
                tetherUpstreamState = next
                RecoveryJournal.record(context, "tether-upstream-change", "u${compactState(next)}")
                recordSampleIfChanged()
            }
        }
    }

    private fun networkState(caps: NetworkCapabilities): NetworkState = NetworkState(
        present = true,
        transports = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "wifi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
            NetworkCapabilities.TRANSPORT_VPN to "vpn",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth"
        ).filter { (transport, _) -> caps.hasTransport(transport) }.map { it.second }.ifEmpty { listOf("other") }.joinToString(","),
        internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        captive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
        capabilitiesAvailable = true
    )

    private fun compactState(state: NetworkState): String = buildString {
        append(if (state.present) '1' else '0')
        append(state.transports.split(',').mapNotNull {
            when (it) { "wifi" -> 'w'; "cellular" -> 'c'; "ethernet" -> 'e'; "vpn" -> 'v'; "bluetooth" -> 'b'; "other" -> 'o'; else -> null }
        }.joinToString("").ifEmpty { "n" })
        append(if (state.capabilitiesAvailable) 'k' else '?')
        append(if (state.internet) 'i' else '-')
        append(if (state.validated) 'v' else '-')
        append(if (state.captive) 'c' else '-')
    }

    fun recordVpnRepair(outcome: String, cause: String = "") {
        when (outcome) {
            "attempt" -> RecoveryJournal.record(context, "vpn-auto-repair-attempt")
            "success" -> RecoveryJournal.record(context, "vpn-auto-repair-success")
            "failure" -> RecoveryJournal.record(context, "vpn-auto-repair-failure", cause.take(80))
        }
    }

    @Synchronized override fun close() {
        if (!started) return
        started = false
        handler.removeCallbacks(sampler)
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
        upstreamNetworkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        upstreamNetworkCallback = null
        val manager = tetheringManager
        val callback = tetheringCallback
        if (manager != null && callback != null) runCatching {
            val callbackType = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
            manager.javaClass.getMethod("unregisterTetheringEventCallback", callbackType)
                .invoke(manager, callback)
        }
        tetheringManager = null; tetheringCallback = null
        wifiTethered = null; tetheringCallbackSupported = false
        tetherUpstreamNetwork = null; tetherUpstreamKnown = false
        tetherUpstreamState = NetworkState(false, "none", false, false, false)
    }
}

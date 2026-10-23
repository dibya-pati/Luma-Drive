// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.*
import android.system.OsConstants
import org.json.JSONObject
import java.net.NetworkInterface
import java.net.InetAddress

/** Local route only: no remote VPN, default route or DNS interception. */
class LocalAccessService : VpnService() {
    companion object {
        @Volatile internal var instance: LocalAccessService? = null
        @Volatile internal var status = "Off"
        internal const val PORT = 9093
        fun disconnect(context: android.content.Context) {
            // A VPN can remain system-bound after stopService(), so close its
            // descriptors explicitly instead of waiting for onDestroy().
            instance?.disconnect() ?: context.stopService(Intent(context, LocalAccessService::class.java))
        }
        fun addressPresent(): Boolean = runCatching { NetworkInterface.getNetworkInterfaces().toList()
            .any { n -> n.inetAddresses.toList().any { it.hostAddress == LocalRouteSession.ADDRESS } } }.getOrDefault(false)
        internal fun facts() = JSONObject().put("status", status).put("addressPresent", addressPresent())
            .put("requested", instance?.wanted == true).put("repairCount", instance?.repairs ?: 0)
            .put("echoRunning", instance?.echoState == EchoTestSession.State.RUNNING)
            .put("echoState", instance?.echoState?.name ?: "OFF").put("counts", instance?.counts() ?: "Test stopped")
            .put("network", instance?.networkDiagnostics?.facts() ?: JSONObject())
        private fun interfaceFacts(): JSONObject = runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val hasAddress: (String) -> Boolean = { address ->
                val target = InetAddress.getByName(address).hostAddress
                interfaces.any { networkInterface -> networkInterface.inetAddresses.toList().any { it.hostAddress == target } }
            }
            JSONObject().put("interfaceCount", interfaces.size)
                .put("upInterfaceCount", interfaces.count { runCatching { it.isUp }.getOrDefault(false) })
                .put("vpnInterfaceCount", interfaces.count { it.name.startsWith("tun") })
                .put("addressPresent", hasAddress(LocalRouteSession.ADDRESS))
                .put("compatAddressPresent", hasAddress(LocalRouteSession.COMPAT_ADDRESS))
        }.getOrDefault(JSONObject())
    }
    private val echoSession = EchoTestSession {
        val listener = LocalAccessServer(this)
        try { listener.start(15000, true); listener }
        catch (failure: Exception) { listener.close(); throw failure }
    }
    internal val echoState get() = echoSession.state
    private val handler = Handler(Looper.getMainLooper())
    @Volatile internal var wanted = false; private set
    @Volatile private var repairs = 0
    internal val networkDiagnostics by lazy { NetworkDiagnostics(this, ::interfaceFacts) }
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val session = LocalRouteSession(object : LocalRouteSession.Factory {
        override fun establish(address: String): LocalRouteSession.Handle {
            val descriptor = Builder().setSession("Luma Drive local access")
                .addAddress(address, 32).addRoute(address, 32)
                .allowFamily(OsConstants.AF_INET).allowFamily(OsConstants.AF_INET6)
                .allowBypass().setMtu(1500)
                .setConfigureIntent(PendingIntent.getActivity(this@LocalAccessService, 2,
                    Intent(this@LocalAccessService, MainActivity::class.java).setAction(MainActivity.ACTION_VPN), PendingIntent.FLAG_IMMUTABLE))
                .establish() ?: error("VPN permission unavailable")
            return Descriptor(descriptor)
        }
    })
    private class Descriptor(private val fd: ParcelFileDescriptor) : LocalRouteSession.Handle {
        override fun duplicate(): LocalRouteSession.Handle = Descriptor(ParcelFileDescriptor.dup(fd.fileDescriptor))
        override fun close() = fd.close()
    }
    private val monitor = object : Runnable {
        override fun run() {
            if (!wanted) return
            if (!addressPresent()) {
                status = "Connection lost · repairing"
                // Retry once per check, rebuilding even if the remembered mode is unchanged.
                networkDiagnostics.recordVpnRepair("attempt")
                try {
                    session.start(LocalRouteSession.Mode.RETAINED, false)
                    check(addressPresent()) { "local address missing after repair" }
                    repairs++; networkDiagnostics.recordVpnRepair("success")
                } catch (e: Exception) {
                    status = "Reconnect VPN in Luma"
                    networkDiagnostics.recordVpnRepair("failure", e.javaClass.simpleName)
                }
            } else status = "On · local route ready"
            handler.postDelayed(this, 5000)
        }
    }
    override fun onCreate() {
        super.onCreate(); instance = this
        networkDiagnostics.start()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("local-access", "Tesla access", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_VPN), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 3, Intent(this, LocalAccessService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(2, Notification.Builder(this, "local-access").setContentTitle("Luma · Tesla access")
            .setContentText("Local connection · tap for controls").setSmallIcon(R.drawable.ic_luma)
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "Disconnect", stop).build()).setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "stop-test" -> { stopEcho(); return START_NOT_STICKY }
            "stop" -> { disconnect(); return START_NOT_STICKY }
            "start", "start-test", "repair" -> Unit
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        generation.incrementAndGet(); wanted = true
        try {
            val repair = intent.action == "repair" || !addressPresent()
            if (intent.action == "repair") status = "Repairing local route…"
            if (session.mode == null && addressPresent()) error("Local address is already owned by another session")
            session.start(LocalRouteSession.Mode.RETAINED, !repair)
            check(addressPresent()) { "Android did not retain the local interface" }
            status = "On · local route ready"
            if (intent.action == "repair") {
                repairs++; RecoveryJournal.record(this, "vpn-repaired")
                android.widget.Toast.makeText(this, "Local VPN route rebuilt", android.widget.Toast.LENGTH_SHORT).show()
            }
            RecoveryJournal.record(this, "vpn-connected")
            RecoveryJournal.record(this, "vpn-route-start-success")
            if (intent.action == "start-test") {
                startEcho()
            }
            handler.removeCallbacks(monitor); handler.postDelayed(monitor, 5000)
        } catch (e: Exception) {
            status = "VPN failed · tap Connect to retry"
            RecoveryJournal.record(this, "vpn-start-failed", e.javaClass.simpleName)
            RecoveryJournal.record(this, "vpn-route-start-failure", e.javaClass.simpleName)
            wanted = false; cleanup(); stopSelf()
        }
        return START_NOT_STICKY
    }
    internal fun counts() = when (echoState) {
        EchoTestSession.State.STARTING -> "Starting connection test…"
        EchoTestSession.State.STOPPING -> "Stopping connection test…"
        EchoTestSession.State.FAILED -> "Test could not start · tap Start to retry"
        else -> echoSession.resource?.counts() ?: "Test stopped"
    }
    internal fun startEcho() { echoSession.start() }
    internal fun stopEcho() {
        val active = echoState != EchoTestSession.State.OFF
        echoSession.stop()
        if (active) RecoveryJournal.record(this, "echo-stopped")
    }
    internal fun disconnect() {
        generation.incrementAndGet(); wanted = false
        status = "Disconnecting…"
        cleanup()
        status = if (addressPresent()) "Disconnect pending · local interface still present" else "Off"
        RecoveryJournal.record(this, "vpn-disconnected-in-luma")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onRevoke() {
        // Never undo a user's disconnect with an automatic reconnect.
        val revoked = generation.incrementAndGet(); wanted = false
        handler.post {
            if (generation.get() != revoked) return@post
            status = "Disconnected in Android · tap Connect"
            RecoveryJournal.record(this, "vpn-disconnected-in-android")
            cleanup(); stopSelf()
        }
    }
    private fun cleanup() { handler.removeCallbacks(monitor); session.close(); stopEcho() }
    override fun onDestroy() {
        wanted = false; cleanup(); networkDiagnostics.close(); echoSession.close()
        if (instance === this) instance = null
        if (status.startsWith("On") || status.startsWith("Connection")) status = "Off"
        super.onDestroy()
    }

    private fun interfaceFacts(): JSONObject = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val hasAddress: (String) -> Boolean = { address ->
            val target = InetAddress.getByName(address).hostAddress
            interfaces.any { networkInterface -> networkInterface.inetAddresses.toList().any { it.hostAddress == target } }
        }
        JSONObject().put("interfaceCount", interfaces.size)
            .put("upInterfaceCount", interfaces.count { runCatching { it.isUp }.getOrDefault(false) })
            .put("vpnInterfaceCount", interfaces.count { it.name.startsWith("tun") })
            .put("addressPresent", hasAddress(LocalRouteSession.ADDRESS))
            .put("compatAddressPresent", hasAddress(LocalRouteSession.COMPAT_ADDRESS))
    }.getOrDefault(JSONObject())
}

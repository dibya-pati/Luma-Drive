// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

class ProjectionService : Service() {
    companion object { @Volatile var instance: ProjectionService? = null }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal lateinit var receiver: Receiver
    internal val pairing = PairingSession()
    internal lateinit var weather: PhoneWeather
    private lateinit var notification: Notification
    private lateinit var server: BridgeServer
    private var names: LocalNameResponder? = null
    private var ready = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val presence = ClientPresencePolicy()
    private var driveRequested = false
    private var receiverPreparationPending = false
    private var selectedResolution = 720
    @Volatile internal var activeClientCount = 0; private set
    @Volatile internal var waitingForClients = false; private set
    private val presenceTick = object : Runnable {
        override fun run() {
            if (!driveRequested) return
            applyPresence(presence.tick(android.os.SystemClock.elapsedRealtime()))
            handler.postDelayed(this, 1000)
        }
    }
    internal fun clientSeen(id: String) { handler.post {
        if (driveRequested) applyPresence(presence.seen(id, android.os.SystemClock.elapsedRealtime()))
    } }
    internal fun clientLeft(id: String) { handler.post {
        if (driveRequested) applyPresence(presence.left(id, android.os.SystemClock.elapsedRealtime()))
    } }
    internal fun clientFacts() = org.json.JSONObject().put("active", activeClientCount).put("graceSeconds", 30).put("waitingForClients", waitingForClients)
    private fun applyPresence(decision: ClientPresencePolicy.Decision) {
        val previousCount = activeClientCount
        activeClientCount = decision.activeCount
        if (previousCount != decision.activeCount) {
            RecoveryJournal.record(this, "client-count-changed", activeClients = decision.activeCount)
        }
        if (decision.shouldResume) {
            waitingForClients = false
            RecoveryJournal.record(this, "aa-idle-resume-requested", activeClients = decision.activeCount)
            startReceiverAfterMediaPreparation()
            RecoveryJournal.record(this, "aa-client-returned")
        } else if (decision.shouldDisconnect) {
            waitingForClients = true
            RecoveryJournal.record(this, "aa-idle-disconnect-requested", activeClients = decision.activeCount)
            receiver.disconnectForNoClients()
        }
    }
    /** Initialize MediaCodec/WebRTC off the main thread before AA's first SPS/PPS/IDR. */
    private fun startReceiverAfterMediaPreparation() {
        if (!ready || !driveRequested || waitingForClients || (receiver.isRunning && !receiver.isDisconnectingForNoClients) || receiverPreparationPending) return
        receiverPreparationPending = true
        scope.launch {
            val prepareFailure = runCatching { server.prepareMedia() }.exceptionOrNull()
            if (prepareFailure != null) {
                RecoveryJournal.record(this@ProjectionService, "rtc-media-prepare-failed", prepareFailure.javaClass.simpleName.take(80))
            }
            handler.post {
                receiverPreparationPending = false
                if (prepareFailure == null && ready && driveRequested && !waitingForClients &&
                    (!receiver.isRunning || receiver.isDisconnectingForNoClients)) {
                    receiver.start(selectedResolution, 16, 48000)
                }
            }
        }
    }
    private val renewWake = object : Runnable {
        override fun run() { wakeLock?.acquire(10 * 60 * 1000L); handler.postDelayed(this, 5 * 60 * 1000L) }
    }
    val token: String by lazy {
        val prefs = getSharedPreferences("pairing", MODE_PRIVATE)
        prefs.getString("token", null) ?: UUID.randomUUID().toString().replace("-", "").also { prefs.edit().putString("token", it).apply() }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("projection", "Projection receiver", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ProjectionService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        notification = Notification.Builder(this, "projection").setContentTitle("Luma Drive")
            .setContentText("Projection is running · tap to open").setSmallIcon(R.drawable.ic_luma)
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "Stop", stop).build()).setOngoing(true).build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1, notification)
        weather = PhoneWeather(this)
        server = BridgeServer(this, token)
        receiver = Receiver(server, scope, this); server.receiver = receiver
        try {
            server.start(15000, false); ready = true; instance = this
            val candidate = LocalNameResponder(this)
            try { candidate.start(); names = candidate } catch (_: Exception) { candidate.close() }
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Luma:Projection").apply { setReferenceCounted(false) }
            handler.post(renewWake)
            RecoveryJournal.record(this, "projection-service-started")
            updateWeather()
        } catch (e: Exception) { RecoveryJournal.record(this, "projection-start-failed", e.javaClass.simpleName); stopSelf() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (!ready) return START_NOT_STICKY
        selectedResolution = intent?.getIntExtra("resolution", 720)
            ?: getSharedPreferences("display", MODE_PRIVATE).getInt("resolution", 720)
        if (!driveRequested) {
            driveRequested = true; presence.start(android.os.SystemClock.elapsedRealtime())
            handler.post(presenceTick)
        }
        // An idle receiver resumes only when a browser returns. Explicit Start drive
        // still keeps the local page, VPN and AA head-unit listener available.
        if (!receiver.isRunning && !waitingForClients) startReceiverAfterMediaPreparation()
        return START_STICKY
    }
    fun urls(): List<String> = try { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
        .filter { it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }
        .map { "http://${it.hostAddress}:${BridgeServer.PORT}/#token=$token" } } catch (_: Exception) { emptyList() }
    fun browserAddresses(): List<String> = buildList {
        if (LocalNameResponder.available) add("http://${LocalNameResponder.hostname}:${BridgeServer.PORT}/")
        if (LocalAccessService.addressPresent()) add("http://${LocalRouteSession.ADDRESS}:${BridgeServer.PORT}/")
        addAll(urls().map { it.substringBefore("/#") + "/" })
    }.distinct()
    override fun onBind(intent: Intent?): IBinder? = null
    internal fun updateWeather(enabled: Boolean? = null) {
        if (enabled != null) weather.setEnabled(enabled)
        val useLocation = weather.enabled && PhoneWeather.permitted(this)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                if (useLocation) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            if (useLocation) weather.startLocation() else weather.stopLocation()
        } catch (_: RuntimeException) { weather.stopLocation(); weather.locationUnavailable() }
    }
    override fun onDestroy() {
        if (instance === this) instance = null
        ready = false; receiverPreparationPending = false; handler.removeCallbacks(renewWake)
        driveRequested = false; presence.stop(); handler.removeCallbacks(presenceTick)
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        names?.close(); names = null
        if (::weather.isInitialized) weather.close()
        if (::receiver.isInitialized) receiver.stop()
        if (::server.isInitialized) server.shutdown()
        scope.cancel(); RecoveryJournal.record(this, "projection-service-stopped"); super.onDestroy()
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.round

/** Opt-in approximate location. Only this phone contacts the forecast provider. */
internal class PhoneWeather(private val context: Context) : AutoCloseable {
    companion object {
        fun permitted(context: Context) = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        private const val FORECAST_REFRESH_MS = 30 * 60 * 1000L
        private const val LOCATION_UPDATE_MS = 30_000L
        private const val LOCATION_UPDATE_METERS = 250f
        private const val CURRENT_FIX_REFRESH_MS = 60_000L
        private const val CURRENT_FIX_TIMEOUT_MS = 30_000L
        private const val LOCATION_FRESH_NS = 2 * 60 * 1_000_000_000L
        private const val GEOCODE_RETRY_NS = 60 * 1_000_000_000L
    }
    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(LocationManager::class.java)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Luma-weather").apply { isDaemon = true } }
    private val geocoderWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "Luma-geocoder").apply { isDaemon = true } }
    private var generation = 0
    private var closed = false
    private var refreshing = false
    private var retryAt = 0L
    private var error = ""
    private var listening = false
    private val locationHandler = Handler(Looper.getMainLooper())
    private var locationSession = 0
    private var activeListener: LocationListener? = null
    private val activeProviders = mutableSetOf<String>()
    private val currentRequests = mutableMapOf<String, CancellationSignal>()
    private val currentRequestTimeouts = mutableMapOf<String, Runnable>()
    private val singleUpdateListeners = mutableMapOf<String, LocationListener>()
    private val singleUpdateTimeouts = mutableMapOf<String, Runnable>()
    private var periodicLocationRefresh: Runnable? = null
    private data class PhoneFix(val latitude: Double, val longitude: Double, val wallTime: Long, val elapsedNanos: Long)
    @Volatile private var phoneFix: PhoneFix? = null
    @Volatile private var placeName: String? = null
    private var geocodeKey: String? = null
    private var geocodeGeneration = 0
    private data class GeocodeRequest(val latitude: Double, val longitude: Double, val key: String, val ticket: Int)
    private var geocoding = false
    private var pendingGeocode: GeocodeRequest? = null
    private var lastGeocodeAttemptElapsed = 0L

    init {
        // Older builds persisted coordinates. Remove them during migration; only forecast data
        // and the explicit opt-in setting survive a service restart.
        prefs.edit().remove("latitude").remove("longitude").remove("locationAt").apply()
    }
    @Volatile var locationStatus = "Enable weather on your phone"
        private set
    val enabled get() = prefs.getBoolean("enabled", false)
    @Synchronized fun setEnabled(value: Boolean) {
        generation++; retryAt = 0; error = ""
        if (!value) { stopLocation(); prefs.edit().clear().putBoolean("enabled", false).apply(); locationStatus = "Weather is off" }
        else { phoneFix = null; placeName = null; geocodeKey = null; pendingGeocode = null; prefs.edit().putBoolean("enabled", true).apply() }
    }
    @Synchronized fun startLocation() {
        if (closed) return
        if (!enabled) { stopLocation(); locationStatus = "Weather is off"; return }
        if (!permitted(context)) { stopLocation(); locationStatus = "Allow approximate location for weather"; return }
        if (listening) return
        val providers = runCatching { manager.getProviders(true).filter { it == "fused" || it == LocationManager.NETWORK_PROVIDER } }.getOrDefault(emptyList())
        if (providers.isEmpty()) { locationStatus = "Turn on phone Location for weather"; return }
        locationStatus = "Finding phone location"
        val session = ++locationSession
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { accept(location, session) }
            override fun onProviderEnabled(provider: String) {
                synchronized(this@PhoneWeather) {
                    if (session == locationSession) { locationStatus = "Finding phone location"; requestCurrentFixes(session) }
                }
            }
            override fun onProviderDisabled(provider: String) {
                synchronized(this@PhoneWeather) {
                    if (session == locationSession && activeProviders.none { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) })
                        locationStatus = "Phone location is off"
                }
            }
            @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        activeListener = listener
        for (provider in providers) runCatching {
            manager.requestLocationUpdates(provider, LOCATION_UPDATE_MS, LOCATION_UPDATE_METERS, listener, Looper.getMainLooper())
            activeProviders += provider
        }
        listening = activeProviders.isNotEmpty()
        if (!listening) { locationSession++; activeListener = null; locationStatus = "Open Luma to refresh phone location"; return }
        // Coarse permission is sufficient; GPS/fine and background permission are never requested.
        // Request immediately and then at a bounded cadence so a stationary phone stays fresh.
        requestCurrentFixes(session)
        val refresh = object : Runnable {
            override fun run() {
                synchronized(this@PhoneWeather) {
                    if (session != locationSession || !listening || closed) return
                    requestCurrentFixes(session)
                    locationHandler.postDelayed(this, CURRENT_FIX_REFRESH_MS)
                }
            }
        }
        periodicLocationRefresh = refresh
        locationHandler.postDelayed(refresh, CURRENT_FIX_REFRESH_MS)
    }
    @Synchronized fun locationUnavailable() { locationStatus = "Open Luma to enable phone weather" }
    @Synchronized fun stopLocation() {
        locationSession++
        periodicLocationRefresh?.let(locationHandler::removeCallbacks)
        periodicLocationRefresh = null
        currentRequestTimeouts.values.forEach(locationHandler::removeCallbacks)
        currentRequestTimeouts.clear()
        singleUpdateTimeouts.values.forEach(locationHandler::removeCallbacks)
        singleUpdateTimeouts.clear()
        currentRequests.values.forEach { runCatching { it.cancel() } }
        currentRequests.clear()
        singleUpdateListeners.values.forEach { runCatching { manager.removeUpdates(it) } }
        singleUpdateListeners.clear()
        activeListener?.let { runCatching { manager.removeUpdates(it) } }
        activeListener = null
        activeProviders.clear()
        listening = false
        phoneFix = null; placeName = null; geocodeKey = null; pendingGeocode = null; geocodeGeneration++
        generation++
        if (!closed && enabled && !permitted(context)) locationStatus = "Allow approximate location for weather"
    }
    @Synchronized private fun requestCurrentFixes(session: Int) {
        if (closed || session != locationSession || !listening || !enabled || !permitted(context)) return
        for (provider in activeProviders) {
            if (provider in currentRequests || provider in singleUpdateListeners) continue
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val signal = CancellationSignal()
                currentRequests[provider] = signal
                val timeout = Runnable {
                    synchronized(this@PhoneWeather) {
                        if (session == locationSession && currentRequests[provider] === signal) {
                            currentRequests.remove(provider); currentRequestTimeouts.remove(provider); runCatching { signal.cancel() }
                        }
                    }
                }
                currentRequestTimeouts[provider] = timeout
                locationHandler.postDelayed(timeout, CURRENT_FIX_TIMEOUT_MS)
                runCatching {
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                        synchronized(this@PhoneWeather) {
                            if (currentRequests[provider] === signal) {
                                currentRequests.remove(provider)
                                currentRequestTimeouts.remove(provider)?.let(locationHandler::removeCallbacks)
                                if (session == locationSession && listening && location != null) accept(location, session)
                            }
                        }
                    }
                }.onFailure {
                    currentRequests.remove(provider)
                    currentRequestTimeouts.remove(provider)?.let(locationHandler::removeCallbacks)
                }
            } else {
                val oneShot = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        synchronized(this@PhoneWeather) {
                            if (singleUpdateListeners[provider] === this) {
                                singleUpdateListeners.remove(provider)
                                singleUpdateTimeouts.remove(provider)?.let(locationHandler::removeCallbacks)
                                runCatching { manager.removeUpdates(this) }
                                if (session == locationSession && listening) accept(location, session)
                            }
                        }
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                singleUpdateListeners[provider] = oneShot
                val timeout = Runnable {
                    synchronized(this@PhoneWeather) {
                        if (session == locationSession && singleUpdateListeners[provider] === oneShot) {
                            singleUpdateListeners.remove(provider); singleUpdateTimeouts.remove(provider); runCatching { manager.removeUpdates(oneShot) }
                        }
                    }
                }
                singleUpdateTimeouts[provider] = timeout
                locationHandler.postDelayed(timeout, CURRENT_FIX_TIMEOUT_MS)
                runCatching { manager.requestSingleUpdate(provider, oneShot, Looper.getMainLooper()) }
                    .onFailure {
                        singleUpdateListeners.remove(provider)
                        singleUpdateTimeouts.remove(provider)?.let(locationHandler::removeCallbacks)
                    }
            }
        }
    }
    @Synchronized private fun accept(location: Location, session: Int) {
        if (closed || session != locationSession || !listening || !enabled || !permitted(context) || !location.latitude.isFinite() || !location.longitude.isFinite()) return
        val nowElapsed = SystemClock.elapsedRealtimeNanos()
        val fixElapsed = location.elapsedRealtimeNanos
        if (fixElapsed <= 0 || nowElapsed - fixElapsed !in 0..LOCATION_FRESH_NS) return
        val previous = phoneFix
        if (previous != null && fixElapsed < previous.elapsedNanos) return
        val lat = round(location.latitude * 100) / 100
        val lon = round(location.longitude * 100) / 100
        val key = "$lat,$lon"
        val changed = previous == null || round(previous.latitude * 100) / 100 != lat || round(previous.longitude * 100) / 100 != lon
        phoneFix = PhoneFix(lat, lon, location.time.takeIf { it > 0 } ?: System.currentTimeMillis(), fixElapsed)
        if (changed) {
            generation++; retryAt = 0; error = ""
            prefs.edit().remove("forecast").remove("fetchedAt").apply()
            placeName = null
            geocodeKey = key
            enqueueGeocode(GeocodeRequest(lat, lon, key, ++geocodeGeneration))
        } else if (placeName == null && geocodeKey == key && nowElapsed - lastGeocodeAttemptElapsed >= GEOCODE_RETRY_NS) {
            enqueueGeocode(GeocodeRequest(lat, lon, key, geocodeGeneration))
        }
        locationStatus = "Using approximate phone location"
        refreshIfNeeded()
    }
    @Synchronized private fun enqueueGeocode(request: GeocodeRequest) {
        if (request.key == geocodeKey && placeName != null) return
        lastGeocodeAttemptElapsed = SystemClock.elapsedRealtimeNanos()
        if (!Geocoder.isPresent()) return
        if (geocoding) { pendingGeocode = request; return }
        geocoding = true
        geocoderWorker.execute {
            val name = runCatching {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.getDefault()).getFromLocation(request.latitude, request.longitude, 1)?.firstOrNull()?.let { address ->
                    val locality = address.locality ?: address.subLocality
                    val region = address.adminArea
                    when {
                        locality != null && region != null && !locality.equals(region, ignoreCase = true) -> "$locality, $region"
                        locality != null -> locality
                        address.subAdminArea != null && region != null -> "${address.subAdminArea}, $region"
                        region != null -> region
                        address.countryName != null -> address.countryName
                        else -> null
                    }
                }
            }.getOrNull()
            synchronized(this) {
                val current = freshFix()
                if (!closed && request.ticket == geocodeGeneration && geocodeKey == request.key && current != null && "${current.latitude},${current.longitude}" == request.key)
                    placeName = name
                val next = pendingGeocode
                pendingGeocode = null
                geocoding = false
                if (next != null && !closed) enqueueGeocode(next)
            }
        }
    }
    private fun freshFix(): PhoneFix? {
        val fix = phoneFix ?: return null
        return fix.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedNanos in 0..LOCATION_FRESH_NS }
    }
    @Synchronized private fun refreshIfNeeded() {
        val now = System.currentTimeMillis()
        val fix = freshFix()
        if (closed || refreshing || !enabled || !permitted(context) || fix == null || now < retryAt || now - prefs.getLong("fetchedAt", 0) < FORECAST_REFRESH_MS) return
        val lat = fix.latitude
        val lon = fix.longitude
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        val ticket = generation; refreshing = true
        worker.execute {
            val result = runCatching {
                val address = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,apparent_temperature,relative_humidity_2m,is_day,weather_code,wind_speed_10m&hourly=temperature_2m,precipitation_probability,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset&timezone=auto&forecast_days=2"
                val connection = URL(address).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 7000; connection.readTimeout = 7000; connection.instanceFollowRedirects = false
                    require(connection.responseCode == 200)
                    val bytes = connection.inputStream.use { it.readBytesLimited(128 * 1024) }
                    val raw = JSONObject(bytes.toString(Charsets.UTF_8))
                    require(raw.getJSONObject("current").has("temperature_2m") && raw.has("hourly") && raw.has("daily"))
                    // Coordinates and provider metadata are not exposed to the browser.
                    JSONObject().put("current", raw.getJSONObject("current")).put("hourly", raw.getJSONObject("hourly"))
                        .put("daily", raw.getJSONObject("daily")).put("timezone", raw.getString("timezone"))
                } finally { connection.disconnect() }
            }
            synchronized(this) {
                if (!closed) {
                    refreshing = false
                    if(ticket == generation) {
                        result.onSuccess { prefs.edit().putString("forecast", it.toString()).putLong("fetchedAt", System.currentTimeMillis()).apply(); error = "" }
                            .onFailure { error = "Weather could not update"; retryAt = System.currentTimeMillis() + 5 * 60 * 1000 }
                    } else refreshIfNeeded()
                }
            }
        }
    }
    @Synchronized fun snapshot(): JSONObject {
        refreshIfNeeded()
        val fix = if (enabled && permitted(context)) freshFix() else null
        if (phoneFix != null && fix == null) {
            phoneFix = null; placeName = null; geocodeKey = null; pendingGeocode = null; geocodeGeneration++; generation++
            if (enabled && permitted(context)) locationStatus = "Finding phone location"
        }
        val allowed = enabled && permitted(context)
        val forecast = if (allowed) runCatching { JSONObject(prefs.getString("forecast", "") ?: "") }.getOrNull() else null
        val fetched = prefs.getLong("fetchedAt", 0)
        return JSONObject().put("enabled", enabled).put("permission", permitted(context)).put("locationStatus", locationStatus)
            .put("locationName", if (fix != null) placeName ?: JSONObject.NULL else JSONObject.NULL)
            .put("locationAt", fix?.wallTime ?: 0).put("locationFresh", fix != null).put("forecast", forecast ?: JSONObject.NULL)
            .put("fetchedAt", if (forecast != null) fetched else 0).put("refreshing", refreshing)
            .put("stale", forecast != null && System.currentTimeMillis() - fetched > 45 * 60 * 1000).put("error", error)
    }
    override fun close() { synchronized(this) { closed = true; generation++ }; stopLocation(); worker.shutdownNow(); geocoderWorker.shutdownNow() }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
        while (true) { val count = read(buffer); if (count < 0) break; require(output.size() + count <= limit); output.write(buffer, 0, count) }
        return output.toByteArray()
    }
}

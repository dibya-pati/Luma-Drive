// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small local lifecycle history. Never records media, addresses, tokens or app content. */
internal object RecoveryJournal {
    @Synchronized fun record(
        context: Context,
        event: String,
        cause: String = "",
        queueDepth: Int = -1,
        viewerCount: Int = -1,
        activeClients: Int = -1,
    ) {
        runCatching {
            val prefs = context.getSharedPreferences("recovery", Context.MODE_PRIVATE)
            val previous = JSONArray(prefs.getString("events", "[]"))
            val next = JSONArray()
            // Keep the existing 30-entry journal and grow it naturally to the 96-entry bound.
            for (i in maxOf(0, previous.length() - (MAX_EVENTS - 1)) until previous.length()) next.put(previous.get(i))
            val item = JSONObject()
                .put("time", System.currentTimeMillis())
                .put("uptimeMs", android.os.SystemClock.elapsedRealtime())
                .put("version", BuildConfig.VERSION_NAME)
                .put("event", safeLabel(event))
                .put("cause", safeLabel(cause))
            if (queueDepth >= 0) item.put("queueDepth", queueDepth)
            if (viewerCount >= 0) item.put("viewerCount", viewerCount)
            if (activeClients >= 0) item.put("activeClients", activeClients)
            next.put(item)
            prefs.edit().putString("events", next.toString()).apply()
        }
    }
    fun read(context: Context): JSONArray = runCatching {
        JSONArray(context.getSharedPreferences("recovery", Context.MODE_PRIVATE).getString("events", "[]"))
    }.getOrDefault(JSONArray())
    @Synchronized fun recordPlayback(context: Context, summary: String) {
        // Caller supplies PlaybackFault's validated summary, never an error message or URL.
        runCatching {
            val prefs=context.getSharedPreferences("playback-faults", Context.MODE_PRIVATE)
            val previous=JSONArray(prefs.getString("events", "[]"));val next=JSONArray()
            for(i in maxOf(0,previous.length()-7) until previous.length())next.put(previous.get(i))
            next.put(JSONObject().put("time",System.currentTimeMillis()).put("version",BuildConfig.VERSION_NAME).put("fault",summary.take(120)))
            prefs.edit().putString("events",next.toString()).apply()
        }
    }
    fun readPlayback(context: Context): JSONArray = runCatching {
        JSONArray(context.getSharedPreferences("playback-faults",Context.MODE_PRIVATE).getString("events","[]"))
    }.getOrDefault(JSONArray())

    private fun safeLabel(value: String): String = value.filter { it.isLetterOrDigit() || it in "_.-" }.take(80)

    private const val MAX_EVENTS = 96
}

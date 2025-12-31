package com.example.earbud_usage_tracker.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.example.earbud_usage_tracker.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

class SessionBackfiller(
    private val context: Context,
    private val usageStatsManager: UsageStatsManager
) {
    
    private val mediaApps = setOf(
        "com.spotify.music",
        "com.google.android.youtube",
        "com.google.android.youtube.music",
        "com.instagram.android",
        "com.zhiliaoapp.musically", // TikTok
        "com.netflix.mediaclient",
        "com.amazon.mp3",
        "com.apple.android.music",
        "com.soundcloud.android",
        "com.gaana",
        "com.jio.media.jiobeats",
        "com.google.android.apps.youtube.music"
    )
    
    suspend fun inferMissedSessions() = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences("earbud_tracker", Context.MODE_PRIVATE)
            val lastSessionEnd = prefs.getLong("last_session_end", 0)
            val now = System.currentTimeMillis()
            
            // Only backfill if service was down for more than 5 minutes
            if (now - lastSessionEnd < 5 * 60 * 1000) {
                Log.d("EarbudDEBUG", "[Backfill] Too recent, skipping")
                return@withContext
            }
            
            val lastConnected = prefs.getBoolean("last_connected", false)
            
            if (!lastConnected) {
                Log.d("EarbudDEBUG", "[Backfill] Earbuds were not connected, skipping")
                return@withContext
            }
            
            Log.d("EarbudDEBUG", "[Backfill] Checking for missed sessions from $lastSessionEnd to $now")
            
            val usageEvents = usageStatsManager.queryEvents(lastSessionEnd, now)
            val event = UsageEvents.Event()
            val appSessions = mutableListOf<AppSession>()
            var currentApp: String? = null
            var currentStart: Long = 0
            
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        if (event.packageName in mediaApps) {
                            currentApp = event.packageName
                            currentStart = event.timeStamp
                            Log.d("EarbudDEBUG", "[Backfill] Media app foreground: $currentApp")
                        }
                    }
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        if (event.packageName == currentApp && currentApp in mediaApps) {
                            val duration = (event.timeStamp - currentStart) / 1000
                            if (duration >= 30) { // At least 30 seconds
                                appSessions.add(
                                    AppSession(
                                        packageName = currentApp,
                                        startTime = currentStart,
                                        endTime = event.timeStamp,
                                        duration = duration.toInt()
                                    )
                                )
                                Log.d("EarbudDEBUG", "[Backfill] Detected session: $currentApp duration=${duration}s")
                            }
                            currentApp = null
                        }
                    }
                }
            }
            
            // Handle app still in foreground
            if (currentApp != null) {
                val duration = (now - currentStart) / 1000
                if (duration >= 30) {
                    appSessions.add(
                        AppSession(
                            packageName = currentApp,
                            startTime = currentStart,
                            endTime = now,
                            duration = duration.toInt()
                        )
                    )
                    Log.d("EarbudDEBUG", "[Backfill] Detected ongoing session: $currentApp duration=${duration}s")
                }
            }
            
            // Get last known average volume for estimation
            val lastAvgVolume = prefs.getFloat("last_avg_volume", 50.0f).toDouble()
            
            // Send inferred sessions to Flutter
            appSessions.forEach { session ->
                val payload = mapOf(
                    "startTime" to Instant.ofEpochMilli(session.startTime)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toOffsetDateTime()
                        .toString(),
                    "endTime" to Instant.ofEpochMilli(session.endTime)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toOffsetDateTime()
                        .toString(),
                    "duration" to session.duration,
                    "avgVolume" to lastAvgVolume,
                    "maxVolume" to lastAvgVolume,
                    "isEstimated" to true
                )
                
                Log.d(
                    "EarbudDEBUG",
                    "[Backfill] Sending inferred session: ${session.packageName} ${session.duration}s"
                )
                
                withContext(Dispatchers.Main) {
                    NativeBridge.sendSessionCompleted(payload)
                }
            }
            
            Log.d("EarbudDEBUG", "[Backfill] Completed, inferred ${appSessions.size} sessions")
            
        } catch (e: Exception) {
            Log.e("EarbudDEBUG", "[Backfill] Error: ${e.message}", e)
        }
    }
    
    private data class AppSession(
        val packageName: String,
        val startTime: Long,
        val endTime: Long,
        val duration: Int
    )
}

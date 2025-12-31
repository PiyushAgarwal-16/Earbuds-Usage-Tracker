package com.example.earbud_usage_tracker.service

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class EarbudNotificationListenerService : NotificationListenerService() {
    
    private lateinit var mediaSessionManager: MediaSessionManager
    private val activeControllers = mutableMapOf<String, MediaController>()
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
        "com.jio.media.jiobeats"
    )
    
    private val sessionCallback = object : MediaSessionManager.OnActiveSessionsChangedListener {
        override fun onActiveSessionsChanged(controllers: List<MediaController>?) {
            Log.d("EarbudDEBUG", "[Media] Active sessions changed: ${controllers?.size ?: 0}")
            
            // Remove old controllers
            val currentPackages = controllers?.map { it.packageName }?.toSet() ?: emptySet()
            val toRemove = activeControllers.keys.filter { it !in currentPackages }
            toRemove.forEach { pkg ->
                activeControllers[pkg]?.unregisterCallback(controllerCallback)
                activeControllers.remove(pkg)
                Log.d("EarbudDEBUG", "[Media] Removed controller for $pkg")
            }
            
            // Add new controllers
            controllers?.forEach { controller ->
                if (controller.packageName !in activeControllers.keys) {
                    registerMediaController(controller)
                }
            }
            
            // Ensure service is running if any media sessions exist
            if (!controllers.isNullOrEmpty()) {
                EarbudTrackingService.startService(applicationContext)
            }
        }
    }
    
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val isPlaying = state?.state == PlaybackState.STATE_PLAYING
            Log.d("EarbudDEBUG", "[Media] Playback state changed: state=${state?.state} isPlaying=$isPlaying")
            
            if (isPlaying) {
                EarbudTrackingService.startService(applicationContext)
            }
        }
        
        override fun onSessionDestroyed() {
            Log.d("EarbudDEBUG", "[Media] Session destroyed")
        }
    }
    
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("EarbudDEBUG", "[Media] NotificationListener connected")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            
            val componentName = ComponentName(this, EarbudNotificationListenerService::class.java)
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionCallback, componentName)
            
            // Register existing sessions
            val controllers = mediaSessionManager.getActiveSessions(componentName)
            Log.d("EarbudDEBUG", "[Media] Found ${controllers.size} existing sessions")
            controllers.forEach { registerMediaController(it) }
        }
        
        EarbudTrackingService.startService(applicationContext)
    }
    
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d("EarbudDEBUG", "[Media] NotificationListener disconnected")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionCallback)
            activeControllers.values.forEach { it.unregisterCallback(controllerCallback) }
            activeControllers.clear()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        
        // Only care about media-related notifications
        if (sbn.packageName in mediaApps) {
            Log.d("EarbudDEBUG", "[Media] Media notification posted from ${sbn.packageName}")
            EarbudTrackingService.startService(applicationContext)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        
        if (sbn.packageName in mediaApps) {
            Log.d("EarbudDEBUG", "[Media] Media notification removed from ${sbn.packageName}")
        }
    }
    
    private fun registerMediaController(controller: MediaController) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val pkg = controller.packageName
            activeControllers[pkg] = controller
            controller.registerCallback(controllerCallback)
            
            val state = controller.playbackState
            val isPlaying = state?.state == PlaybackState.STATE_PLAYING
            
            Log.d(
                "EarbudDEBUG",
                "[Media] Registered controller for $pkg isPlaying=$isPlaying state=${state?.state}"
            )
            
            if (isPlaying) {
                EarbudTrackingService.startService(applicationContext)
            }
        }
    }
}

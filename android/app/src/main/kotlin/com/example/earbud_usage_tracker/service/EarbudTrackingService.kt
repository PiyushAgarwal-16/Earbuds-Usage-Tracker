package com.example.earbud_usage_tracker.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.earbud_usage_tracker.MainActivity
import com.example.earbud_usage_tracker.NativeBridge
import com.example.earbud_usage_tracker.service.SessionBackfiller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.time.Instant

class EarbudTrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var audioManager: AudioManager
    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var backfiller: SessionBackfiller

    private var earbudsConnected: Boolean = false
    private var isPlaybackActive: Boolean = false
    private var currentPlayingPackage: String? = null
    
    // Dynamic receiver for device connections (works even after app kill)
    private val dynamicAudioDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    Log.d("EarbudDEBUG", "[Receiver-Dynamic] Bluetooth connected: ${device?.name}")
                    updateEarbudConnectionState()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    Log.d("EarbudDEBUG", "[Receiver-Dynamic] Bluetooth disconnected: ${device?.name}")
                    updateEarbudConnectionState()
                }
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    Log.d("EarbudDEBUG", "[Receiver-Dynamic] Headset plug state=$state")
                    updateEarbudConnectionState()
                }
            }
        }
    }
    private var currentSession: SessionState? = null
    private var volumeJob: Job? = null
    private var statePollJob: Job? = null

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            updateEarbudConnectionState()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            updateEarbudConnectionState()
        }
    }

    private val audioPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            super.onPlaybackConfigChanged(configs)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d("EarbudDEBUG", "[Playback] Callback triggered, configs count=${configs.size}")
                
                configs.forEachIndexed { index, config ->
                    val usage = config.audioAttributes.usage
                    Log.d("EarbudDEBUG", "[Playback] Config[$index]: usage=$usage (MEDIA=${AudioAttributes.USAGE_MEDIA}, GAME=${AudioAttributes.USAGE_GAME}, UNKNOWN=${AudioAttributes.USAGE_UNKNOWN})")
                }
                
                val hasActiveMedia = configs.any { playbackConfig ->
                    val usage = playbackConfig.audioAttributes.usage
                    // Check if this is media/game/unknown usage
                    // Configs in this callback are already active by definition
                    usage == AudioAttributes.USAGE_MEDIA || 
                    usage == AudioAttributes.USAGE_GAME ||
                    usage == AudioAttributes.USAGE_UNKNOWN
                }

                isPlaybackActive = hasActiveMedia
                
                Log.d(
                    "EarbudDEBUG",
                    "[Playback] hasActive=$hasActiveMedia, isPlaybackActive=$isPlaybackActive"
                )

                // If media is playing without MediaSession (YouTube Shorts, TikTok), identify the app
                if (hasActiveMedia) {
                    identifyPlayingApp()
                } else {
                    currentPlayingPackage = null
                }

                evaluateSessionState()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        
        val prefs = getSharedPreferences("earbud_tracker", Context.MODE_PRIVATE)
        val restartCount = prefs.getInt("restart_count", 0) + 1
        prefs.edit().putInt("restart_count", restartCount).apply()
        
        Log.d(
            "EarbudDEBUG",
            "[Service] onCreate() restart_count=$restartCount timestamp=${System.currentTimeMillis()}"
        )
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        backfiller = SessionBackfiller(this, usageStatsManager)
        
        ensureNotificationChannel()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        audioManager.registerAudioPlaybackCallback(audioPlaybackCallback, Handler(Looper.getMainLooper()))
        
        // Register dynamic receiver for device connections
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
        }
        registerReceiver(dynamicAudioDeviceReceiver, filter)
        Log.d("EarbudDEBUG", "[Service] Dynamic receiver registered")
        
        updateEarbudConnectionState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("EarbudDEBUG", "[Service] onStartCommand() startId=$startId")
        
        // CRITICAL: Call startForeground immediately to prevent crash
        startForeground(NOTIFICATION_ID, buildNotification())
        
        // CRITICAL: Check for already-playing media when service starts/restarts
        // This is important when service restarts after being killed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val activeConfigs = audioManager.getActivePlaybackConfigurations()
            Log.d("EarbudDEBUG", "[Service] Initial playback check: ${activeConfigs.size} active configs")
            
            if (activeConfigs.isNotEmpty()) {
                // Manually trigger the callback to update playback state
                audioPlaybackCallback.onPlaybackConfigChanged(activeConfigs)
            }
        }
        
        // Also check isMusicActive
        val musicActive = audioManager.isMusicActive
        Log.d("EarbudDEBUG", "[Service] Initial music active check: $musicActive")
        
        // Start background tasks only after foreground is established
        if (statePollJob == null || !statePollJob!!.isActive) {
            // Try to backfill missed sessions
            serviceScope.launch {
                backfiller.inferMissedSessions()
            }
            statePollJob = serviceScope.launch {
                while (isActive) {
                    evaluateSessionState()
                    delay(3000) // 3 seconds
                }
            }
        }
        
        return START_STICKY
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("EarbudDEBUG", "[Service] onTaskRemoved() - scheduling restart")
        
        // CRITICAL: Save current session before service is destroyed
        if (currentSession != null) {
            Log.d("EarbudDEBUG", "[Service] onTaskRemoved() - saving active session")
            endSession()
        }
        
        // Schedule immediate restart using AlarmManager
        val restartIntent = Intent(applicationContext, EarbudTrackingService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            1,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 1000, // Restart after 1 second
            pendingIntent
        )
    }

    override fun onDestroy() {
        Log.d("EarbudDEBUG", "[Service] onDestroy() timestamp=${System.currentTimeMillis()}")
        
        // CRITICAL: Save current session before service is destroyed
        if (currentSession != null) {
            Log.d("EarbudDEBUG", "[Service] onDestroy() - saving active session")
            endSession()
        }
        
        // Unregister dynamic receiver
        try {
            unregisterReceiver(dynamicAudioDeviceReceiver)
            Log.d("EarbudDEBUG", "[Service] Dynamic receiver unregistered")
        } catch (e: Exception) {
            Log.w("EarbudDEBUG", "[Service] Error unregistering receiver: ${e.message}")
        }
        
        statePollJob?.cancel()
        statePollJob = null

        volumeJob?.cancel()
        audioManager.unregisterAudioPlaybackCallback(audioPlaybackCallback)
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        serviceScope.cancel()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun identifyPlayingApp() {
        try {
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 60_000 // Last 1 minute
            
            val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()
            var lastForegroundApp: String? = null
            
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastForegroundApp = event.packageName
                }
            }
            
            currentPlayingPackage = lastForegroundApp
            Log.d("EarbudDEBUG", "Identified playing app: $lastForegroundApp")
        } catch (e: Exception) {
            Log.w("EarbudDEBUG", "Failed to identify app: ${e.message}")
        }
    }

    private fun updateEarbudConnectionState() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        earbudsConnected = devices.any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_BLE_BROADCAST,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
                else -> false
            }
        }
        evaluateSessionState()
    }

    private fun evaluateSessionState() {
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val musicActive = audioManager.isMusicActive

        Log.d(
            "EarbudDEBUG",
            "evaluateSessionState() | earbuds=$earbudsConnected volume=$currentVolume playback=$isPlaybackActive musicActive=$musicActive session=${currentSession != null}"
        )

        val shouldTrack = earbudsConnected && currentVolume > 0 && (isPlaybackActive || musicActive)

        if (shouldTrack && currentSession == null) {
            Log.d("EarbudDEBUG", "[Session] START earbuds=$earbudsConnected playback=$isPlaybackActive musicActive=$musicActive")
            startSession()
        } else if (!shouldTrack && currentSession != null) {
            Log.d("EarbudDEBUG", "[Session] END earbuds=$earbudsConnected playback=$isPlaybackActive musicActive=$musicActive")
            endSession()
        }

        if (shouldTrack) {
            ensureVolumeSampling()
        } else {
            volumeJob?.cancel()
            volumeJob = null
        }
    }

    private fun startSession() {
        Log.d("EarbudDEBUG", "startSession() called")
        val now = System.currentTimeMillis()
        currentSession = SessionState(startEpochMillis = now)
        collectVolumeSample()
    }

    private fun endSession() {
        Log.d("EarbudDEBUG", "endSession() called")
        val state = currentSession ?: return

        val endMillis = System.currentTimeMillis()
        val durationSeconds = ((endMillis - state.startEpochMillis) / 1000L).coerceAtLeast(1L)

        val payload = mapOf(
            "startTime" to Instant.ofEpochMilli(state.startEpochMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .toOffsetDateTime()
                .toString(),
            "endTime" to Instant.ofEpochMilli(endMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .toOffsetDateTime()
                .toString(),
            "duration" to durationSeconds.toInt(),
            "avgVolume" to state.averageVolume(),
            "maxVolume" to state.maxVolume
        )
        
        // Save for backfilling
        val prefs = getSharedPreferences("earbud_tracker", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("last_session_end", endMillis)
            .putFloat("last_avg_volume", state.averageVolume().toFloat())
            .apply()

        currentSession = null
        
        // CRITICAL: Use runBlocking to ensure session is sent before service is destroyed
        // This prevents the coroutine from being cancelled when serviceScope is cancelled in onDestroy()
        try {
            kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                NativeBridge.sendSessionCompleted(payload)
                Log.d("EarbudDEBUG", "[Session] Sent to Flutter: duration=${durationSeconds}s")
            }
        } catch (e: Exception) {
            Log.e("EarbudDEBUG", "[Session] Error sending to Flutter: ${e.message}")
        }
    }

    private fun ensureVolumeSampling() {
        if (volumeJob?.isActive == true) {
            return
        }
        volumeJob = serviceScope.launch {
            while (isActive && currentSession != null) {
                collectVolumeSample()
                delay(VOLUME_SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun collectVolumeSample() {
        val state = currentSession ?: return
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percentage = (currentVolume.toDouble() / maxVolume.toDouble()) * 100.0
        state.recordSample(percentage)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Earbud Usage Tracker")
            .setContentText("Tracking audio sessions")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Make it persistent
            .setAutoCancel(false) // Prevent dismissal
            .setPriority(NotificationCompat.PRIORITY_LOW) // Don't annoy user
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private data class SessionState(
        val startEpochMillis: Long,
        private val volumeSamples: MutableList<Double> = mutableListOf(),
        var maxVolume: Double = 0.0
    ) {
        fun recordSample(value: Double) {
            volumeSamples.add(value)
            if (value > maxVolume) {
                maxVolume = value
            }
        }

        fun averageVolume(): Double {
            if (volumeSamples.isEmpty()) {
                return 0.0
            }
            return volumeSamples.sum() / volumeSamples.size
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 4701
        private const val NOTIFICATION_CHANNEL_ID = "earbud_usage_tracking"
        private const val NOTIFICATION_CHANNEL_NAME = "Earbud Usage Tracking"
        private const val VOLUME_SAMPLE_INTERVAL_MS = 5_000L

        @Volatile
        private var running: Boolean = false

        fun startService(context: Context) {
            val intent = Intent(context, EarbudTrackingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, EarbudTrackingService::class.java)
            context.stopService(intent)
        }

        fun isRunning(): Boolean = running
    }
}

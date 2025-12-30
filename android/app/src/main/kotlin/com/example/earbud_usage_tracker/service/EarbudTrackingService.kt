package com.example.earbud_usage_tracker.service

import android.util.Log
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.earbud_usage_tracker.MainActivity
import com.example.earbud_usage_tracker.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

class EarbudTrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var audioManager: AudioManager
    private lateinit var mediaSessionManager: MediaSessionManager

    private var earbudsConnected: Boolean = false
    private var isPlaybackActive: Boolean = false
    private var currentSession: SessionState? = null
    private var volumeJob: Job? = null
    private var statePollJob: Job? = null

    private val controllerCallbacks = ConcurrentHashMap<MediaController, MediaController.Callback>()
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            updateEarbudConnectionState()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            updateEarbudConnectionState()
        }
    }

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        handleControllers(controllers ?: emptyList())
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        registerSessionListener()
        updateEarbudConnectionState()
        refreshPlaybackState()
        statePollJob = serviceScope.launch {
            while (isActive) {
                evaluateSessionState()
                delay(3000) // 3 seconds
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        statePollJob?.cancel()
        statePollJob = null

        volumeJob?.cancel()
        controllerCallbacks.forEach { (controller, callback) ->
            controller.unregisterCallback(callback)
        }
        controllerCallbacks.clear()
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (ignored: SecurityException) {
        }
        serviceScope.cancel()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerSessionListener() {
        val notificationComponent = ComponentName(this, EarbudNotificationListenerService::class.java)
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                sessionListener,
                notificationComponent,
                Handler(Looper.getMainLooper())
            )
            val controllers = mediaSessionManager.getActiveSessions(notificationComponent)
            handleControllers(controllers ?: emptyList())
        } catch (ignored: SecurityException) {
        }
    }

    private fun handleControllers(controllers: List<MediaController>) {
        val existingControllers = controllerCallbacks.keys
        existingControllers.filter { controller -> controllers.none { it == controller } }
            .forEach { controller ->
                controllerCallbacks.remove(controller)?.let { callback ->
                    controller.unregisterCallback(callback)
                }
            }

        controllers.forEach { controller ->
            if (!controllerCallbacks.containsKey(controller)) {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        refreshPlaybackState()
                    }
                }
                controller.registerCallback(callback)
                controllerCallbacks[controller] = callback
            }
        }
        refreshPlaybackState()
    }

    private fun refreshPlaybackState() {
        val notificationComponent = ComponentName(this, EarbudNotificationListenerService::class.java)
        val controllers = try {
            mediaSessionManager.getActiveSessions(notificationComponent)
        } catch (ignored: SecurityException) {
            emptyList<MediaController>()
        }
        val playingController = controllers.firstOrNull { controller ->
            controller.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        isPlaybackActive = playingController != null
        evaluateSessionState()
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
    val currentVolume =
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val musicActive = audioManager.isMusicActive

    Log.d(
        "EarbudDEBUG",
        "evaluateSessionState() | earbuds=$earbudsConnected volume=$currentVolume playback=$isPlaybackActive musicActive=$musicActive session=${currentSession != null}"
    )

    val shouldTrack =
        earbudsConnected && 
        currentVolume > 0 && 
        (isPlaybackActive || musicActive)

    if (shouldTrack && currentSession == null) {
        Log.d("EarbudDEBUG", ">>> START SESSION")
        startSession()
    } else if (!shouldTrack && currentSession != null) {
        Log.d("EarbudDEBUG", ">>> END SESSION")
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
    val durationSeconds =
        ((endMillis - state.startEpochMillis) / 1000L).coerceAtLeast(1L)

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

    currentSession = null
    
    serviceScope.launch(Dispatchers.Main) {
        NativeBridge.sendSessionCompleted(payload)
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
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Earbud tracking active")
            .setContentText("Monitoring audio playback")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
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

package com.example.earbud_usage_tracker

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.example.earbud_usage_tracker.service.EarbudTrackingService
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

object NativeBridge : MethodChannel.MethodCallHandler {
    private const val CHANNEL_NAME = "earbud_usage_tracker/native"

    private var channel: MethodChannel? = null
    private var appContext: Context? = null

    fun register(messenger: BinaryMessenger, context: Context) {
        appContext = context.applicationContext
        channel = MethodChannel(messenger, CHANNEL_NAME).also { methodChannel ->
            methodChannel.setMethodCallHandler(this)
        }
    }

    fun unregister() {
        channel?.setMethodCallHandler(null)
        channel = null
        appContext = null
    }

    fun sendSessionCompleted(payload: Map<String, Any>) {
        channel?.invokeMethod("sessionCompleted", payload)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val context = appContext
        when (call.method) {
            "startService" -> {
                if (context != null) {
                    EarbudTrackingService.startService(context)
                }
                result.success(null)
            }
            "stopService" -> {
                if (context != null) {
                    EarbudTrackingService.stopService(context)
                }
                result.success(null)
            }
            "requestStatus" -> {
                result.success(EarbudTrackingService.isRunning())
            }
            "isNotificationAccessGranted" -> {
                result.success(isNotificationAccessGranted(context))
            }
            "isBatteryOptimizationIgnored" -> {
                result.success(isBatteryOptimizationIgnored(context))
            }
            "openNotificationAccessSettings" -> {
                openNotificationAccessSettings(context)
                result.success(null)
            }
            "openBatteryOptimizationSettings" -> {
                openBatteryOptimizationSettings(context)
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    private fun isNotificationAccessGranted(context: Context?): Boolean {
        context ?: return false
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabledPackages.contains(context.packageName)
    }

    private fun isBatteryOptimizationIgnored(context: Context?): Boolean {
        context ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun openNotificationAccessSettings(context: Context?) {
        context ?: return
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    private fun openBatteryOptimizationSettings(context: Context?) {
        context ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val optimizationIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val started = runCatching { context.startActivity(optimizationIntent) }.isSuccess
            if (!started) {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                runCatching { context.startActivity(fallback) }
            }
        } else {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            runCatching { context.startActivity(fallback) }
        }
    }
}

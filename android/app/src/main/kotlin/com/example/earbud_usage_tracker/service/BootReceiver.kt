package com.example.earbud_usage_tracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d("EarbudDEBUG", "[Receiver] BOOT_COMPLETED received")
                EarbudTrackingService.startService(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d("EarbudDEBUG", "[Receiver] PACKAGE_REPLACED received")
                EarbudTrackingService.startService(context)
            }
        }
    }
}

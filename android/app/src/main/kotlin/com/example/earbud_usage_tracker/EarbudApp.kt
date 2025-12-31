package com.example.earbud_usage_tracker

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.earbud_usage_tracker.service.EarbudTrackingService
import com.example.earbud_usage_tracker.service.ServiceWatchdogWorker
import java.util.concurrent.TimeUnit

class EarbudApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Start service automatically when app process starts
        if (!EarbudTrackingService.isRunning()) {
            EarbudTrackingService.startService(this)
        }
        
        // Schedule watchdog to ensure service persistence
        scheduleServiceWatchdog()
    }
    
    private fun scheduleServiceWatchdog() {
        val watchdogRequest = androidx.work.OneTimeWorkRequestBuilder<ServiceWatchdogWorker>()
            .setInitialDelay(5, java.util.concurrent.TimeUnit.MINUTES)
            .build()
        
        WorkManager.getInstance(this).enqueueUniqueWork(
            "ServiceWatchdog",
            androidx.work.ExistingWorkPolicy.REPLACE,
            watchdogRequest
        )
        
        android.util.Log.d("EarbudDEBUG", "[App] Watchdog scheduled with 5-minute interval")
    }
}

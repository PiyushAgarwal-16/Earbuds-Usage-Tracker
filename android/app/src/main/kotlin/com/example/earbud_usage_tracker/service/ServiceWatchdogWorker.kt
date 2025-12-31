package com.example.earbud_usage_tracker.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class ServiceWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("earbud_tracker", Context.MODE_PRIVATE)
        val executionCount = prefs.getInt("watchdog_execution_count", 0) + 1
        prefs.edit().putInt("watchdog_execution_count", executionCount).apply()
        
        Log.d("EarbudDEBUG", "[Watchdog] Checking service status execution_count=$executionCount")
        
        if (!EarbudTrackingService.isRunning()) {
            val consecutiveRestarts = prefs.getInt("consecutive_restarts", 0)
            
            // Exponential backoff: don't spam restarts if service keeps dying
            if (consecutiveRestarts >= 5) {
                Log.w("EarbudDEBUG", "[Watchdog] Too many consecutive restarts ($consecutiveRestarts), backing off")
                prefs.edit().putInt("consecutive_restarts", 0).apply()
                // Still schedule next run
                scheduleNextRun()
                return Result.retry()
            }
            
            Log.w("EarbudDEBUG", "[Watchdog] Service not running, restarting... (attempt ${consecutiveRestarts + 1})")
            prefs.edit()
                .putInt("consecutive_restarts", consecutiveRestarts + 1)
                .putLong("last_watchdog_restart", System.currentTimeMillis())
                .apply()
                
            EarbudTrackingService.startService(applicationContext)
        } else {
            Log.d("EarbudDEBUG", "[Watchdog] Service is running")
            // Reset consecutive restarts counter on success
            prefs.edit().putInt("consecutive_restarts", 0).apply()
        }
        
        // Schedule next watchdog run in 5 minutes
        scheduleNextRun()
        
        return Result.success()
    }
    
    private fun scheduleNextRun() {
        val nextRun = OneTimeWorkRequestBuilder<ServiceWatchdogWorker>()
            .setInitialDelay(5, TimeUnit.MINUTES)
            .build()
        
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "ServiceWatchdog",
            ExistingWorkPolicy.REPLACE,
            nextRun
        )
        
        Log.d("EarbudDEBUG", "[Watchdog] Next run scheduled in 5 minutes")
    }
}

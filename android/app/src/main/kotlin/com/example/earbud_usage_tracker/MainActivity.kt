package com.example.earbud_usage_tracker

import android.os.Bundle
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import com.example.earbud_usage_tracker.service.EarbudTrackingService

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: io.flutter.embedding.engine.FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        NativeBridge.register(flutterEngine.dartExecutor.binaryMessenger, this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Always ensure service is running when app opens
        Log.d("EarbudDEBUG", "[MainActivity] onCreate() - ensuring service is running")
        EarbudTrackingService.startService(this)
    }
    
    override fun onResume() {
        super.onResume()
        
        // Double-check service is running when app comes to foreground
        if (!EarbudTrackingService.isRunning()) {
            Log.w("EarbudDEBUG", "[MainActivity] onResume() - service not running, starting...")
            EarbudTrackingService.startService(this)
        }
    }

    override fun cleanUpFlutterEngine(flutterEngine: io.flutter.embedding.engine.FlutterEngine) {
        NativeBridge.unregister()
        super.cleanUpFlutterEngine(flutterEngine)
    }
}

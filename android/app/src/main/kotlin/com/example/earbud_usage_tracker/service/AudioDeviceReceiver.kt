package com.example.earbud_usage_tracker.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AudioDeviceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val deviceName = device?.name ?: "Unknown"
                
                Log.d("EarbudDEBUG", "[Receiver] Bluetooth connected: $deviceName")
                
                // Save connection state for backfilling
                saveConnectionState(context, true)
                ensureServiceRunning(context)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val deviceName = device?.name ?: "Unknown"
                
                Log.d("EarbudDEBUG", "[Receiver] Bluetooth disconnected: $deviceName")
                saveConnectionState(context, false)
            }
            Intent.ACTION_HEADSET_PLUG -> {
                val state = intent.getIntExtra("state", -1)
                val name = intent.getStringExtra("name") ?: "Unknown"
                
                when (state) {
                    1 -> {
                        Log.d("EarbudDEBUG", "[Receiver] Wired headset connected: $name")
                        saveConnectionState(context, true)
                        ensureServiceRunning(context)
                    }
                    0 -> {
                        Log.d("EarbudDEBUG", "[Receiver] Wired headset disconnected: $name")
                        saveConnectionState(context, false)
                    }
                }
            }
        }
    }

    private fun ensureServiceRunning(context: Context) {
        if (!EarbudTrackingService.isRunning()) {
            Log.d("EarbudDEBUG", "[Receiver] Starting service")
            EarbudTrackingService.startService(context)
        }
    }
    
    private fun saveConnectionState(context: Context, connected: Boolean) {
        val prefs = context.getSharedPreferences("earbud_tracker", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("last_connected", connected)
            .putLong("last_connection_change", System.currentTimeMillis())
            .apply()
    }
}

package com.example.earbud_usage_tracker.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

data class OemInstructions(
    val manufacturer: String,
    val steps: List<String>,
    val deepLinkIntent: Intent?
)

object OemHelper {
    
    fun getOemInstructions(context: Context): OemInstructions {
        val manufacturer = Build.MANUFACTURER.lowercase()
        
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
                OemInstructions(
                    manufacturer = "Xiaomi/Redmi",
                    steps = listOf(
                        "Open Security app",
                        "Go to Battery & Performance",
                        "Tap 'Choose apps'",
                        "Find 'Earbud Usage Tracker'",
                        "Enable 'No restrictions'",
                        "Go back and tap 'Autostart'",
                        "Enable autostart for this app"
                    ),
                    deepLinkIntent = try {
                        Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                            putExtra("extra_pkgname", context.packageName)
                        }
                    } catch (e: Exception) {
                        null
                    }
                )
            }
            manufacturer.contains("samsung") -> {
                OemInstructions(
                    manufacturer = "Samsung",
                    steps = listOf(
                        "Open Settings",
                        "Go to Apps",
                        "Find 'Earbud Usage Tracker'",
                        "Tap Battery",
                        "Enable 'Allow background activity'",
                        "Disable 'Put app to sleep'",
                        "Go back to app info",
                        "Tap 'Mobile data'",
                        "Enable 'Allow background data usage'"
                    ),
                    deepLinkIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                    }
                )
            }
            manufacturer.contains("oneplus") -> {
                OemInstructions(
                    manufacturer = "OnePlus",
                    steps = listOf(
                        "Open Settings",
                        "Go to Battery > Battery optimization",
                        "Tap 'All apps'",
                        "Find 'Earbud Usage Tracker'",
                        "Select 'Don't optimize'",
                        "Go back to Settings",
                        "Go to Apps > Special access",
                        "Tap 'Battery optimization'",
                        "Ensure this app is not optimized"
                    ),
                    deepLinkIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            }
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> {
                OemInstructions(
                    manufacturer = "Oppo/Realme",
                    steps = listOf(
                        "Open Settings",
                        "Go to Battery > App Battery Management",
                        "Find 'Earbud Usage Tracker'",
                        "Disable battery optimization",
                        "Go to Settings > Privacy",
                        "Tap 'App Auto-Launch'",
                        "Enable auto-launch for this app"
                    ),
                    deepLinkIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                    }
                )
            }
            manufacturer.contains("vivo") -> {
                OemInstructions(
                    manufacturer = "Vivo",
                    steps = listOf(
                        "Open i Manager app",
                        "Go to App Manager",
                        "Tap 'Autostart Manager'",
                        "Enable autostart for 'Earbud Usage Tracker'",
                        "Go to Settings > Battery",
                        "Tap 'Background power consumption'",
                        "Find this app and allow background activity"
                    ),
                    deepLinkIntent = null
                )
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                OemInstructions(
                    manufacturer = "Huawei/Honor",
                    steps = listOf(
                        "Open Settings",
                        "Go to Battery > App launch",
                        "Find 'Earbud Usage Tracker'",
                        "Disable 'Manage automatically'",
                        "Enable all three options: Auto-launch, Secondary launch, Run in background",
                        "Go to Settings > Apps",
                        "Find this app",
                        "Ensure battery optimization is disabled"
                    ),
                    deepLinkIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                    }
                )
            }
            else -> {
                OemInstructions(
                    manufacturer = "Generic Android",
                    steps = listOf(
                        "Open Settings",
                        "Go to Apps",
                        "Find 'Earbud Usage Tracker'",
                        "Tap Battery",
                        "Select 'Unrestricted' or 'Don't optimize'",
                        "Ensure background activity is allowed"
                    ),
                    deepLinkIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            }
        }
    }
    
    fun getManufacturerName(): String {
        return Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    }
}

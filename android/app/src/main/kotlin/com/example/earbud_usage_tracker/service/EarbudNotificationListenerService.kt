package com.example.earbud_usage_tracker.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class EarbudNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        EarbudTrackingService.startService(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        EarbudTrackingService.startService(applicationContext)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        EarbudTrackingService.startService(applicationContext)
    }
}

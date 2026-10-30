package com.ember.assistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/** Opt-in notification access. */
class EmberNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!getSharedPreferences("ember_privacy", MODE_PRIVATE).getBoolean("notification_access_enabled", false)) return
        Log.d("EMBER_NOTIFY", "Notification received from " + sbn.packageName)
    }
}

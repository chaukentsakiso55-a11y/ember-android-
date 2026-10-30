package com.ember.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class EmberKeepAliveService : Service() {
    companion object {
        const val ACTION_START = "com.ember.assistant.action.START"
        const val ACTION_STOP = "com.ember.assistant.action.STOP"
        private const val CHANNEL_ID = "ember_assistant_status"
        private const val NOTIFICATION_ID = 7301
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "EMBER assistant status", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows when the user-enabled EMBER background session is running." })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("ember_runtime", MODE_PRIVATE).edit().putBoolean("service_active", false).apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EmberKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ember_foreground)
            .setContentTitle("EMBER is active")
            .setContentText("Your assistant session is running. Tap to open controls.")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "SHUT DOWN", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        getSharedPreferences("ember_runtime", MODE_PRIVATE).edit().putBoolean("service_active", true).apply()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

package com.penumbraos.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

class ServerService : Service() {

    companion object {
        private const val TAG = "PenumbraServer"
        private const val CHANNEL_ID = "penumbra_server"
        private const val CHANNEL_NAME = "Penumbra Server"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ServerService::class.java)
            context.startForegroundService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting on-device server"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val configPath = BootstrapConfig.ensureCanonicalConfig(applicationContext)
            ServerRuntime.start(applicationContext, configPath)
            updateNotification("On-device server running")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start server runtime", t)
            updateNotification("On-device server failed to start")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            ServerRuntime.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to stop runtime cleanly", t)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Foreground service for Penumbra server"
            setShowBadge(false)
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Penumbra Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }
}

package com.somedeveloper.smsforwarder

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

@SuppressLint("MissingForegroundServiceType")
class ForwardingService : Service() {
    companion object {
        const val CHANNEL_ID = "sms_forwarder_channel"
        const val NOTIF_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Ensure notification is shown as soon as service is created
        val notification = buildNotification()
        try {
            startForeground(NOTIF_ID, notification)
        } catch (_: Exception) {
            // best-effort
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "SMS Forwarder", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Keeps SMS forwarding service running"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val baseFlag = PendingIntent.FLAG_UPDATE_CURRENT
        val immFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val flags = baseFlag or immFlag
        val pending = PendingIntent.getActivity(this, 0, intent, flags)
        val smallIcon = try {
            R.mipmap.ic_launcher
        } catch (_: Exception) {
            android.R.drawable.sym_def_app_icon
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Forwarder")
            .setContentText("SMS forwarding is running in the background")
            .setSmallIcon(smallIcon)
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ensure channel exists and notification is displayed whenever the service starts
        createChannel()
        val notification = buildNotification()
        try {
            startForeground(NOTIF_ID, notification)
        } catch (_: Exception) {
            // best-effort
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

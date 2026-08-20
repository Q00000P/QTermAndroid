package org.qterm.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Foreground-сервис держит процесс живым, пока есть открытые SSH-сессии:
 * без него Android замораживает приложение в doze через считанные минуты
 * и сокеты умирают. Стартует при первой сессии, гаснет с последней.
 */
class TermService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "SSH-сессии", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                },
            )
        }
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("QTerm")
            .setContentText("Активных сессий: $count")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, notif)
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "sessions"
        private const val EXTRA_COUNT = "count"

        fun update(context: Context, sessions: Int) {
            if (sessions > 0) {
                val i = Intent(context, TermService::class.java).putExtra(EXTRA_COUNT, sessions)
                context.startForegroundService(i)
            } else {
                context.stopService(Intent(context, TermService::class.java))
            }
        }
    }
}

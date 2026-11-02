package com.molina.studio.terminal

import com.molina.studio.ui.MolinaTheme
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import com.molina.studio.AppExit
import com.molina.studio.MainActivity
import com.molina.studio.R

/**
 * Foreground service yang menjaga proses aplikasi (dan sesi terminal di dalamnya)
 * tetap hidup saat aplikasi di latar belakang. Sesi sendiri masih dimiliki
 * [TerminalSessions]; service ini pemilik siklus hidupnya.
 */
class TerminalService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Sesi Terminal", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Menjaga sesi terminal Molina tetap berjalan"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Molina:terminal").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Wajib dipanggil dulu pada setiap startForegroundService().
        startForeground(NOTIF_ID, buildNotification())
        when (intent?.action) {
            ACTION_EXIT -> {
                AppExit.exit()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val exit = PendingIntent.getService(
            this, 1,
            Intent(this, TerminalService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_stat_molina)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_molina)
            .setContentTitle("Molina Terminal")
            .setContentText("Sesi terminal berjalan")
            .setColor(MolinaTheme.GOLD_ARGB)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(icon, "Buka", open).build())
            .addAction(Notification.Action.Builder(icon, "Keluar", exit).build())
            .build()
    }

    companion object {
        const val ACTION_EXIT = "com.molina.studio.terminal.EXIT"
        const val ACTION_STOP = "com.molina.studio.terminal.STOP"
        private const val CHANNEL_ID = "molina_terminal"
        private const val NOTIF_ID = 1001

        /** Idempoten: aman dipanggil berulang kali. */
        fun ensureRunning(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, TerminalService::class.java))
        }

        /** Berhenti lewat service sendiri agar startForeground() selalu terpanggil lebih dulu. */
        fun requestStop(ctx: Context) {
            ctx.startForegroundService(
                Intent(ctx, TerminalService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

package com.molina.studio.settings

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
import com.molina.studio.MainActivity
import com.molina.studio.R
import com.molina.studio.ui.MolinaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * [TOOLRUN-FGS] Foreground service (tipe dataSync) yang menjaga proses aplikasi tetap hidup selama proses Paket
 * (pasang/hapus/apt/yt-dlp) berjalan, termasuk saat aplikasi di latar belakang. Sumber kebenaran tetap
 * [ToolRunHub.session]; service hanya mengamati dan menampilkan progres di notifikasi. Berhenti sendiri saat
 * proses selesai dan meninggalkan satu notifikasi ringkasan.
 */
class ToolRunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Proses Paket", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progres pemasangan dan pembaruan paket Molina"
                setShowBadge(false)
            }
        )
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Molina:toolrun").apply {
            setReferenceCounted(false)
            // Batas atas lebih besar dari batas waktu skrip terpanjang (50 menit): tidak menahan CPU tanpa akhir bila service bocor.
            acquire(WAKE_MAX_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Wajib dipanggil dulu pada setiap startForegroundService().
        lastStartId = startId
        startForeground(NOTIF_ID, buildOngoing(ToolRunHub.session.value))
        if (intent?.action == ACTION_CANCEL) ToolRunHub.cancel()
        if (watcher == null) {
            watcher = scope.launch {
                ToolRunHub.session.collect { s ->
                    if (s != null && s.running) nm().notify(NOTIF_ID, buildOngoing(s)) else finishWith(s)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun finishWith(s: ToolRunSnapshot?) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (s != null) {
            val text = s.status.lineSequence().firstOrNull().orEmpty().ifBlank { "Selesai" }
            nm().notify(
                NOTIF_DONE_ID,
                Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_molina)
                    .setContentTitle(s.title)
                    .setContentText(text)
                    .setColor(MolinaTheme.BRAND_ARGB)
                    .setAutoCancel(true)
                    .setTimeoutAfter(DONE_TIMEOUT_MS)
                    .setContentIntent(openApp())
                    .build()
            )
        }
        // Hentikan pengamat ini; stopSelf(startId) tidak berefek bila proses baru sudah memulai service lagi.
        watcher?.cancel()
        watcher = null
        stopSelf(lastStartId)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 12,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("molina_open_tab", "tool_run"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildOngoing(s: ToolRunSnapshot?): Notification {
        val pct: Int? = s?.progress?.let { (it * 100).toInt().coerceIn(0, 100) }
        val text = when {
            s == null -> "Menyiapkan\u2026"
            pct != null -> "$pct% \u00B7 ${s.phase}"
            else -> s.phase
        }
        val cancel = PendingIntent.getService(
            this, 13,
            Intent(this, ToolRunService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_stat_molina)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_molina)
            .setContentTitle(s?.title ?: "Molina Paket")
            .setContentText(text)
            .setColor(MolinaTheme.BRAND_ARGB)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct ?: 0, pct == null)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(icon, "Berhenti", cancel).build())
            .build()
    }

    companion object {
        const val ACTION_CANCEL = "com.molina.studio.settings.TOOLRUN_CANCEL"
        private const val CHANNEL_ID = "molina_toolrun"
        private const val NOTIF_ID = 1004
        private const val NOTIF_DONE_ID = 1005
        private const val WAKE_MAX_MS = 60 * 60_000L
        private const val DONE_TIMEOUT_MS = 5 * 60_000L

        /** Idempoten: aman dipanggil berulang. Dipanggil [ToolRunHub] setelah proses dimulai. */
        fun ensureRunning(ctx: Context) {
            // Android 12+ menolak start dari latar; proses tetap jalan di proses aplikasi, hanya tanpa notifikasi.
            runCatching { ctx.startForegroundService(Intent(ctx, ToolRunService::class.java)) }
        }
    }
}

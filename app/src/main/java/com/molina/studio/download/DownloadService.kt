package com.molina.studio.download

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
import com.molina.core.common.DownloadItemStatus
import com.molina.core.common.DownloadReducer
import com.molina.core.common.DownloadCommand
import com.molina.core.common.DownloadRunState
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
 * Foreground service (tipe dataSync) yang menjaga proses aplikasi tetap hidup selama unduhan berjalan dan
 * menampilkan persen gabungan di notifikasi. Sumber kebenaran tetap [DownloadRunner.state]; service hanya
 * mengamati. Berhenti sendiri saat unduhan selesai dan meninggalkan satu notifikasi ringkasan (tanpa URL).
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Unduhan", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progres unduhan Molina"
                setShowBadge(false)
            }
        )
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Molina:download").apply {
            setReferenceCounted(false)
            // Batas atas = batas waktu skrip + selisih: tidak pernah menahan CPU tanpa akhir bila service bocor.
            acquire(DownloadCommand.TIMEOUT_MS + 60_000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Wajib dipanggil dulu pada setiap startForegroundService().
        lastStartId = startId
        startForeground(NOTIF_ID, buildOngoing(DownloadRunner.state.value))
        if (intent?.action == ACTION_CANCEL) DownloadRunner.cancel()
        if (watcher == null) {
            watcher = scope.launch {
                DownloadRunner.state.collect { s ->
                    if (s.running) {
                        nm().notify(NOTIF_ID, buildOngoing(s))
                    } else {
                        finishWith(s)
                    }
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

    private fun finishWith(s: DownloadRunState) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val sess = s.session
        com.molina.studio.automation.PostDownload.onFinished(this, sess)
        val text = when {
            sess.fatalError != null -> "Unduhan gagal dimulai"
            sess.cancelled -> "Dibatalkan (${sess.ok} berhasil, ${sess.failed} gagal)"
            sess.finished -> "${sess.ok} berhasil, ${sess.failed} gagal"
            else -> "Unduhan berhenti"
        }
        nm().notify(
            NOTIF_DONE_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_molina)
                .setContentTitle("Unduhan selesai")
                .setContentText(text)
                .setColor(MolinaTheme.GOLD_ARGB)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build()
        )
        // Hentikan pengamat ini; stopSelf(startId) tidak berefek bila unduhan baru sudah memulai service lagi.
        watcher?.cancel()
        watcher = null
        stopSelf(lastStartId)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 10,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_TAB, OPEN_TAB_DOWNLOADS),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildOngoing(s: DownloadRunState): Notification {
        val items = s.session.items
        val total = items.size
        val done = items.count { it.status == DownloadItemStatus.DONE || it.status == DownloadItemStatus.FAILED }
        // Persen keseluruhan: rata-rata persen gabungan semua item (item belum diketahui dihitung 0).
        val percent: Int? = if (total == 0) null else
            items.map { DownloadReducer.overallPercent(it) ?: 0.0 }.average().toInt().coerceIn(0, 100)
        val text = when {
            total == 0 -> "Menyiapkan unduhan…"
            else -> "$done/$total selesai" + (percent?.let { " · $it%" } ?: "")
        }
        val cancel = PendingIntent.getService(
            this, 11,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_stat_molina)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_molina)
            .setContentTitle("Molina Unduhan")
            .setContentText(text)
            .setColor(MolinaTheme.GOLD_ARGB)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent ?: 0, percent == null)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(icon, "Batalkan", cancel).build())
            .build()
    }

    companion object {
        const val ACTION_CANCEL = "com.molina.studio.download.CANCEL"
        const val EXTRA_OPEN_TAB = "com.molina.studio.extra.OPEN_TAB"
        const val OPEN_TAB_DOWNLOADS = "downloads"
        private const val CHANNEL_ID = "molina_download"
        private const val NOTIF_ID = 1002
        private const val NOTIF_DONE_ID = 1003

        /** Idempoten: aman dipanggil berulang. Dipanggil DownloadRunner setelah sesi dimulai. */
        fun ensureRunning(ctx: Context) {
            // Android 12+ menolak start dari latar; unduhan tetap jalan di proses aplikasi, hanya tanpa notifikasi.
            runCatching { ctx.startForegroundService(Intent(ctx, DownloadService::class.java)) }
        }
    }
}

package com.molina.studio.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.molina.studio.AppPaths
import com.molina.studio.MainActivity
import com.molina.studio.R
import com.molina.studio.log.LogBus
import com.molina.core.common.CrashContext
import com.molina.core.common.LogLevel
import com.molina.core.common.LogRedact
import com.molina.core.common.MolinaDir
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reporter Molina: menangkap exception yang tidak tertangani, menulis laporan ke
 * files/crash/, lalu (bila "Crash Report Notifications" aktif) menampilkan notifikasi saat
 * aplikasi dibuka lagi. Penanganan crash bawaan sistem tetap dijalankan setelahnya.
 */
object CrashReporter {
    private const val DIR = "crash"
    private const val PENDING = ".pending"
    private const val CHANNEL = "molina_crash"
    private const val NOTIF_ID = 4101

    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }

    private fun write(ctx: Context, thread: Thread, error: Throwable) {
        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        val d = dir(ctx)
        val version = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val recent = LogBus.entries.value.takeLast(40).joinToString("\n") {
            "${it.level.code}/${it.source}/${it.tag}: ${it.message}"
        }
        val body = buildString {
            appendLine("Molina crash report")
            appendLine("Waktu     : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)}")
            appendLine("Versi     : $version")
            appendLine("Perangkat : ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread    : ${thread.name}")
            appendLine("Mode      : ${CrashContext.describe()}")
            appendLine()
            appendLine(Log.getStackTraceString(error))
            appendLine()
            appendLine("--- 40 entri log terakhir ---")
            appendLine(recent)
        }
        File(d, "crash-$stamp.txt").writeText(body)
        File(d, PENDING).writeText(stamp)
        // Salinan di <appRoot>/Crash supaya mudah diambil dari file manager. URL dan token disamarkan karena
        // folder ini terbaca aplikasi lain; salinan privat di files/crash tetap lengkap. Gagal (mis. izin
        // storage belum ada) tidak boleh mengganggu laporan utama.
        runCatching {
            val pub = AppPaths.get(ctx).dir(MolinaDir.Crash)
            if (pub.isDirectory || pub.mkdirs()) File(pub, "crash-$stamp.txt").writeText(LogRedact.text(body))
        }
        LogBus.log("crash", LogLevel.Error, "uncaught", "${error::class.java.simpleName}: ${error.message}")
    }

    /** Dipanggil saat aplikasi mulai: tampilkan notifikasi untuk crash sebelumnya. */
    fun notifyPending(ctx: Context) {
        val marker = File(dir(ctx), PENDING)
        if (!marker.exists()) return
        marker.delete()
        if (!TerminalPrefs.crashNotifications) return
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Crash Report", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_molina)
                .setContentTitle("Molina berhenti tak terduga")
                .setContentText("Laporan crash tersimpan: Settings > Terminal > Debugging.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
        }
    }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun read(f: File): String = runCatching { f.readText() }.getOrDefault("(tidak dapat dibaca)")

    fun delete(f: File) { f.delete() }

    fun clearAll(ctx: Context) { list(ctx).forEach { it.delete() } }

    /** crash-20261023-101500.txt -> 2026-10-23 10:15:00 */
    fun title(f: File): String {
        val m = Regex("""crash-(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})""").find(f.name)
            ?: return f.name
        val g = m.groupValues
        return "${g[1]}-${g[2]}-${g[3]} ${g[4]}:${g[5]}:${g[6]}"
    }
}

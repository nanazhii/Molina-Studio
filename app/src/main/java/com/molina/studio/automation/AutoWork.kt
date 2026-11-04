package com.molina.studio.automation

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Menjadwalkan (atau membatalkan) pekerjaan latar sesuai [AutoPrefs]. Aman dipanggil berulang. */
object AutoWork {
    private const val UPDATE = "molina-auto-update"
    private const val BACKUP = "molina-auto-backup"
    private const val TOOLS = "molina-tool-check" // [PAKET-WORKER2]

    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        AutoPrefs.init(app)
        val st = AutoPrefs.state.value
        val wm = WorkManager.getInstance(app)

        if (st.autoUpdate) {
            // Maks. sekali per 24 jam, hanya Wi-Fi (jaringan tidak berbayar), tidak saat baterai lemah.
            val c = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()
            val req = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(c)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(UPDATE, ExistingPeriodicWorkPolicy.KEEP, req)
        } else {
            wm.cancelUniqueWork(UPDATE)
        }

        if (st.autoBackup) {
            val c = Constraints.Builder().setRequiresBatteryNotLow(true).build()
            val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(c)
                .setInitialDelay(30, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(BACKUP, ExistingPeriodicWorkPolicy.KEEP, req)
        } else {
            wm.cancelUniqueWork(BACKUP)
        }

        // [PAKET-WORKER2] cek versi alat Paket: lokal tanpa jaringan, tidak bergantung pada autoUpdate; hanya baterai tidak lemah
        val tc = Constraints.Builder().setRequiresBatteryNotLow(true).build()
        val treq = PeriodicWorkRequestBuilder<ToolCheckWorker>(24, TimeUnit.HOURS)
            .setConstraints(tc)
            .setInitialDelay(20, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(TOOLS, ExistingPeriodicWorkPolicy.KEEP, treq)
    }
}

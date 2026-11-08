package com.molina.studio.download

import android.content.Context
import com.molina.core.common.DownloadHistory
import com.molina.core.common.DownloadHistoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors

/**
 * Riwayat sesi unduhan (maks [DownloadHistory.MAX_ENTRIES]) di berkas privat aplikasi `files/download-history.json`.
 * Hanya URL item yang belum berhasil disimpan penuh (untuk Ulangi); item berhasil hanya skema + host.
 * Baca/tulis berkas dilakukan di satu thread latar (berurutan); gagal baca/tulis tidak pernah mengganggu unduhan
 * (riwayat bersifat best-effort). Sumber kebenaran UI: [entries].
 */
internal object DownloadHistoryStore {
    private const val FILE = "download-history.json"

    private val _entries = MutableStateFlow<List<DownloadHistoryEntry>>(emptyList())
    val entries: StateFlow<List<DownloadHistoryEntry>> = _entries.asStateFlow()

    private val lock = Any()
    private var file: File? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "molina-dl-history").apply { isDaemon = true } }

    /** Idempoten; panggil dari MainActivity.onCreate. Memuat berkas di thread latar. */
    fun init(ctx: Context) {
        val f = synchronized(lock) {
            if (file != null) return
            File(ctx.applicationContext.filesDir, FILE).also { file = it }
        }
        io.execute {
            val loaded = try {
                if (f.isFile) DownloadHistory.decode(f.readText(Charsets.UTF_8)) else emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            synchronized(lock) {
                // Entri yang masuk sebelum pemuatan selesai (jarang) digabung, bukan ditimpa.
                var merged = loaded
                for (e in _entries.value.reversed()) merged = DownloadHistory.push(merged, e)
                _entries.value = merged
            }
        }
    }

    fun add(entry: DownloadHistoryEntry) {
        synchronized(lock) { _entries.value = DownloadHistory.push(_entries.value, entry) }
        persist()
    }

    fun remove(at: Long) {
        synchronized(lock) { _entries.value = _entries.value.filter { it.at != at } }
        persist()
    }

    fun clear() {
        synchronized(lock) { _entries.value = emptyList() }
        persist()
    }

    /** Menulis keadaan TERBARU saat tugas berjalan (bukan saat dijadwalkan); atomik lewat berkas sementara. */
    private fun persist() {
        val f = synchronized(lock) { file } ?: return
        io.execute {
            val snapshot = _entries.value
            try {
                if (snapshot.isEmpty()) {
                    f.delete()
                } else {
                    val tmp = File(f.parentFile, f.name + ".tmp")
                    tmp.writeText(DownloadHistory.encode(snapshot), Charsets.UTF_8)
                    if (!tmp.renameTo(f)) {
                        f.delete()
                        tmp.renameTo(f)
                    }
                }
            } catch (e: Exception) {
                // best-effort
            }
        }
    }
}

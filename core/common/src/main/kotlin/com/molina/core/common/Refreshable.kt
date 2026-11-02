package com.molina.core.common

/** Hasil penyegaran satu modul. Semua hitungan adalah jumlah proses/objek yang benar-benar dihentikan. */
data class RefreshReport(
    val id: String,
    /** Dihentikan lewat jalur resmi (layanan, sesi, mpv destroy). */
    val stopped: Int = 0,
    /** Dihentikan paksa (SIGTERM lalu SIGKILL). */
    val killed: Int = 0,
    /** Masih hidup setelah semua upaya. */
    val remaining: Int = 0,
    val note: String? = null,
) {
    val total: Int get() = stopped + killed
}

/**
 * Satu modul yang bisa "disegarkan": semua proses latarnya dimatikan sampai akar. Modul mendaftar
 * lewat [Refreshers]; layar Pengaturan hanya mengenal tipe ini, jadi modul baru (mis. Code, Fase 6)
 * cukup mendaftar tanpa mengubah UI. Beda dengan [ExitHook]: aplikasi tetap hidup.
 */
interface Refreshable {
    /** Pengenal stabil ("mpv", "terminal", "code"). */
    val id: String

    /** Nama untuk UI. */
    val label: String

    /** Jumlah proses/objek aktif saat ini. Murah dan tanpa jaringan; boleh dipanggil dari thread latar. */
    fun activeCount(): Int

    /** Perkiraan RAM (KB) yang dipegang modul, atau -1 bila tidak diketahui. */
    fun memoryKb(): Long = -1L

    /** Matikan semuanya. Blocking: panggil dari thread latar. Kegagalan dilaporkan di [RefreshReport.note]. */
    fun refresh(): RefreshReport
}

/** Registri [Refreshable]; urutan pendaftaran = urutan tampil dan urutan "Segarkan Semua". */
object Refreshers {
    private val items = LinkedHashMap<String, Refreshable>()

    @Synchronized
    fun register(r: Refreshable) {
        items[r.id] = r
    }

    @Synchronized
    fun unregister(id: String) {
        items.remove(id)
    }

    @Synchronized
    fun all(): List<Refreshable> = items.values.toList()

    @Synchronized
    fun get(id: String): Refreshable? = items[id]

    /** Jalankan satu modul; kegagalan jadi laporan, bukan exception. */
    fun refresh(id: String): RefreshReport {
        val r = get(id) ?: return RefreshReport(id, note = "modul tidak terdaftar")
        return safe(r)
    }

    /** Jalankan semua berurutan; kegagalan satu modul tidak menghentikan yang lain. */
    fun refreshAll(): List<RefreshReport> = all().map { safe(it) }

    private fun safe(r: Refreshable): RefreshReport =
        try {
            r.refresh()
        } catch (e: Throwable) {
            RefreshReport(r.id, note = "gagal: ${e.javaClass.simpleName}")
        }
}

package com.molina.core.api

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Jenis pilihan yang diminta fitur dari Penjelajah. */
enum class ExplorerPickKind { FILE, FOLDER }

/** Satu permintaan pilih. [onResult] dipanggil tepat sekali: berkas/folder terpilih, atau null bila dibatalkan atau digantikan. */
class ExplorerRequest(val id: Long, val kind: ExplorerPickKind, val startDir: File?, val onResult: (File?) -> Unit)

/**
 * Kontrak Penjelajah untuk fitur lain (code, mpv, ...): minta pengguna memilih berkas atau folder tanpa
 * mengimpor UI di :app. Hanya tipe JVM murni. Implementasi dan host UI hidup di :app dan didaftarkan lewat
 * [MolinaServices] saat aplikasi mulai. Aksi lain (putar, buka di Code) tetap lewat kontrak masing-masing.
 */
interface ExplorerGateway {
    /** Permintaan yang sedang tampil; host UI merender Penjelajah selama tidak null. */
    val request: StateFlow<ExplorerRequest?>

    /** Minta pilih. Permintaan baru menggantikan yang sedang tampil (yang lama menerima null). Mengembalikan id. */
    fun pick(kind: ExplorerPickKind, startDir: File? = null, onResult: (File?) -> Unit): Long

    /** Dipanggil host UI saat pengguna memilih atau menutup; id yang sudah tidak aktif diabaikan. */
    fun complete(id: Long, result: File?)

    fun cancel(id: Long) = complete(id, null)

    /** Folder terakhir yang dibuka di Penjelajah (masih ada), atau null bila belum ada. */
    fun lastFolder(): File? = null
}

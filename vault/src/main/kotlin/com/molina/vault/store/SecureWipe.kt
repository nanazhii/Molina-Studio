package com.molina.vault.store

// MOLINA-H10-STREAM
import java.io.File
import java.io.RandomAccessFile

/** Timpa-nol berkas per blok lalu hapus. Memori tetap 64 KB berapa pun ukuran berkas (tahap 1 memakai ByteArray sebesar berkas). */
object SecureWipe {
    private const val BLOCK = 64 * 1024

    /** Menimpa nol lalu hapus. Di flash tidak dijamin; perlindungan sebenarnya adalah enkripsi. */
    fun overwriteAndDelete(f: File) {
        if (!f.exists()) return
        try { overwrite(f) } catch (e: Exception) { /* tetap lanjut hapus */ }
        f.delete()
    }

    internal fun overwrite(f: File) {
        RandomAccessFile(f, "rw").use { raf ->
            val zero = ByteArray(BLOCK)
            var left = raf.length()
            raf.seek(0)
            while (left > 0) {
                val n = minOf(left, BLOCK.toLong()).toInt()
                raf.write(zero, 0, n)
                left -= n
            }
            raf.fd.sync()
        }
    }
}

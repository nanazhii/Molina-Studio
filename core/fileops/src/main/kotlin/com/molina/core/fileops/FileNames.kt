package com.molina.core.fileops

/** [CORE-FILEOPS] Aturan nama berkas yang dipakai bersama Penjelajah, Laci Berkas, dan Mode TV. Murni JVM. */
object FileNames {
    /**
     * Batas akhir "nama tanpa ekstensi" untuk menyorot bagian nama saat ganti nama.
     * Folder: seluruh nama. Berkas: sebelum titik terakhir; titik di awal (".bashrc") dan tanpa titik = seluruh nama.
     */
    fun stemEnd(name: String, isDir: Boolean): Int =
        if (isDir) name.length else name.lastIndexOf('.').let { if (it > 0) it else name.length }
}

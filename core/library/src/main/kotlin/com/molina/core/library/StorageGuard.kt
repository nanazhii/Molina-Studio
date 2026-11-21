package com.molina.core.library

import java.io.File

/**
 * [CORE-LIBRARY] Satu-satunya sumber daftar volume penyimpanan. Dipakai Penjelajah (Berkas) dan Mode TV.
 * Murni JVM: akar internal disuntik pemanggil (Android: Environment.getExternalStorageDirectory()).
 */
object StorageVolumes {
    /** Volume pertama = [internalRoot]; berikutnya tiap folder terbaca di [storageRoot] selain "emulated" dan "self" (kartu SD/USB). */
    fun list(internalRoot: File, storageRoot: File = File("/storage")): List<File> {
        val out = mutableListOf(internalRoot)
        storageRoot.listFiles()?.forEach {
            if (it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self") out.add(it)
        }
        return out
    }
}

/**
 * [CORE-LIBRARY] Satu-satunya aturan jalur yang tidak boleh dihapus/diubah namanya/dipindah: akar tiap volume,
 * folder Recycle Bin tiap volume, akar data aplikasi, ditambah [extra] (mis. folder saluran Mode TV).
 * Isi folder terlindung tetap boleh diubah; hanya jalur itu sendiri yang dijaga.
 */
object ProtectedPaths {
    fun of(volumes: List<File>, appRoot: File, extra: List<File> = emptyList()): Set<String> {
        val out = HashSet<String>()
        for (v in volumes) {
            out.add(v.absolutePath)
            out.add(File(v, TrashBin.DIR).absolutePath)
        }
        out.add(appRoot.absolutePath)
        for (e in extra) out.add(e.absolutePath)
        return out
    }

    fun canModify(file: File, protected: Set<String>): Boolean = file.absolutePath !in protected
}

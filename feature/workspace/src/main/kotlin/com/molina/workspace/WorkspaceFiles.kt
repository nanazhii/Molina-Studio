package com.molina.workspace

import java.io.File
import java.nio.file.Files

/** Satu entri folder pada panel Folder Kerja. */
class DirEntry(val file: File, val isDir: Boolean) {
    val name: String get() = file.name
    val hidden: Boolean get() = file.name.startsWith(".")
}

/** Hasil operasi berkas: [error] null berarti sukses dan [file] berisi hasilnya. */
class FsResult private constructor(val file: File?, val error: String?) {
    val ok: Boolean get() = error == null

    companion object {
        fun ok(file: File) = FsResult(file, null)
        fun fail(message: String) = FsResult(null, message)
    }
}

/**
 * I/O berkas Folder Kerja (padanan FileStore/DirectoryState editor lama, tanpa CodeSession).
 * Semua fungsi memblokir: panggil dari thread IO, bukan thread utama.
 */
object WorkspaceFiles {
    const val STORAGE_ROOT = WorkspaceRootPolicy.STORAGE_ROOT
    const val MAX_LIST_ENTRIES = 2000
    private const val MAX_NAME_BYTES = 240

    private fun err(e: Exception) = "Gagal: ${e.message ?: e.javaClass.simpleName}"

    /** Isi folder: folder dulu, lalu berkas, A-Z tanpa peka huruf. Null bila tidak bisa dibaca (beda dari kosong). */
    fun listDir(dir: File): List<DirEntry>? {
        val arr = try {
            dir.listFiles()
        } catch (e: SecurityException) {
            null
        } ?: return null
        return arr.asSequence()
            .map { DirEntry(it, it.isDirectory) }
            .sortedWith(compareBy<DirEntry>({ !it.isDir }, { it.file.name.lowercase() }, { it.file.name }))
            .take(MAX_LIST_ENTRIES)
            .toList()
    }

    /** Hapus berkas atau folder beserta isinya. Tautan simbolik dihapus sebagai tautan, isinya tidak disentuh. */
    fun deleteTree(f: File): Boolean {
        val link = try {
            Files.isSymbolicLink(f.toPath())
        } catch (e: Exception) {
            false
        }
        if (!link && f.isDirectory) {
            f.listFiles()?.forEach { if (!deleteTree(it)) return false }
        }
        return f.delete()
    }

    /** Pesan galat untuk nama yang tidak boleh dipakai, atau null bila valid. */
    fun validate(name: String): String? {
        val n = name.trim()
        return when {
            n.isEmpty() -> "Nama kosong"
            n == "." || n == ".." -> "Nama tidak valid"
            '/' in n || '\u0000' in n -> "Nama tidak boleh mengandung /"
            n.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> "Nama terlalu panjang"
            else -> null
        }
    }

    fun createFile(parent: File, name: String): FsResult {
        validate(name)?.let { return FsResult.fail(it) }
        val f = File(parent, name.trim())
        return try {
            when {
                f.exists() -> FsResult.fail("Sudah ada: ${f.name}")
                else -> {
                    parent.mkdirs()
                    if (f.createNewFile()) FsResult.ok(f) else FsResult.fail("Gagal membuat berkas")
                }
            }
        } catch (e: Exception) {
            FsResult.fail(err(e))
        }
    }

    fun createFolder(parent: File, name: String): FsResult {
        validate(name)?.let { return FsResult.fail(it) }
        val f = File(parent, name.trim())
        return try {
            when {
                f.exists() -> FsResult.fail("Sudah ada: ${f.name}")
                f.mkdirs() -> FsResult.ok(f)
                else -> FsResult.fail("Gagal membuat folder")
            }
        } catch (e: Exception) {
            FsResult.fail(err(e))
        }
    }

    /** Ganti nama di folder yang sama. Nama yang sama dengan nama lama dianggap sukses tanpa perubahan. */
    fun rename(file: File, newName: String): FsResult {
        validate(newName)?.let { return FsResult.fail(it) }
        val parent = file.parentFile ?: return FsResult.fail("Folder induk tidak valid")
        val target = File(parent, newName.trim())
        if (target.path == file.path) return FsResult.ok(file)
        return try {
            // Penyimpanan emulasi Android tidak peka huruf: a.txt -> A.txt "sudah ada" padahal berkas yang sama.
            val sameFile = target.exists() && file.name.equals(target.name, ignoreCase = true) &&
                try {
                    Files.isSameFile(file.toPath(), target.toPath())
                } catch (e: java.io.IOException) {
                    false // sumber tidak ada: bukan berkas yang sama
                }
            when {
                target.exists() && !sameFile -> FsResult.fail("Sudah ada: ${target.name}")
                file.renameTo(target) -> FsResult.ok(target)
                else -> FsResult.fail("Gagal mengganti nama")
            }
        } catch (e: Exception) {
            FsResult.fail(err(e))
        }
    }

    /**
     * Hapus [file]. Ditolak untuk /storage/emulated/0 dan untuk folder utama [root] atau induknya,
     * karena menghapusnya ikut menghapus folder kerja itu sendiri.
     */
    fun delete(file: File, root: File): FsResult {
        if (WorkspaceRootPolicy.normalize(file).path == STORAGE_ROOT || WorkspaceRootPolicy.contains(file, root)) {
            return FsResult.fail("Folder ini tidak boleh dihapus")
        }
        return try {
            if (deleteTree(file)) FsResult.ok(file) else FsResult.fail("Gagal menghapus ${file.name}")
        } catch (e: Exception) {
            FsResult.fail(err(e))
        }
    }
}

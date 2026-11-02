package com.molina.code.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Satu baris tree yang tampak: entri, kedalaman indent, dan status expand (khusus folder). */
internal class DirRow(val entry: DirEntry, val depth: Int, val expanded: Boolean)

/** Dialog aksi berkas yang dirender oleh [FileDialogs]. */
internal sealed class FileDialog {
    class Menu(val file: File, val fromTree: Boolean = false) : FileDialog()
    class Rename(val file: File) : FileDialog()
    class Delete(val file: File) : FileDialog()
    class NewFile(val parent: File) : FileDialog()
    class NewFolder(val parent: File) : FileDialog()
}

/**
 * Model panel direktori (F4, blueprint bagian 5 dan 31). Isi folder dibaca di thread IO sesi dan
 * disimpan di [children]; tree yang tampak dihitung dari [children] dan [expandedKeys] oleh [rows].
 */
internal class DirectoryState(private val session: CodeSession, initialRoot: File) {
    /** Panel terbuka. */
    var open by mutableStateOf(false)
        private set

    /** Folder utama tree. Juga folder awal Simpan sebagai dan pemilih Buka. */
    var root by mutableStateOf(initialRoot)
        private set

    /** Path baris yang disorot (berkas aktif atau yang terakhir diketuk). */
    var selected by mutableStateOf<String?>(null)

    /** True bila folder utama tidak bisa dibaca (tidak ada atau izin ditolak): tampilkan empty state. */
    var rootUnreadable by mutableStateOf(false)
        private set

    private val children = mutableStateMapOf<String, List<DirEntry>>()
    private val expandedKeys = mutableStateListOf<String>()
    private val loading = HashSet<String>() // hanya diakses dari thread utama

    fun openPanel() {
        open = true
        if (children[root.path] == null) load(root)
    }

    fun closePanel() {
        open = false
    }

    /** Dipakai saat memulihkan sesi: ganti folder utama tanpa memuat atau menjadwalkan draf. */
    fun restoreRoot(dir: File) {
        root = dir
    }

    fun changeRoot(dir: File) {
        root = dir
        expandedKeys.clear()
        children.clear()
        rootUnreadable = false
        session.scheduleDraft()
        if (open) load(dir)
    }

    /** Naik satu level. Berhenti di /storage/emulated/0. */
    fun up() {
        val parent = root.parentFile ?: return
        if (root.path == FileStore.STORAGE_ROOT) return
        changeRoot(parent)
    }

    /** Muat ulang folder utama dan semua folder yang sedang terbuka. */
    fun refresh() {
        if (!open) {
            children.clear()
            return
        }
        load(root)
        expandedKeys.toList().forEach { load(File(it)) }
    }

    /** Muat ulang folder induk [file] bila terlihat di tree (setelah simpan/buat/hapus). */
    fun refreshParentOf(file: File) {
        val parent = file.parentFile ?: return
        if (children.containsKey(parent.path)) load(parent)
    }

    fun toggle(dir: File) {
        val key = dir.path
        if (key in expandedKeys) {
            expandedKeys.remove(key)
        } else {
            expandedKeys.add(key)
            if (children[key] == null) load(dir)
        }
    }

    /** Tampilkan [file] di tree: ganti folder utama bila di luar, buka folder induknya, buka panel. */
    fun reveal(file: File) {
        val parent = file.parentFile ?: return
        val rootKey = root.path
        if (parent.path != rootKey && !parent.path.startsWith("$rootKey/")) changeRoot(parent)
        val chain = ArrayList<File>()
        var p: File? = parent
        while (p != null && p.path != root.path) {
            chain.add(p)
            p = p.parentFile
        }
        for (d in chain.reversed()) {
            if (d.path !in expandedKeys) {
                expandedKeys.add(d.path)
                if (children[d.path] == null) load(d)
            }
        }
        selected = file.path
        openPanel()
    }

    /** Daftar baris yang tampak. Membaca state, jadi memicu recompose saat isi berubah. */
    fun rows(): List<DirRow> {
        val out = ArrayList<DirRow>()
        fun walk(dir: File, depth: Int) {
            val list = children[dir.path] ?: return
            for (e in list) {
                val exp = e.isDir && e.file.path in expandedKeys
                out.add(DirRow(e, depth, exp))
                if (exp) walk(e.file, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    /** True bila folder utama sudah dibaca dan kosong. */
    fun isRootEmpty(): Boolean = !rootUnreadable && children[root.path]?.isEmpty() == true

    private fun load(dir: File) {
        val key = dir.path
        if (!loading.add(key)) return
        session.runIo {
            val list = FileStore.listDir(dir)
            session.mainHandler.post {
                loading.remove(key)
                val isRoot = key == root.path
                if (list == null) {
                    if (isRoot) rootUnreadable = true
                    children[key] = emptyList()
                } else {
                    if (isRoot) rootUnreadable = false
                    children[key] = list
                }
            }
        }
    }

    private fun ensureExpanded(dir: File) {
        if (dir.path != root.path && dir.path !in expandedKeys) expandedKeys.add(dir.path)
    }

    private fun dropExpandedUnder(path: String) {
        expandedKeys.removeAll { it == path || it.startsWith("$path/") }
    }

    // ---------------------------------------------------------------- Operasi berkas

    fun createFile(parent: File, name: String) {
        val err = FileStore.validate(name, "")
        if (err != null) {
            session.toast(err)
            return
        }
        val f = File(parent, name.trim())
        session.runIo {
            val result: String? = try {
                if (f.exists()) {
                    "Sudah ada: ${f.name}"
                } else {
                    parent.mkdirs()
                    if (f.createNewFile()) null else "Gagal membuat berkas"
                }
            } catch (e: Exception) {
                "Gagal: ${e.message ?: e.javaClass.simpleName}"
            }
            session.mainHandler.post {
                if (result != null) {
                    session.toast(result)
                } else {
                    ensureExpanded(parent)
                    load(parent)
                    session.openFile(f)
                }
            }
        }
    }

    fun createFolder(parent: File, name: String) {
        val err = FileStore.validate(name, "")
        if (err != null) {
            session.toast(err)
            return
        }
        val f = File(parent, name.trim())
        session.runIo {
            val result: String? = try {
                if (f.exists()) "Sudah ada: ${f.name}" else if (f.mkdirs()) null else "Gagal membuat folder"
            } catch (e: Exception) {
                "Gagal: ${e.message ?: e.javaClass.simpleName}"
            }
            session.mainHandler.post {
                if (result != null) {
                    session.toast(result)
                } else {
                    ensureExpanded(parent)
                    expandedKeys.add(f.path)
                    load(parent)
                    load(f)
                    selected = f.path
                }
            }
        }
    }

    fun rename(file: File, newName: String) {
        val err = FileStore.validate(newName, "")
        if (err != null) {
            session.toast(err)
            return
        }
        val parent = file.parentFile ?: return
        val target = File(parent, newName.trim())
        if (target.path == file.path) return
        session.runIo {
            val result: String? = try {
                if (target.exists()) "Sudah ada: ${target.name}" else if (file.renameTo(target)) null else "Gagal mengganti nama"
            } catch (e: Exception) {
                "Gagal: ${e.message ?: e.javaClass.simpleName}"
            }
            session.mainHandler.post {
                if (result != null) {
                    session.toast(result)
                } else {
                    session.onRenamed(file, target)
                    dropExpandedUnder(file.path)
                    if (selected == file.path) selected = target.path
                    load(parent)
                }
            }
        }
    }

    fun delete(file: File) {
        if (file.path == root.path || file.path == FileStore.STORAGE_ROOT) {
            session.toast("Folder ini tidak boleh dihapus")
            return
        }
        val parent = file.parentFile
        session.runIo {
            val ok = try {
                FileStore.deleteTree(file)
            } catch (e: Exception) {
                false
            }
            session.mainHandler.post {
                if (!ok) {
                    session.toast("Gagal menghapus ${file.name}")
                } else {
                    session.onDeleted(file)
                    dropExpandedUnder(file.path)
                    if (selected == file.path) selected = null
                }
                if (parent != null) load(parent)
            }
        }
    }
}

package com.molina.code.nav

import java.io.File
import java.nio.file.Files

// [CODE-DEBLIB] Path guest Debian -> berkas di rootfs (terlihat dari Android). Kotlin/JVM murni.

object RootfsPaths {
    const val MAX_LINKS = 40

    /**
     * Resolusi gaya chroot: symlink absolut dibaca relatif terhadap [rootfs] (bukan root Android), ".." tidak pernah
     * keluar dari [rootfs]. Null bila path bukan absolut, putus, berputar (lebih dari [MAX_LINKS] symlink), atau
     * hasilnya bukan berkas biasa. Hasil selalu berada di bawah [rootfs].
     */
    fun resolve(rootfs: File, guestPath: String): File? {
        if (!guestPath.startsWith("/") || guestPath.indexOf('\u0000') >= 0) return null
        val root = try { rootfs.canonicalFile } catch (e: Exception) { return null }
        val pending = ArrayDeque(parts(guestPath))
        val done = ArrayList<String>()
        var links = 0
        while (pending.isNotEmpty()) {
            val c = pending.removeFirst()
            if (c == "." || c.isEmpty()) continue
            if (c == "..") { if (done.isNotEmpty()) done.removeAt(done.size - 1); continue }
            val node = File(root, (done + c).joinToString("/")).toPath()
            if (Files.isSymbolicLink(node)) {
                if (++links > MAX_LINKS) return null
                val target = try { Files.readSymbolicLink(node).toString() } catch (e: Exception) { return null }
                if (target.startsWith("/")) done.clear()
                val tp = parts(target)
                for (i in tp.indices.reversed()) pending.addFirst(tp[i])
            } else {
                done.add(c)
            }
        }
        if (done.isEmpty()) return null
        val out = File(root, done.joinToString("/"))
        return if (out.isFile) out else null
    }

    /** True bila [file] berada di bawah [rootfs] (membandingkan path kanonik). */
    fun isUnder(rootfs: File, file: File): Boolean = try {
        val r = rootfs.canonicalPath.trimEnd('/')
        val f = file.canonicalPath
        f.startsWith("$r/")
    } catch (e: Exception) {
        false
    }

    private fun parts(p: String): List<String> = p.split('/').filter { it.isNotEmpty() }
}

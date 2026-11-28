package com.molina.code.guard

import java.io.File

// [CODE-FEED] Notifikasi perubahan berkas: satu butir = satu peristiwa (skrip jalan dari Code atau lewat nano) di satu folder,
// berisi daftar berkas yang berubah. Disimpan append-only di berkas privat aplikasi (bukan di folder pengguna), satu butir per
// baris: `FEED1<TAB>0|1<TAB>folder<TAB>json`, 1 = belum dibaca. Isi json memakai ProjectLog.Entry. Berlaku untuk folder mana pun
// (tak perlu penanda `.molina`). Kotlin murni: scripts/code-tests/run_changefeed.sh.
object ChangeFeed {
    const val MAX_ITEMS = 200
    private const val PRUNE_SLACK = 20
    private const val TAG = "FEED1"
    const val SRC_NANO = "nano"
    const val SRC_EXTERNAL = "external" // [CODE-FEED3] berkas terbuka di tab diubah dari luar editor
    const val SRC_RESTORE = "restore" // [EXT-DIFF3] berkas dipulihkan (atau pemulihan dibatalkan) dari butir feed

    class Item(val folder: String, val unread: Boolean, val entry: ProjectLog.Entry)

    /** Tambahkan butir untuk [folder]. False bila folder tak valid (tab/baris baru) atau penulisan gagal. Best-effort. */
    @Synchronized
    fun append(file: File, folder: File, e: ProjectLog.Entry): Boolean {
        val dir = folder.path
        if (dir.isEmpty() || dir.any { it == '\t' || it == '\n' || it == '\r' }) return false
        return try {
            file.parentFile?.mkdirs()
            val needNl = file.isFile && file.length() > 0 && !file.readBytes().last().let { it == '\n'.code.toByte() }
            file.appendText((if (needNl) "\n" else "") + line(true, dir, e) + "\n", Charsets.UTF_8)
            prune(file)
            true
        } catch (ex: Exception) {
            false
        }
    }

    /** Semua butir, terbaru dulu. Baris rusak dilewati. */
    @Synchronized
    fun read(file: File): List<Item> = parseAll(file).asReversed()

    @Synchronized
    fun unreadCount(file: File): Int = parseAll(file).count { it.unread }

    /** Tandai semua butir sudah dibaca (urutan dipertahankan). */
    @Synchronized
    fun markAllRead(file: File) {
        val items = parseAll(file)
        if (items.none { it.unread }) return
        rewrite(file, items.map { line(false, it.folder, it.entry) })
    }

    @Synchronized
    fun clear(file: File) {
        try { file.delete() } catch (ex: Exception) { /* best-effort */ }
    }

    /** Kalimat ringkas satu butir, mis. "a.sh di folder work diubah dan 2 berkas lain". */
    fun headline(item: Item): String {
        val e = item.entry
        val first = e.files.firstOrNull() ?: return e.message
        val verb = when (first.kind) { 'A' -> "ditambah"; 'D' -> "dihapus"; else -> "diubah" }
        val dirName = File(item.folder).name.ifEmpty { item.folder }
        val rest = e.fileCount - 1
        return "${first.path} di folder $dirName $verb" + if (rest > 0) " dan $rest berkas lain" else ""
    }

    /** Label sumber untuk baris kedua; kosong bila tak dikenal. */
    fun sourceLabel(src: String): String = when (src) {
        SRC_NANO -> "via nano"
        ProjectLog.SRC_SCRIPT -> "dari Code"
        SRC_EXTERNAL -> "dari luar"
        SRC_RESTORE -> "pemulihan"
        else -> ""
    }

    /** [CODE-FEED3] Butir untuk satu berkas yang diubah dari luar editor. */
    fun externalEntry(name: String, time: Long, added: Int = 0, removed: Int = 0): ProjectLog.Entry = // [EXT-DIFF]
        ProjectLog.Entry(time, SRC_EXTERNAL, "Diubah dari luar editor", listOf(ProjectLog.FileChange(name, 'M', added, removed)))

    /**
     * [CODE-FEED3] Sudah tercakup butir yang ada? Benar bila ada butir (skrip/nano) yang memuat [target] dan dicatat tidak lebih
     * awal dari [modified] (dikurangi [COVER_SLACK_MS]). Mencegah notifikasi ganda untuk berkas yang diubah skrip lalu dimuat ulang.
     */
    @Synchronized
    fun covered(file: File, target: File, modified: Long): Boolean = parseAll(file).any { it.entry.time + COVER_SLACK_MS >= modified &&
        it.entry.files.any { c -> File(File(it.folder), c.path).path == target.path } }

    private const val COVER_SLACK_MS = 2_000L

    /** [CODE-FEED4] Sudah ada butir yang memuat [target] dan dicatat sejak [since] (waktu baseline folder)? Mencegah notifikasi ganda dengan nano/skrip. */
    @Synchronized
    fun coveredSince(file: File, target: File, since: Long): Boolean = parseAll(file).any { it.entry.time >= since &&
        it.entry.files.any { c -> File(File(it.folder), c.path).path == target.path } }

    private fun line(unread: Boolean, folder: String, e: ProjectLog.Entry) = TAG + "\t" + (if (unread) "1" else "0") + "\t" + folder + "\t" + ProjectLog.encode(e)

    private fun parseAll(file: File): List<Item> {
        if (!file.isFile) return emptyList()
        return try {
            file.readLines(Charsets.UTF_8).mapNotNull { parse(it) }
        } catch (ex: Exception) {
            emptyList()
        }
    }

    private fun parse(l: String): Item? {
        val p = l.split('\t', limit = 4)
        if (p.size != 4 || p[0] != TAG || (p[1] != "0" && p[1] != "1") || p[2].isEmpty()) return null
        val e = ProjectLog.decode(p[3]) ?: return null
        return Item(p[2], p[1] == "1", e)
    }

    private fun prune(file: File) {
        val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        if (lines.size <= MAX_ITEMS + PRUNE_SLACK) return
        rewrite(file, lines.takeLast(MAX_ITEMS))
    }

    private fun rewrite(file: File, lines: List<String>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) tmp.delete() }
        } catch (ex: Exception) {
            tmp.delete()
        }
    }
}

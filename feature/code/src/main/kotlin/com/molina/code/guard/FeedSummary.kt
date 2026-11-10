package com.molina.code.guard

// [EXT-FEED7] Ringkasan teks satu butir notifikasi perubahan, untuk disalin/dibagikan. Kotlin murni (tanpa Android):
// scripts/code-tests/run_feedsummary.sh. Tanda +/- memakai ASCII agar aman ditempel ke chat, issue, atau pesan commit.
object FeedSummary {
    /** Satu berkas: `M  path/File.kt  +12 -3`. Tanpa angka bila tidak ada baris berubah (mis. berkas dihapus). */
    private fun fileLine(c: ProjectLog.FileChange, sep: String): String {
        val path = c.path.replace('\n', ' ').replace('\r', ' ')
        val counts = if (c.added + c.removed > 0) "$sep+${c.added} -${c.removed}" else ""
        return "${c.kind}$sep$path$counts"
    }

    private fun totals(e: ProjectLog.Entry): Pair<Int, Int> = e.files.sumOf { it.added } to e.files.sumOf { it.removed }

    /**
     * Ringkasan biasa untuk dilaporkan: judul (folder, waktu, sumber), satu baris per berkas, lalu total.
     * [whenText] dan [via] diformat pemanggil (via boleh kosong) agar fungsi ini deterministik.
     */
    fun plain(folder: String, whenText: String, via: String, e: ProjectLog.Entry): String {
        val dirName = java.io.File(folder).name.ifEmpty { folder }
        val sb = StringBuilder()
        sb.append("Perubahan di ").append(dirName).append(" \u00B7 ").append(whenText)
        if (via.isNotEmpty()) sb.append(" \u00B7 ").append(via)
        sb.append('\n')
        if (e.files.isEmpty()) {
            if (e.message.isNotEmpty()) sb.append(e.message).append('\n')
            return sb.toString().trimEnd()
        }
        for (c in e.files) sb.append(fileLine(c, "  ")).append('\n')
        if (e.omitted > 0) sb.append("(+${e.omitted} berkas lain tidak dimuat)\n")
        val (a, r) = totals(e)
        sb.append("Total ${e.fileCount} berkas, +$a -$r")
        if (e.omitted > 0) sb.append(" (hanya berkas yang dimuat)")
        return sb.toString()
    }

    /** Pesan commit bergaya Conventional Commits: `chore: ubah 3 berkas (+17 -3)` lalu daftar berkas sebagai butir. */
    fun commit(e: ProjectLog.Entry): String {
        val verb = when {
            e.files.isNotEmpty() && e.files.all { it.kind == 'A' } -> "tambah"
            e.files.isNotEmpty() && e.files.all { it.kind == 'D' } -> "hapus"
            else -> "ubah"
        }
        val (a, r) = totals(e)
        val sb = StringBuilder()
        sb.append("chore: ").append(verb).append(' ').append(e.fileCount).append(" berkas (+").append(a).append(" -").append(r).append(")")
        if (e.files.isNotEmpty()) {
            sb.append("\n")
            for (c in e.files) sb.append("\n- ").append(fileLine(c, " "))
            if (e.omitted > 0) sb.append("\n- dan ${e.omitted} berkas lain")
        }
        return sb.toString()
    }

    /** [EXT-FEED8] Batas butir terbaru dalam satu laporan gabungan, agar teks tidak melewati batas clipboard/Intent. */
    const val MAX_REPORT_ITEMS = 50

    /**
     * [EXT-FEED8] Laporan gabungan beberapa butir (masukan terbaru dulu), dikelompokkan per folder; folder dengan butir terbaru di atas.
     * Kosong bila [items] kosong. [nowText] = waktu pembuatan laporan, [whenFmt] memformat waktu tiap butir (keduanya dari pemanggil agar deterministik).
     */
    fun report(items: List<ChangeFeed.Item>, nowText: String, whenFmt: (Long) -> String): String {
        if (items.isEmpty()) return ""
        val used = items.take(MAX_REPORT_ITEMS)
        val files = used.sumOf { it.entry.fileCount }
        val a = used.sumOf { i -> i.entry.files.sumOf { it.added } }
        val r = used.sumOf { i -> i.entry.files.sumOf { it.removed } }
        val groups = used.groupBy { it.folder }
        val sb = StringBuilder()
        sb.append("Laporan perubahan \u00B7 ").append(nowText).append('\n')
        sb.append("${used.size} peristiwa, ${groups.size} folder, $files berkas, +$a -$r")
        if (items.size > used.size) sb.append("\n(${items.size - used.size} peristiwa lebih lama tidak disertakan)")
        for ((folder, list) in groups) {
            sb.append("\n\nFolder: ").append(folder)
            for (item in list) {
                val e = item.entry
                val via = ChangeFeed.sourceLabel(e.source)
                sb.append('\n').append(whenFmt(e.time))
                if (via.isNotEmpty()) sb.append(" \u00B7 ").append(via)
                if (e.files.isEmpty() && e.message.isNotEmpty()) sb.append("\n  ").append(e.message.replace('\n', ' ').replace('\r', ' '))
                for (c in e.files) sb.append("\n  ").append(fileLine(c, "  "))
                if (e.omitted > 0) sb.append("\n  (+${e.omitted} berkas lain tidak dimuat)")
            }
        }
        return sb.toString()
    }
}

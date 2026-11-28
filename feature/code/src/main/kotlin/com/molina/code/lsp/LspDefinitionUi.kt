package com.molina.code.lsp

// [CODE-LSP6] Pergi ke definisi: aturan murni (tanpa Android, supaya teruji di JVM). Semua posisi basis 0, kolom UTF-16.

/** Rentang pengenal di satu baris: [start] inklusif, [end] eksklusif. */
data class WordSpan(val start: Int, val end: Int, val requestColumn: Int)

/** Satu pilihan pada daftar tujuan. [line] dan [column] basis 0; [label] siap tampil ("pkg/mod.py:12"). */
data class DefinitionChoice(val hostPath: String, val line: Int, val column: Int, val label: String)

/** Hasil balasan server untuk satu permintaan definisi, dipindahkan ke main thread oleh [LspLive]. */
sealed class LspDefinitionReply {
    data class Targets(val list: List<LspDefinitionTarget>) : LspDefinitionReply()
    data class Error(val reason: String) : LspDefinitionReply()
}

/** Keputusan UI setelah balasan server disaring. */
sealed class DefinitionOutcome {
    data class Jump(val hostPath: String, val line: Int, val column: Int) : DefinitionOutcome()
    data class Choose(val choices: List<DefinitionChoice>) : DefinitionOutcome()

    /** Kursor sudah berada di nama yang mendefinisikan dirinya sendiri. */
    object AtDefinition : DefinitionOutcome()
    object NotFound : DefinitionOutcome()

    /** Semua tujuan ada di dalam Debian (mis. pustaka sistem) dan tidak terbuka dari Android. */
    data class OnlyInDebian(val guestPath: String, val count: Int) : DefinitionOutcome()
}

object LspDefinitionLogic {
    const val MAX_CHOICES = 30

    /**
     * Pengenal di sekitar [column]: huruf/angka/garis bawah yang mencakup kursor, atau yang berakhir tepat di kursor.
     * Null bila tidak ada pengenal, atau "kata" berupa angka (diawali digit). [WordSpan.requestColumn] selalu berada
     * DI DALAM kata supaya Jedi tidak menjawab untuk karakter di sebelahnya.
     */
    fun wordAt(lineText: String, column: Int): WordSpan? {
        val col = column.coerceIn(0, lineText.length)
        val onChar = col < lineText.length && LspCompletionUi.isIdentifierChar(lineText[col])
        val prevChar = col > 0 && LspCompletionUi.isIdentifierChar(lineText[col - 1])
        if (!onChar && !prevChar) return null
        val anchor = if (onChar) col else col - 1
        var s = anchor
        while (s > 0 && LspCompletionUi.isIdentifierChar(lineText[s - 1])) s--
        var e = anchor + 1
        while (e < lineText.length && LspCompletionUi.isIdentifierChar(lineText[e])) e++
        if (lineText[s].isDigit()) return null
        return WordSpan(s, e, col.coerceIn(s, e - 1))
    }

    /**
     * Saring dan putuskan. [exists] membuang path yang tidak ada di disk Android (peta path bisa menerjemahkan path yang
     * belum ada). Target di posisi kursor sendiri dibuang; bila hanya itu yang tersisa hasilnya [DefinitionOutcome.AtDefinition].
     * Urutan pilihan: berkas aktif dulu, lalu menurut path, baris, kolom.
     */
    fun classify(
        targets: List<LspDefinitionTarget>,
        currentPath: String,
        word: WordSpan,
        cursorLine: Int,
        rootDir: String?,
        exists: (String) -> Boolean = { true },
    ): DefinitionOutcome {
        if (targets.isEmpty()) return DefinitionOutcome.NotFound
        val visible = targets.filter { t -> t.hostPath != null && exists(t.hostPath) }
        if (visible.isEmpty()) {
            val first = targets.first().uri
            return DefinitionOutcome.OnlyInDebian(LspUri.toPath(first) ?: first, targets.size)
        }
        val others = visible.filter { t ->
            !(t.hostPath == currentPath && t.line == cursorLine && t.column >= word.start && t.column <= word.end)
        }
        if (others.isEmpty()) return DefinitionOutcome.AtDefinition
        val sorted = others
            .sortedWith(
                compareBy<LspDefinitionTarget> { if (it.hostPath == currentPath) 0 else 1 }
                    .thenBy { it.hostPath }.thenBy { it.line }.thenBy { it.column },
            )
            .distinctBy { Triple(it.hostPath, it.line, it.column) }
        if (sorted.size == 1) {
            val t = sorted[0]
            return DefinitionOutcome.Jump(t.hostPath!!, t.line, t.column)
        }
        return DefinitionOutcome.Choose(
            sorted.take(MAX_CHOICES).map { DefinitionChoice(it.hostPath!!, it.line, it.column, label(it.hostPath, it.line, rootDir)) },
        )
    }

    /** "relatif/ke/berkas.py:12" (baris basis 1) bila di bawah [rootDir], selain itu hanya nama berkas. */
    fun label(hostPath: String, line: Int, rootDir: String?): String {
        val root = rootDir?.trimEnd('/')
        val name = if (!root.isNullOrEmpty() && hostPath.startsWith("$root/")) hostPath.removePrefix("$root/") else hostPath.substringAfterLast('/')
        return name + ":" + (line + 1)
    }

    /**
     * [CODE-DEBLIB] Dipanggil bila [classify] hanya menemukan tujuan di dalam Debian. [resolve] mengubah path guest
     * jadi berkas di rootfs (null bila tidak ada). Hasil: Jump (1 berkas), Choose (beberapa, label "[Debian] path:baris"),
     * atau OnlyInDebian bila tak satu pun bisa dibuka.
     */
    fun classifyDebian(targets: List<LspDefinitionTarget>, resolve: (String) -> java.io.File?): DefinitionOutcome {
        val found = ArrayList<DefinitionChoice>()
        val seen = HashSet<Triple<String, Int, Int>>()
        for (t in targets) {
            val guest = LspUri.toPath(t.uri) ?: continue
            val f = resolve(guest) ?: continue
            if (!seen.add(Triple(f.path, t.line, t.column))) continue
            found.add(DefinitionChoice(f.path, t.line, t.column, labelDebian(guest, t.line)))
        }
        if (found.isEmpty()) {
            val first = targets.firstOrNull()?.uri
            return DefinitionOutcome.OnlyInDebian(first?.let { LspUri.toPath(it) ?: it } ?: "", targets.size)
        }
        found.sortWith(compareBy({ it.label }, { it.line }, { it.column }))
        if (found.size == 1) return DefinitionOutcome.Jump(found[0].hostPath, found[0].line, found[0].column)
        return DefinitionOutcome.Choose(found.take(MAX_CHOICES))
    }

    /** "[Debian] /usr/lib/python3/x.py:12" (baris basis 1). */
    fun labelDebian(guestPath: String, line: Int): String = "[Debian] " + guestPath + ":" + (line + 1)

    /** Pesan toast untuk hasil yang tidak berpindah; null bila hasilnya Jump/Choose. */
    fun message(outcome: DefinitionOutcome): String? = when (outcome) {
        is DefinitionOutcome.NotFound -> "Definisi tidak ditemukan"
        is DefinitionOutcome.AtDefinition -> "Kursor sudah di definisi"
        is DefinitionOutcome.OnlyInDebian -> "Definisi ada di Debian (${outcome.guestPath}), berkasnya tidak ditemukan di rootfs"
        is DefinitionOutcome.Jump, is DefinitionOutcome.Choose -> null
    }
}

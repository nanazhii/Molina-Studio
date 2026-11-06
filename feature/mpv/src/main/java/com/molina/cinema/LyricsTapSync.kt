package com.molina.cinema

import java.io.File

/*
 * [LYRICS-TAP] Inti editor tap-sync: lirik polos + ketukan pengguna saat lagu diputar -> LRC tersinkron.
 * Kotlin murni (tanpa Android) supaya bisa diuji lewat scripts/mpv-tests/run_tapsync.sh.
 * UI (dialog/tombol di pemutar) memanggil TapSession; penyimpanan lewat LyricsSaver.
 */

/** Penulis LRC. Keluaran selalu bisa dibaca balik oleh [LrcParser] (diuji round-trip). */
internal object LrcWriter {
    private const val MAX_MS = 999L * 60_000 + 59_999 // parser menerima menit hingga 3 digit

    private fun pad(v: Long, width: Int): String = v.toString().padStart(width, '0')

    /** "mm:ss.xx" (centisecond, dibulatkan). Tanpa String.format agar tidak bergantung locale. */
    fun formatTime(ms: Long): String {
        val cs = (ms.coerceIn(0L, MAX_MS) + 5) / 10
        return pad(cs / 6000, 2) + ":" + pad((cs / 100) % 60, 2) + "." + pad(cs % 100, 2)
    }

    private fun clean(s: String): String = s.replace('\r', ' ').replace('\n', ' ').trim()

    fun write(lines: List<LyricLine>, title: String? = null, artist: String? = null, album: String? = null): String =
        buildString {
            title?.let { clean(it).takeIf { v -> v.isNotEmpty() }?.let { v -> append("[ti:").append(v.replace("]", ")")).append("]\n") } }
            artist?.let { clean(it).takeIf { v -> v.isNotEmpty() }?.let { v -> append("[ar:").append(v.replace("]", ")")).append("]\n") } }
            album?.let { clean(it).takeIf { v -> v.isNotEmpty() }?.let { v -> append("[al:").append(v.replace("]", ")")).append("]\n") } }
            for (l in lines) append('[').append(formatTime(l.startMs)).append(']').append(clean(l.text)).append('\n')
        }
}

internal enum class TapReject { COMPLETE, EMPTY }

internal sealed class TapResult {
    class Ok(val index: Int, val stampMs: Long) : TapResult()
    class Rejected(val reason: TapReject) : TapResult()
}

/**
 * Sesi cap waktu. Kursor menunjuk baris berikutnya yang menunggu ketukan. Waktu ketukan dikurangi
 * [reactionMs] (kompensasi waktu reaksi) dan dijaga tidak mundur dari baris sebelumnya, jadi hasilnya
 * selalu terurut. Baris jeda (instrumental) bisa disisipkan di posisi kursor.
 */
internal class TapSession(source: List<String>, val reactionMs: Long = DEFAULT_REACTION_MS) {
    private class Entry(val text: String, var stamp: Long? = null, val isBreak: Boolean = false)

    private val entries = ArrayList<Entry>()
    private val history = ArrayList<Entry>() // urutan ketukan, untuk undo
    var cursor = 0
        private set

    init {
        for (s in source) {
            val t = s.replace('\r', ' ').replace('\n', ' ').trim()
            if (t.isNotEmpty() && entries.size < MAX_LINES) entries.add(Entry(t))
        }
    }

    val size: Int get() = entries.size
    val isComplete: Boolean get() = entries.isNotEmpty() && cursor >= entries.size
    val stampedCount: Int get() = cursor
    fun textOf(i: Int): String = entries.getOrNull(i)?.text ?: ""
    fun stampOf(i: Int): Long? = entries.getOrNull(i)?.stamp
    fun isBreak(i: Int): Boolean = entries.getOrNull(i)?.isBreak == true

    private fun floorMs(): Long = if (cursor > 0) entries[cursor - 1].stamp ?: 0L else 0L

    private fun stampFor(positionMs: Long): Long = maxOf(positionMs - reactionMs, floorMs(), 0L)

    fun tap(positionMs: Long): TapResult {
        if (entries.isEmpty()) return TapResult.Rejected(TapReject.EMPTY)
        if (cursor >= entries.size) return TapResult.Rejected(TapReject.COMPLETE)
        val e = entries[cursor]
        e.stamp = stampFor(positionMs)
        history.add(e)
        return TapResult.Ok(cursor++, e.stamp!!)
    }

    /** Sisipkan baris jeda (♪) bercap waktu tepat di posisi kursor; kursor maju satu. */
    fun tapBreak(positionMs: Long): TapResult {
        if (entries.size >= MAX_LINES) return TapResult.Rejected(TapReject.COMPLETE)
        val e = Entry("", stampFor(positionMs), isBreak = true)
        entries.add(cursor, e)
        history.add(e)
        return TapResult.Ok(cursor++, e.stamp!!)
    }

    /** Batalkan ketukan terakhir. Jeda yang disisipkan dihapus, baris lirik hanya dikosongkan capnya. */
    fun undo(): Boolean {
        val last = history.removeLastOrNull() ?: return false
        if (last.isBreak) entries.remove(last) else last.stamp = null
        cursor--
        return true
    }

    /** Mulai ulang dari [index]: cap pada baris itu dan sesudahnya dihapus, jeda sesudahnya dibuang. */
    fun rewindTo(index: Int) {
        val target = index.coerceIn(0, cursor)
        val it = entries.listIterator(target)
        while (it.hasNext()) { val e = it.next(); if (e.isBreak) it.remove() else e.stamp = null }
        history.removeAll { h -> h.stamp == null || h !in entries }
        cursor = target
    }

    /** Geser cap baris [index] sebesar [deltaMs], dijepit di antara tetangganya. False bila baris belum bercap. */
    fun nudge(index: Int, deltaMs: Long): Boolean {
        val e = entries.getOrNull(index) ?: return false
        val cur = e.stamp ?: return false
        val lo = if (index > 0) entries[index - 1].stamp ?: 0L else 0L
        val hi = entries.getOrNull(index + 1)?.stamp ?: Long.MAX_VALUE
        e.stamp = (cur + deltaMs).coerceIn(lo, hi)
        return true
    }

    /** Baris hasil. Hanya sah bila [isComplete]; null bila belum selesai. */
    fun toLines(): List<LyricLine>? =
        if (!isComplete) null else entries.map { LyricLine(it.stamp ?: 0L, it.text) }

    fun toLrc(title: String? = null, artist: String? = null, album: String? = null): String? =
        toLines()?.let { LrcWriter.write(it, title, artist, album) }

    companion object {
        const val DEFAULT_REACTION_MS = 150L
        const val MAX_LINES = 5_000
    }
}

/** Penyimpan LRC hasil tap-sync: tulis atomik, cadangkan lirik polos asli, tolak keluaran yang tidak terbaca. */
internal object LyricsSaver {
    sealed class Result {
        class Saved(val file: File, val backup: File?) : Result()
        class Failed(val reason: String) : Result()
    }

    /** Berkas lirik yang sudah ada, atau "<nama-lagu>.lrc" di folder lagu bila bisa ditulis, jika tidak di [userDir]. */
    fun targetFor(mediaPath: String, userDir: String = LyricsFiles.USER_DIR): File? {
        LyricsFiles.find(mediaPath, userDir)?.let { return it }
        val media = File(mediaPath)
        val name = media.nameWithoutExtension + ".lrc"
        if (name.length <= 4) return null
        val sibling = media.parentFile
        if (sibling != null && sibling.canWrite()) return File(sibling, name)
        val dir = File(userDir)
        return if (dir.isDirectory || dir.mkdirs()) File(dir, name) else null
    }

    fun save(mediaPath: String, content: String, userDir: String = LyricsFiles.USER_DIR): Result {
        if (!LrcParser.parse(content).synced) return Result.Failed("invalid_lrc")
        val target = targetFor(mediaPath, userDir) ?: return Result.Failed("no_target")
        val dir = target.parentFile ?: return Result.Failed("no_target")
        return try {
            var backup: File? = null
            if (target.exists()) {
                val bak = File(dir, target.name + ".bak")
                // cadangan pertama dipertahankan: lirik polos asli tidak hilang oleh simpan berulang
                if (!bak.exists()) { target.copyTo(bak); backup = bak }
            }
            val tmp = File(dir, "." + target.name + ".tmp")
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            Result.Saved(target, backup)
        } catch (e: java.io.IOException) {
            Result.Failed("io:" + (e.message ?: ""))
        }
    }
}

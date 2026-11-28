package com.molina.cinema

/*
 * [SUBTITLE-EDIT] Logika murni (tanpa Android) untuk Editor Subtitle: validasi, ubah/sisip/hapus cue,
 * parse/format timecode, deteksi tumpang tindih, dan sesi dengan undo/redo. Dipakai SubEditorDialog;
 * diuji di scripts/mpv-tests. Daftar cue selalu IMMUTABLE dan terurut menurut startMs.
 */

enum class EditError { BAD_TIME, END_BEFORE_START, EMPTY_TEXT, TOO_LONG, TOO_MANY, NO_SUCH_CUE }

internal sealed class EditResult {
    /** [index] = posisi cue yang baru saja diubah/disisipkan di dalam [cues] (-1 bila dihapus). */
    class Ok(val cues: List<SubCue>, val index: Int) : EditResult()
    class Error(val error: EditError) : EditResult()
}

internal object TimeCode {
    private val HMS = Regex("""^(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})(?:[.,](\d{1,3}))?$""")
    private val SECONDS = Regex("""^(\d{1,6})(?:[.,](\d{1,3}))?$""")
    const val MAX_MS = 359_999_999L // 99:59:59.999

    private fun frac(f: String?): Long = when (f?.length) { null, 0 -> 0L; 1 -> f.toLong() * 100; 2 -> f.toLong() * 10; else -> f.toLong() }

    /** "HH:MM:SS,mmm", "H:MM:SS.m", "MM:SS.mmm", atau detik polos "62.5". null bila bukan waktu valid. */
    fun parse(input: String): Long? {
        val s = input.trim()
        HMS.matchEntire(s)?.let { m ->
            val (h, mi, se, fr) = m.destructured
            val minutes = mi.toLong(); val seconds = se.toLong()
            if (minutes > 59 || seconds > 59) return null
            val ms = (if (h.isEmpty()) 0L else h.toLong()) * 3_600_000 + minutes * 60_000 + seconds * 1_000 + frac(fr.ifEmpty { null })
            return ms.takeIf { it <= MAX_MS }
        }
        SECONDS.matchEntire(s)?.let { m ->
            val ms = m.groupValues[1].toLong() * 1_000 + frac(m.groupValues[2].ifEmpty { null })
            return ms.takeIf { it <= MAX_MS }
        }
        return null
    }

    fun format(ms: Long): String {
        val t = ms.coerceIn(0, MAX_MS)
        return "%02d:%02d:%02d.%03d".format(t / 3_600_000, t / 60_000 % 60, t / 1_000 % 60, t % 1_000)
    }
}

internal object SubEdit {
    const val MAX_CUES = 50_000
    const val MAX_TEXT = 2_000
    const val DEFAULT_DURATION_MS = 2_000L

    private fun sorted(cues: List<SubCue>) = cues.sortedWith(compareBy<SubCue> { it.startMs }.thenBy { it.endMs })

    /** Normalisasi teks dari kolom isian: CRLF -> LF, baris kosong di tepi dibuang. */
    fun cleanText(raw: String): String = raw.replace("\r\n", "\n").replace('\r', '\n').trim('\n', ' ', '\t')

    fun replace(cues: List<SubCue>, index: Int, startMs: Long, endMs: Long, text: String): EditResult {
        if (index !in cues.indices) return EditResult.Error(EditError.NO_SUCH_CUE)
        val clean = cleanText(text)
        return when {
            startMs < 0 || endMs < 0 || startMs > TimeCode.MAX_MS || endMs > TimeCode.MAX_MS -> EditResult.Error(EditError.BAD_TIME)
            endMs < startMs -> EditResult.Error(EditError.END_BEFORE_START)
            clean.isEmpty() -> EditResult.Error(EditError.EMPTY_TEXT)
            clean.length > MAX_TEXT -> EditResult.Error(EditError.TOO_LONG)
            else -> {
                val target = SubCue(startMs, endMs, clean)
                val next = sorted(cues.toMutableList().also { it[index] = target })
                EditResult.Ok(next, next.indexOfFirst { it === target })
            }
        }
    }

    fun delete(cues: List<SubCue>, index: Int): EditResult {
        if (index !in cues.indices) return EditResult.Error(EditError.NO_SUCH_CUE)
        return EditResult.Ok(cues.filterIndexed { i, _ -> i != index }, -1)
    }

    /** Sisipkan cue baru di [startMs] (durasi [durationMs]); teks tidak boleh kosong. */
    fun insert(cues: List<SubCue>, startMs: Long, text: String, durationMs: Long = DEFAULT_DURATION_MS): EditResult {
        if (cues.size >= MAX_CUES) return EditResult.Error(EditError.TOO_MANY)
        val clean = cleanText(text)
        return when {
            startMs < 0 || startMs > TimeCode.MAX_MS -> EditResult.Error(EditError.BAD_TIME)
            clean.isEmpty() -> EditResult.Error(EditError.EMPTY_TEXT)
            clean.length > MAX_TEXT -> EditResult.Error(EditError.TOO_LONG)
            else -> {
                val target = SubCue(startMs, (startMs + durationMs).coerceAtMost(TimeCode.MAX_MS), clean)
                val next = sorted(cues + target)
                EditResult.Ok(next, next.indexOfFirst { it === target })
            }
        }
    }

    /** Geser awal dan/atau akhir satu cue; hasil dijepit ke >= 0 dan end >= start. */
    fun nudge(cues: List<SubCue>, index: Int, deltaStartMs: Long, deltaEndMs: Long): EditResult {
        val c = cues.getOrNull(index) ?: return EditResult.Error(EditError.NO_SUCH_CUE)
        val s = (c.startMs + deltaStartMs).coerceIn(0, TimeCode.MAX_MS)
        val e = (c.endMs + deltaEndMs).coerceIn(0, TimeCode.MAX_MS)
        return replace(cues, index, s, maxOf(s, e), c.text)
    }

    /** Indeks cue yang sedang tampil pada [positionMs]; bila tidak ada, cue terakhir sebelum posisi (atau 0). -1 bila kosong. */
    fun cueAt(cues: List<SubCue>, positionMs: Long): Int {
        if (cues.isEmpty()) return -1
        var lo = 0; var hi = cues.size - 1; var best = 0
        while (lo <= hi) { // terakhir dengan startMs <= posisi
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= positionMs) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }

    /** Indeks cue yang mulai sebelum cue sebelumnya selesai (tumpang tindih). */
    fun overlaps(cues: List<SubCue>): Set<Int> {
        val out = HashSet<Int>()
        var maxEnd = Long.MIN_VALUE
        cues.forEachIndexed { i, c ->
            if (i > 0 && c.startMs < maxEnd) out.add(i)
            if (c.endMs > maxEnd) maxEnd = c.endMs
        }
        return out
    }

    /** Cue berikutnya yang teksnya memuat [query] (abaikan huruf besar/kecil), mulai dari [from] (inklusif), memutar ke awal. -1 bila tidak ada. */
    fun find(cues: List<SubCue>, query: String, from: Int): Int {
        val q = query.trim()
        if (q.isEmpty() || cues.isEmpty()) return -1
        val n = cues.size
        val start = from.coerceIn(0, n - 1)
        for (k in 0 until n) {
            val i = (start + k) % n
            if (cues[i].text.contains(q, ignoreCase = true)) return i
        }
        return -1
    }
}

/** Sesi penyuntingan: riwayat undo/redo (daftar immutable berbagi struktur, jadi murah) dan status "belum disimpan". */
internal class SubEditSession(initial: List<SubCue>, private val maxHistory: Int = 200) {
    private var baseline: List<SubCue> = initial.toList()
    private val undoStack = ArrayDeque<List<SubCue>>()
    private val redoStack = ArrayDeque<List<SubCue>>()

    var cues: List<SubCue> = initial.toList()
        private set

    val dirty: Boolean get() = cues != baseline

    /** Tandai keadaan sekarang sebagai sudah disimpan (riwayat undo tetap). */
    fun markSaved() { baseline = cues }
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Terapkan hasil operasi. Mengembalikan indeks cue yang berubah, atau null (dan galat di [lastError]) bila ditolak. */
    var lastError: EditError? = null
        private set

    fun apply(result: EditResult): Int? = when (result) {
        is EditResult.Error -> { lastError = result.error; null }
        is EditResult.Ok -> {
            lastError = null
            undoStack.addLast(cues)
            while (undoStack.size > maxHistory) undoStack.removeFirst()
            redoStack.clear()
            cues = result.cues
            result.index
        }
    }

    fun undo(): Boolean {
        val prev = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(cues); cues = prev; return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(cues); cues = next; return true
    }
}

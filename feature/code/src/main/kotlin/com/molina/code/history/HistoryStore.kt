package com.molina.code.history

import java.io.File
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// [CODE-HISTORY] Riwayat versi lokal per berkas. Kotlin murni (tanpa Android) agar bisa diuji di JVM:
// scripts/code-tests/run_history.sh. Dipakai CodeSession.writeTab dan HistoryDialog.

enum class HistoryKind(val code: Char, val label: String) {
    SAVED('s', "Disimpan"),
    BEFORE('b', "Sebelum simpan");

    companion object {
        fun of(c: Char): HistoryKind? = entries.firstOrNull { it.code == c }
    }
}

/** Satu versi. [name] = nama berkas snapshot; semua metadata dibaca dari nama (tanpa berkas indeks yang bisa rusak). */
class HistoryVersion(
    val name: String,
    val time: Long,
    val kind: HistoryKind,
    val fp: String,
    val chars: Int,
    val lines: Int,
    val bytes: Long,
    /** [CODE-HISTORY2] Versi disematkan: tidak ikut dipangkas otomatis (batas [HistoryStore.MAX_PINS] per berkas). */
    val pinned: Boolean = false
)

/**
 * Penyimpanan snapshot teks (LF) per berkas di bawah [root]: satu folder per berkas (hash path kanonik),
 * satu .gz per versi bernama `<ms>_<jenis>_<sidikjari>_<karakter>_<baris>.gz`. Best-effort: galat I/O
 * tidak pernah dilempar ke pemanggil (record mengembalikan null). Semua fungsi memblokir: panggil dari thread IO.
 */
class HistoryStore(private val root: File, private val clock: () -> Long = { System.currentTimeMillis() }) {

    /**
     * Catat [text] sebagai versi baru [kind]. Null bila sama dengan versi terbaru (tanpa duplikat),
     * terlalu besar, atau gagal. Waktu dijamin naik terus per berkas walau jam sistem mundur.
     */
    @Synchronized
    fun record(file: File, text: String, kind: HistoryKind): HistoryVersion? {
        if (text.length > MAX_CHARS) return null
        return try {
            val dir = dirFor(file)
            val fp = sha1(text.toByteArray(Charsets.UTF_8)).take(12)
            val latest = listIn(dir).firstOrNull()
            if (latest != null && latest.fp == fp) return null
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return null
            val marker = File(dir, "path.txt")
            if (!marker.isFile) marker.writeText(file.path, Charsets.UTF_8)
            val time = maxOf(clock(), (latest?.time ?: 0L) + 1)
            val lines = countLines(text)
            val name = String.format(Locale.US, "%013d_%c_%s_%d_%d.gz", time, kind.code, fp, text.length, lines)
            val tmp = File(dir, "$name.tmp")
            GZIPOutputStream(tmp.outputStream().buffered()).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            val target = File(dir, name)
            if (!tmp.renameTo(target)) { tmp.delete(); return null }
            prune(dir)
            HistoryVersion(name, time, kind, fp, text.length, lines, target.length())
        } catch (e: Exception) {
            null
        }
    }

    /** Versi [file], terbaru dulu. */
    @Synchronized
    fun list(file: File): List<HistoryVersion> = listIn(dirFor(file))

    /** Isi versi (LF), atau null bila berkas snapshot hilang/rusak. */
    @Synchronized
    fun read(file: File, v: HistoryVersion): String? = try {
        val f = File(dirFor(file), v.name)
        if (!f.isFile) null else GZIPInputStream(f.inputStream().buffered()).use { String(it.readBytes(), Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    /**
     * [CODE-HISTORY2] Sematkan/lepas sematan [v]. Penanda disimpan di `pins.txt` (satu nama snapshot per baris),
     * ditulis atomik. False bila snapshot tidak ada, batas [MAX_PINS] tercapai, atau gagal menulis.
     */
    @Synchronized
    fun setPin(file: File, v: HistoryVersion, pin: Boolean): Boolean = try {
        val dir = dirFor(file)
        if (!File(dir, v.name).isFile) false else {
            val live = listIn(dir).filter { it.pinned }.map { it.name }.toMutableSet()
            if (pin && v.name !in live && live.size >= MAX_PINS) false else {
                if (pin) live.add(v.name) else live.remove(v.name)
                writePins(dir, live)
                true
            }
        }
    } catch (e: Exception) {
        false
    }

    /** Hapus seluruh riwayat [file]. */
    @Synchronized
    fun clear(file: File) {
        runCatching { dirFor(file).deleteRecursively() }
    }

    /**
     * Pangkas seluruh penyimpanan ke [maxBytes] dengan membuang versi tertua lintas berkas, lalu folder yang kosong.
     * Dipanggil sekali per sesi di thread IO.
     */
    @Synchronized
    fun trimGlobal(maxBytes: Long) {
        try {
            val dirs = root.listFiles { f -> f.isDirectory && f.name.startsWith("h") } ?: return
            val all = ArrayList<Pair<File, HistoryVersion>>()
            var total = 0L
            for (d in dirs) for (v in listIn(d)) { all.add(Pair(d, v)); total += v.bytes }
            // [CODE-HISTORY2] versi tanpa sematan dibuang dulu (tertua dulu); yang disematkan hanya bila masih melebihi batas
            all.sortWith(compareBy({ it.second.pinned }, { it.second.time }))
            var i = 0
            while (total > maxBytes && i < all.size) {
                val (d, v) = all[i++]
                if (File(d, v.name).delete()) total -= v.bytes
            }
            for (d in dirs) if (listIn(d).isEmpty()) d.deleteRecursively()
        } catch (e: Exception) {
            // best-effort
        }
    }

    private fun dirFor(file: File): File {
        val key = try { file.canonicalPath } catch (e: Exception) { file.absolutePath }
        return File(root, "h" + sha1(key.toByteArray(Charsets.UTF_8)).take(16))
    }

    private fun listIn(dir: File): List<HistoryVersion> {
        val arr = dir.listFiles { f -> f.isFile && f.name.endsWith(".gz") } ?: return emptyList()
        val out = ArrayList<HistoryVersion>(arr.size)
        val pins = readPins(dir)
        for (f in arr) {
            val p = f.name.removeSuffix(".gz").split('_')
            if (p.size != 5) continue
            val time = p[0].toLongOrNull() ?: continue
            val kind = p[1].singleOrNull()?.let { HistoryKind.of(it) } ?: continue
            val chars = p[3].toIntOrNull() ?: continue
            val lines = p[4].toIntOrNull() ?: continue
            out.add(HistoryVersion(f.name, time, kind, p[2], chars, lines, f.length(), f.name in pins))
        }
        out.sortByDescending { it.time }
        return out
    }

    private fun prune(dir: File) {
        val vs = listIn(dir)
        var bytes = vs.sumOf { it.bytes }
        var unpinned = vs.count { !it.pinned }
        for (i in vs.indices.reversed()) { // tertua dulu; versi terbaru dan yang disematkan tidak pernah dibuang
            val v = vs[i]
            if (i == 0) break
            if (v.pinned) continue
            if (unpinned > MAX_VERSIONS || bytes > MAX_DIR_BYTES) {
                if (File(dir, v.name).delete()) { bytes -= v.bytes; unpinned-- }
            } else break
        }
    }

    private fun readPins(dir: File): Set<String> = try {
        val f = File(dir, PINS_FILE)
        if (f.isFile) f.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()
    } catch (e: Exception) {
        emptySet()
    }

    private fun writePins(dir: File, names: Set<String>) {
        val f = File(dir, PINS_FILE)
        if (names.isEmpty()) { f.delete(); return }
        val tmp = File(dir, "$PINS_FILE.tmp")
        tmp.writeText(names.sorted().joinToString("\n") + "\n", Charsets.UTF_8)
        if (!tmp.renameTo(f)) { tmp.delete(); throw java.io.IOException("pins") }
    }

    companion object {
        const val MAX_CHARS = 2_000_000
        const val MAX_VERSIONS = 60
        /** [CODE-HISTORY2] Versi yang boleh disematkan per berkas (di luar [MAX_VERSIONS]). */
        const val MAX_PINS = 10
        private const val PINS_FILE = "pins.txt"
        const val MAX_DIR_BYTES = 8L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 100L * 1024 * 1024

        fun countLines(text: String): Int =
            if (text.isEmpty()) 0 else text.count { it == '\n' } + (if (text.endsWith('\n')) 0 else 1)

        private fun sha1(b: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }
    }
}

/** Format tampilan untuk daftar riwayat. */
object HistoryFormat {
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")

    /** "Hari ini 14:32", "Kemarin 09:05", "29 Okt 14:32", atau "29 Okt 2025 14:32" bila beda tahun. */
    fun time(t: Long, now: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = t }
        val n = Calendar.getInstance(tz).apply { timeInMillis = now }
        val hm = String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val dayT = Math.floorDiv(t + tz.getOffset(t), 86_400_000L)
        val dayN = Math.floorDiv(now + tz.getOffset(now), 86_400_000L)
        return when {
            dayT == dayN -> "Hari ini $hm"
            dayT == dayN - 1 -> "Kemarin $hm"
            else -> {
                val d = c.get(Calendar.DAY_OF_MONTH).toString() + " " + MONTHS[c.get(Calendar.MONTH)]
                (if (c.get(Calendar.YEAR) != n.get(Calendar.YEAR)) d + " " + c.get(Calendar.YEAR) else d) + " " + hm
            }
        }
    }

    /** "850 B", "1,2 KB", "2,0 MB". Dipakai dengan jumlah karakter sebagai perkiraan ukuran teks (bukan ukuran .gz). */
    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0).replace('.', ',')
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0)).replace('.', ',')
    }
}

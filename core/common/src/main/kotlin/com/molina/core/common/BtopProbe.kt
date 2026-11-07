package com.molina.core.common

import java.io.File
import kotlin.math.roundToInt

/** [BTOP-MONITOR] Pembacaan /proc untuk Monitor ala btop. Murni JVM (java.io), bisa diuji tanpa Android. */

/** Isi /proc/meminfo (KB). [availableKb] = MemAvailable; bila tak ada, dihitung dari Free + Buffers + Cached. */
data class MemInfo(
    val totalKb: Long,
    val availableKb: Long,
    val buffersKb: Long,
    val cachedKb: Long,
    val swapTotalKb: Long,
    val swapFreeKb: Long,
) {
    val usedKb: Long get() = (totalKb - availableKb).coerceAtLeast(0L)
    val swapUsedKb: Long get() = (swapTotalKb - swapFreeKb).coerceAtLeast(0L)
}

data class LoadAvg(val one: Float, val five: Float, val fifteen: Float)

/** Waktu CPU kumulatif satu inti dari /proc/stat (satuan clock tick). */
data class CoreTimes(val busy: Long, val total: Long)

/** Bidang /proc/<pid>/stat yang dipakai btop: [ticks] = utime+stime. */
data class ProcStat(val ticks: Long, val nice: Int, val threads: Int, val startTicks: Long)

/** Satu baris btop: [cpu] dalam persen, 100 = satu inti penuh. [pssKb] < 0 berarti belum diketahui. */
data class BtopRow(
    val pid: Int,
    val ppid: Int,
    val name: String,
    val cmdline: String,
    val state: Char,
    val threads: Int,
    val nice: Int,
    val rssKb: Long,
    val pssKb: Long,
    val cpu: Float,
    val runtimeSec: Long,
    val group: TaskGroup,
    val isSelf: Boolean,
) {
    /** PSS (membagi halaman bersama secara adil) bila ada, jika tidak RSS. */
    val memKb: Long get() = if (pssKb >= 0L) pssKb else rssKb
}

/** Baris yang sudah diratakan untuk mode pohon; [prefix] berisi garis cabang ("├─ ", "└─ "). */
data class BtopTreeLine(val row: BtopRow, val depth: Int, val prefix: String)

enum class BtopSort { Pid, Name, Cpu, Memory, Threads }

object BtopProbe {
    private fun fields(text: String, key: String): Long? {
        for (line in text.lineSequence()) {
            if (line.startsWith("$key:")) {
                return line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
            }
        }
        return null
    }

    fun parseMemInfo(text: String): MemInfo? {
        val total = fields(text, "MemTotal") ?: return null
        val free = fields(text, "MemFree") ?: 0L
        val buffers = fields(text, "Buffers") ?: 0L
        val cached = fields(text, "Cached") ?: 0L
        val avail = fields(text, "MemAvailable") ?: (free + buffers + cached)
        return MemInfo(
            totalKb = total,
            availableKb = avail.coerceIn(0L, total),
            buffersKb = buffers,
            cachedKb = cached,
            swapTotalKb = fields(text, "SwapTotal") ?: 0L,
            swapFreeKb = fields(text, "SwapFree") ?: 0L,
        )
    }

    fun parseLoadAvg(text: String): LoadAvg? {
        val f = text.trim().split(Regex("\\s+"))
        if (f.size < 3) return null
        return LoadAvg(
            f[0].toFloatOrNull() ?: return null,
            f[1].toFloatOrNull() ?: return null,
            f[2].toFloatOrNull() ?: return null,
        )
    }

    /** Detik sejak boot dari isi /proc/uptime. */
    fun parseUptimeSec(text: String): Double? = text.trim().substringBefore(' ').toDoubleOrNull()

    /** Waktu CPU per inti (baris cpu0, cpu1, ...). Kosong bila /proc/stat tak terbaca atau tak berisi inti. */
    fun parseCpuStat(text: String): List<CoreTimes> {
        val out = ArrayList<CoreTimes>()
        for (line in text.lineSequence()) {
            if (!line.startsWith("cpu") || line.length < 4 || !line[3].isDigit()) continue
            val f = line.split(Regex("\\s+")).drop(1).map { it.toLongOrNull() ?: 0L }
            if (f.size < 4) continue
            // user nice system idle iowait irq softirq steal
            val idle = f[3] + f.getOrElse(4) { 0L }
            val busy = f[0] + f[1] + f[2] + f.getOrElse(5) { 0L } + f.getOrElse(6) { 0L } + f.getOrElse(7) { 0L }
            out.add(CoreTimes(busy, busy + idle))
        }
        return out
    }

    /** Aman untuk nama proses berspasi/berkurung. Bidang dihitung dari setelah ')' terakhir. */
    fun parseProcStat(stat: String): ProcStat? {
        val after = stat.substringAfterLast(')', "").trim()
        if (after.isEmpty()) return null
        val f = after.split(' ')
        if (f.size < 20) return null
        val u = f[11].toLongOrNull() ?: return null
        val s = f[12].toLongOrNull() ?: return null
        return ProcStat(
            ticks = u + s,
            nice = f[16].toIntOrNull() ?: 0,
            threads = f[17].toIntOrNull() ?: 1,
            startTicks = f[19].toLongOrNull() ?: 0L,
        )
    }

    fun readProcStat(pid: Int, root: File = File("/proc")): ProcStat? =
        try { parseProcStat(File(root, "$pid/stat").readText()) } catch (e: Exception) { null }

    /** Jumlah descriptor terbuka; null bila tak terbaca. */
    fun fdCount(pid: Int, root: File = File("/proc")): Int? =
        try { File(root, "$pid/fd").list()?.size } catch (e: Exception) { null }

    /** Lama proses berjalan (detik) dari starttime (tick sejak boot) dan uptime sistem. */
    fun runtimeSec(startTicks: Long, uptimeSec: Double, ticksPerSec: Int): Long =
        (uptimeSec - startTicks.toDouble() / ticksPerSec.coerceAtLeast(1)).toLong().coerceAtLeast(0L)

    /** "1j 05m", "3m 12d", "45d" untuk tampilan ringkas. */
    fun runtimeText(sec: Long): String = when {
        sec >= 3600L -> "${sec / 3600}j ${"%02d".format((sec % 3600) / 60)}m"
        sec >= 60L -> "${sec / 60}m ${"%02d".format(sec % 60)}d"
        else -> "${sec}d"
    }

    /** Urutkan; seri diputus PID agar urutan stabil antar-sampel. */
    fun sort(rows: List<BtopRow>, key: BtopSort, descending: Boolean): List<BtopRow> {
        val base: Comparator<BtopRow> = when (key) {
            BtopSort.Pid -> compareBy<BtopRow> { it.pid }
            BtopSort.Name -> compareBy<BtopRow> { it.name.lowercase() }
            BtopSort.Cpu -> compareBy<BtopRow> { it.cpu }
            BtopSort.Memory -> compareBy<BtopRow> { it.memKb }
            BtopSort.Threads -> compareBy<BtopRow> { it.threads }
        }
        val dir = if (descending) base.reversed() else base
        return rows.sortedWith(dir.thenBy { it.pid })
    }

    /** Saring nama, cmdline, atau PID (huruf besar/kecil tak dibedakan). Kueri kosong = semua. */
    fun filter(rows: List<BtopRow>, query: String): List<BtopRow> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return rows
        return rows.filter { it.name.lowercase().contains(q) || it.cmdline.lowercase().contains(q) || it.pid.toString() == q }
    }

    /**
     * Ratakan jadi pohon induk-anak. Saudara diurutkan memakai [key]/[descending]. Proses yang induknya tidak
     * ada di daftar menjadi akar. Aman dari siklus PID (dibatasi kedalaman 64 dan dikunjungi sekali).
     */
    fun tree(rows: List<BtopRow>, key: BtopSort, descending: Boolean): List<BtopTreeLine> {
        val pids = rows.mapTo(HashSet()) { it.pid }
        val kids = HashMap<Int, MutableList<BtopRow>>()
        val roots = ArrayList<BtopRow>()
        for (r in rows) {
            if (r.ppid != r.pid && r.ppid in pids) kids.getOrPut(r.ppid) { ArrayList() }.add(r) else roots.add(r)
        }
        val out = ArrayList<BtopTreeLine>(rows.size)
        val seen = HashSet<Int>()
        fun walk(r: BtopRow, depth: Int, ancestorsLast: List<Boolean>, isLast: Boolean) {
            if (depth > 64 || !seen.add(r.pid)) return
            val prefix = if (depth == 0) "" else
                ancestorsLast.drop(1).joinToString("") { if (it) "   " else "\u2502  " } +
                    (if (isLast) "\u2514\u2500 " else "\u251C\u2500 ")
            out.add(BtopTreeLine(r, depth, prefix))
            val children = sort(kids[r.pid].orEmpty(), key, descending)
            children.forEachIndexed { i, c -> walk(c, depth + 1, ancestorsLast + isLast, i == children.lastIndex) }
        }
        val rootsSorted = sort(roots, key, descending)
        rootsSorted.forEachIndexed { i, r -> walk(r, 0, emptyList(), i == rootsSorted.lastIndex) }
        // Siklus murni (tak ada akar) tetap ditampilkan agar tak ada proses yang hilang.
        for (r in rows) if (r.pid !in seen) walk(r, 0, emptyList(), true)
        return out
    }
}

/**
 * CPU% per PID dari selisih tick antar sampel, dengan desimal. 100 = satu inti penuh (seperti btop/top);
 * bagi dengan jumlah inti untuk skala "seluruh kapasitas". Sampel pertama menghasilkan peta kosong.
 */
class ProcCpuTracker(private val ticksPerSec: Int = 100) {
    private var prevNanos: Long? = null
    private var prev: Map<Int, Long> = emptyMap()

    fun update(nowNanos: Long, ticks: Map<Int, Long>): Map<Int, Float> {
        val out = HashMap<Int, Float>()
        val before = prevNanos
        if (before != null && nowNanos > before) {
            val dtSec = (nowNanos - before) / 1_000_000_000.0
            val capacity = dtSec * ticksPerSec
            for ((pid, t) in ticks) {
                val p = prev[pid] ?: continue
                val d = (t - p).coerceAtLeast(0L)
                out[pid] = ((d / capacity * 1000.0).roundToInt() / 10f).coerceAtLeast(0f)
            }
        }
        prevNanos = nowNanos
        prev = HashMap(ticks)
        return out
    }
}

/** Pemakaian per inti (0..100) dari selisih [CoreTimes]. Sampel pertama menghasilkan daftar kosong. */
class CoreCpuTracker {
    private var prev: List<CoreTimes> = emptyList()

    fun update(now: List<CoreTimes>): List<Float> {
        val before = prev
        prev = now
        if (before.size != now.size || now.isEmpty()) return emptyList()
        return now.indices.map { i ->
            val dt = now[i].total - before[i].total
            val db = now[i].busy - before[i].busy
            if (dt <= 0L) 0f else (db.toDouble() / dt * 100.0).toFloat().coerceIn(0f, 100f)
        }
    }
}

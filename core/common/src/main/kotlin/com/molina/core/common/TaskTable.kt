package com.molina.core.common

import java.io.File
import kotlin.math.roundToInt

/** [TASK-MANAGER] Kelompok proses di Pengelola Tugas. Heuristik berdasarkan nama/cmdline dan pewarisan dari induk. */
enum class TaskGroup(val label: String) {
    App("Aplikasi Molina"),
    Terminal("Terminal"),
    Unduhan("Unduhan"),
    Code("Code"),
    Lainnya("Lainnya"),
}

/** Satu baris tabel proses. [cpuPercent] sudah dinormalisasi per inti (0..100). */
data class TaskRow(
    val pid: Int,
    val ppid: Int,
    val name: String,
    val cmdline: String,
    val state: Char,
    val rssKb: Long,
    val cpuPercent: Int,
    val group: TaskGroup,
    val isSelf: Boolean,
)

enum class TaskSort { Name, Pid, Cpu, Memory }

/** Pembacaan /proc tambahan untuk Pengelola Tugas. Murni JVM (java.io), bisa diuji tanpa Android. */
object TaskProbe {
    /** utime + stime (clock tick) dari isi /proc/<pid>/stat; null bila rusak. Aman untuk nama proses berspasi/berkurung. */
    fun parseTicks(stat: String): Long? {
        val after = stat.substringAfterLast(')', "").trim()
        if (after.isEmpty()) return null
        val f = after.split(' ')
        if (f.size < 13) return null
        val u = f[11].toLongOrNull() ?: return null
        val s = f[12].toLongOrNull() ?: return null
        return u + s
    }

    /** Argumen dari isi /proc/<pid>/cmdline (dipisah NUL). */
    fun parseCmdline(raw: String): List<String> = raw.split('\u0000').filter { it.isNotEmpty() }

    fun cpuTicks(pid: Int, root: File = File("/proc")): Long? =
        try { parseTicks(File(root, "$pid/stat").readText()) } catch (e: Exception) { null }

    fun cmdline(pid: Int, root: File = File("/proc")): List<String> =
        try { parseCmdline(File(root, "$pid/cmdline").readText()) } catch (e: Exception) { emptyList() }
}

/**
 * Hitung CPU% per PID dari selisih tick di antara dua sampel. Dinormalisasi per inti seperti Task Manager
 * desktop. Sampel pertama (belum ada pembanding) menghasilkan peta kosong.
 */
class CpuTracker(private val cores: Int, private val ticksPerSec: Int = 100) {
    private var prevNanos: Long? = null
    private var prev: Map<Int, Long> = emptyMap()

    fun update(nowNanos: Long, ticks: Map<Int, Long>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        val before = prevNanos
        if (before != null && nowNanos > before) {
            val dtSec = (nowNanos - before) / 1_000_000_000.0
            val capacity = dtSec * ticksPerSec * cores.coerceAtLeast(1)
            for ((pid, t) in ticks) {
                val p = prev[pid] ?: continue
                val d = (t - p).coerceAtLeast(0L)
                out[pid] = (d / capacity * 100.0).roundToInt().coerceIn(0, 100)
            }
        }
        prevNanos = nowNanos
        prev = HashMap(ticks)
        return out
    }
}

object TaskTable {
    private val TERMINAL = setOf("proot", "bash", "sh", "dash", "zsh", "fish", "login", "su", "busybox", "tmux", "screen")
    private val DOWNLOAD = setOf("ffmpeg", "ffprobe", "aria2c")
    private val CODE_PREFIX = listOf("python", "node", "java", "ruby", "perl", "lua", "gcc", "g++", "make", "cmake", "rustc", "cargo")
    private val INTERPRETERS = setOf("sh", "bash", "dash", "zsh", "node", "ruby", "perl", "lua")

    /** Kelompok menurut nama/cmdline saja; null bila tidak dikenal (nanti mewarisi induk). */
    fun byName(name: String, args: List<String>): TaskGroup? {
        val n = name.lowercase()
        val joined = args.joinToString(" ").lowercase()
        return when {
            n.startsWith("yt-dlp") || "yt-dlp" in joined || "yt_dlp" in joined || n in DOWNLOAD -> TaskGroup.Unduhan
            n in TERMINAL -> TaskGroup.Terminal
            CODE_PREFIX.any { n.startsWith(it) } -> TaskGroup.Code
            else -> null
        }
    }

    /** Nama tampil ala "Image Name": basename argv[0]; untuk interpreter, nama skripnya. Cadangan: nama dari status. */
    fun displayName(statusName: String, args: List<String>): String {
        val argv0 = args.firstOrNull()?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: return statusName
        val l = argv0.lowercase()
        if (l.startsWith("python") || l in INTERPRETERS) {
            val a1 = args.getOrNull(1)
            if (a1 != null && !a1.startsWith("-")) return a1.substringAfterLast('/').ifEmpty { argv0 }
        }
        return argv0
    }

    /** Baris untuk semua proses hidup milik [uid]. Proses [selfPid] masuk kelompok App; yang tak dikenal mewarisi induknya. */
    fun build(
        procs: List<ProcInfo>,
        selfPid: Int,
        uid: Int,
        cmdlines: Map<Int, List<String>>,
        cpu: Map<Int, Int>,
    ): List<TaskRow> {
        val mine = procs.filter { it.uid == uid && it.alive }
        val byPid = mine.associateBy { it.pid }
        val memo = HashMap<Int, TaskGroup>()

        fun groupOf(p: ProcInfo, depth: Int): TaskGroup {
            memo[p.pid]?.let { return it }
            val args = cmdlines[p.pid].orEmpty()
            val g = when {
                p.pid == selfPid -> TaskGroup.App
                else -> byName(p.name, args) ?: run {
                    val parent = byPid[p.ppid]
                    if (parent == null || parent.pid == selfPid || depth > 32) TaskGroup.Lainnya
                    else groupOf(parent, depth + 1)
                }
            }
            memo[p.pid] = g
            return g
        }

        return mine.map { p ->
            val args = cmdlines[p.pid].orEmpty()
            TaskRow(
                pid = p.pid,
                ppid = p.ppid,
                name = displayName(p.name, args),
                cmdline = args.joinToString(" ").take(200),
                state = p.state,
                rssKb = p.rssKb,
                cpuPercent = cpu[p.pid] ?: 0,
                group = groupOf(p, 0),
                isSelf = p.pid == selfPid,
            )
        }
    }

    /** Urutkan; seri diputus PID agar urutan stabil antar-sampel. */
    fun sort(rows: List<TaskRow>, key: TaskSort, descending: Boolean): List<TaskRow> {
        val base: Comparator<TaskRow> = when (key) {
            TaskSort.Name -> compareBy<TaskRow> { it.name.lowercase() }
            TaskSort.Pid -> compareBy<TaskRow> { it.pid }
            TaskSort.Cpu -> compareBy<TaskRow> { it.cpuPercent }
            TaskSort.Memory -> compareBy<TaskRow> { it.rssKb }
        }
        val dir = if (descending) base.reversed() else base
        return rows.sortedWith(dir.thenBy { it.pid })
    }

    fun totalCpu(rows: List<TaskRow>): Int = rows.sumOf { it.cpuPercent }.coerceAtMost(100)
    fun totalRssKb(rows: List<TaskRow>): Long = rows.sumOf { it.rssKb }
}

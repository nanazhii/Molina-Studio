package com.molina.core.common

import java.io.File

/** Satu proses Linux. [state] 'Z' = zombie (sudah mati, hanya menunggu di-wait induknya). */
data class ProcInfo(
    val pid: Int,
    val ppid: Int,
    val uid: Int,
    val name: String,
    val state: Char = 'S',
    val rssKb: Long = 0L,
) {
    val alive: Boolean get() = state != 'Z' && state != 'X'
}

/** Pembacaan /proc. Murni JVM (java.io), jadi bisa diuji tanpa Android. */
object ProcFs {
    /** Urai isi /proc/<pid>/status; null bila rusak. */
    fun parse(pid: Int, status: String): ProcInfo? {
        var name: String? = null
        var ppid: Int? = null
        var uid: Int? = null
        var state = 'S'
        var rss = 0L
        for (line in status.lineSequence()) {
            val key = line.substringBefore(':', "")
            val v = line.substringAfter(':', "").trim()
            when (key) {
                "Name" -> name = v
                "State" -> state = v.firstOrNull() ?: 'S'
                "PPid" -> ppid = v.toIntOrNull()
                "Uid" -> uid = v.split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
                "VmRSS" -> rss = v.substringBefore(' ').toLongOrNull() ?: 0L
            }
        }
        return ProcInfo(pid, ppid ?: return null, uid ?: return null, name ?: return null, state, rss)
    }

    /** Semua proses yang terbaca; proses yang hilang di tengah pembacaan dilewati. */
    fun list(root: File = File("/proc")): List<ProcInfo> {
        val dirs = root.listFiles() ?: return emptyList()
        val out = ArrayList<ProcInfo>()
        for (d in dirs) {
            val pid = d.name.toIntOrNull() ?: continue
            val text = try { File(d, "status").readText() } catch (e: Exception) { continue }
            parse(pid, text)?.let(out::add)
        }
        return out
    }
}

/** Akses proses; implementasi Android memakai Os.kill. */
interface ProcessOps {
    fun list(): List<ProcInfo>
    fun term(pid: Int)
    fun kill(pid: Int)
}

data class ReapResult(
    val found: Int,
    /** Berhenti setelah SIGTERM (bersih). */
    val terminated: Int,
    /** Perlu SIGKILL. */
    val killed: Int,
    /** Masih hidup setelah SIGKILL. */
    val remaining: Int,
) {
    val total: Int get() = terminated + killed
}

/**
 * Mematikan proses bertahap: SIGTERM, tunggu [graceMs], SIGKILL untuk yang bandel, verifikasi.
 * Tidak pernah menyentuh [selfPid], PID <= 1, dan proses bernama di [keepNames] (mis. `logcat`
 * milik LogcatReporter). Blocking: panggil dari thread latar.
 */
class ProcessReaper(
    private val ops: ProcessOps,
    private val selfPid: Int,
    private val uid: Int,
    private val keepNames: Set<String> = setOf("logcat"),
    private val graceMs: Long = 500L,
    private val killWaitMs: Long = 400L,
    private val pollMs: Long = 40L,
) {
    private fun protectedProc(p: ProcInfo) = p.pid <= 1 || p.pid == selfPid || p.name in keepNames

    /** Semua proses lain milik UID ini yang masih hidup (proot, guest Debian, yt-dlp, yatim). */
    fun ownTargets(): List<ProcInfo> =
        ops.list().filter { it.uid == uid && it.alive && !protectedProc(it) }

    /** [rootPid] beserta seluruh keturunannya (lewat PPid). */
    fun treeOf(rootPid: Int): List<ProcInfo> = descendants(ops.list(), rootPid, includeRoot = true)
        .filter { it.alive && !protectedProc(it) }

    fun reapOwn(): ReapResult = reap { ownTargets() }

    fun reapTree(rootPid: Int): ReapResult = reap { treeOf(rootPid) }

    /** [select] dipanggil ulang tiap pemeriksaan agar proses yang baru lahir (anak dari anak) ikut tertangkap. */
    fun reap(select: () -> List<ProcInfo>): ReapResult {
        val first = select()
        if (first.isEmpty()) return ReapResult(0, 0, 0, 0)
        val seen = HashSet<Int>().apply { first.forEach { add(it.pid) } }

        first.forEach { safe { ops.term(it.pid) } }
        val afterTerm = waitGone(select, graceMs, seen)

        var remaining = afterTerm
        if (afterTerm.isNotEmpty()) {
            afterTerm.forEach { safe { ops.kill(it.pid) } }
            remaining = waitGone(select, killWaitMs, seen)
        }
        return ReapResult(
            found = seen.size,
            terminated = (seen.size - afterTerm.size).coerceAtLeast(0),
            killed = (afterTerm.size - remaining.size).coerceAtLeast(0),
            remaining = remaining.size,
        )
    }

    /** Tunggu sampai [select] kosong atau waktu habis; PID baru yang muncul dicatat di [seen]. */
    private fun waitGone(select: () -> List<ProcInfo>, waitMs: Long, seen: MutableSet<Int>): List<ProcInfo> {
        val deadline = System.nanoTime() + waitMs * 1_000_000L
        var cur = select()
        cur.forEach { seen.add(it.pid) }
        while (cur.isNotEmpty() && System.nanoTime() < deadline) {
            try { Thread.sleep(pollMs) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
            cur = select()
            cur.forEach { seen.add(it.pid) }
        }
        return cur
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (e: Exception) { }
    }

    companion object {
        /** Keturunan [rootPid] (dan dirinya bila [includeRoot]) dari daftar proses. Murni. */
        fun descendants(all: List<ProcInfo>, rootPid: Int, includeRoot: Boolean): List<ProcInfo> {
            val byParent = all.groupBy { it.ppid }
            val out = ArrayList<ProcInfo>()
            if (includeRoot) all.firstOrNull { it.pid == rootPid }?.let(out::add)
            val queue = ArrayDeque<Int>().apply { add(rootPid) }
            val visited = HashSet<Int>().apply { add(rootPid) }
            while (queue.isNotEmpty()) {
                val p = queue.removeFirst()
                for (c in byParent[p].orEmpty()) {
                    if (visited.add(c.pid)) { out.add(c); queue.add(c.pid) }
                }
            }
            return out
        }
    }
}

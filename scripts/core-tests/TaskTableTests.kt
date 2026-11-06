import com.molina.core.common.CpuTracker
import com.molina.core.common.ProcInfo
import com.molina.core.common.TaskGroup
import com.molina.core.common.TaskProbe
import com.molina.core.common.TaskSort
import com.molina.core.common.TaskTable

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    // --- TaskProbe
    val stat = "123 (my (weird) proc) S 1 123 123 0 -1 4194560 100 0 0 0 40 15 0 0 20 0 1 0 5000 1000 200 18446744073709551615"
    check("parseTicks nama berkurung/berspasi = utime+stime", TaskProbe.parseTicks(stat) == 55L)
    check("parseTicks rusak -> null", TaskProbe.parseTicks("garbage") == null && TaskProbe.parseTicks("1 (a) S 1") == null)
    check("parseCmdline pisah NUL", TaskProbe.parseCmdline("python3\u0000app.py\u0000\u0000") == listOf("python3", "app.py"))
    check("parseCmdline kosong", TaskProbe.parseCmdline("").isEmpty())

    // --- displayName
    check("displayName basename argv0", TaskTable.displayName("x", listOf("/usr/bin/proot", "--rootfs")) == "proot")
    check("displayName interpreter -> skrip", TaskTable.displayName("python3", listOf("/usr/bin/python3", "/opt/app/run.py")) == "run.py")
    check("displayName sh -c tetap sh", TaskTable.displayName("sh", listOf("sh", "-c", "ls")) == "sh")
    check("displayName cmdline kosong -> nama status", TaskTable.displayName("kworker", emptyList()) == "kworker")
    check("displayName bash interaktif", TaskTable.displayName("bash", listOf("-bash")) == "-bash")

    // --- byName
    check("yt-dlp lewat nama", TaskTable.byName("yt-dlp", emptyList()) == TaskGroup.Unduhan)
    check("yt-dlp lewat cmdline python", TaskTable.byName("python3", listOf("python3", "/x/yt-dlp", "url")) == TaskGroup.Unduhan)
    check("proot = Terminal", TaskTable.byName("proot", emptyList()) == TaskGroup.Terminal)
    check("python = Code", TaskTable.byName("python3.11", listOf("python3.11", "a.py")) == TaskGroup.Code)
    check("tak dikenal = null", TaskTable.byName("foo", emptyList()) == null)

    // --- build: filter uid/zombie, pewarisan kelompok
    val me = 10100
    val procs = listOf(
        ProcInfo(100, 1, me, "com.molina.studio", 'S', 150_000),
        ProcInfo(200, 100, me, "proot", 'S', 8_000),
        ProcInfo(201, 200, me, "bash", 'S', 4_000),
        ProcInfo(202, 201, me, "htop", 'R', 6_000),       // tak dikenal, mewarisi bash -> Terminal
        ProcInfo(300, 100, me, "foo", 'S', 1_000),         // anak app, tak dikenal -> Lainnya
        ProcInfo(400, 201, me, "python3", 'S', 20_000),    // Code
        ProcInfo(500, 1, 9999, "other", 'S', 999),         // uid lain -> dibuang
        ProcInfo(600, 100, me, "zomb", 'Z', 0),            // zombie -> dibuang
    )
    val cmds = mapOf(100 to listOf("com.molina.studio"), 400 to listOf("python3", "/s/job.py"))
    val rows = TaskTable.build(procs, 100, me, cmds, mapOf(200 to 12, 400 to 40))
    val by = rows.associateBy { it.pid }
    check("uid lain & zombie dibuang", rows.size == 6 && 500 !in by && 600 !in by)
    check("self = App", by[100]!!.group == TaskGroup.App && by[100]!!.isSelf)
    check("htop mewarisi Terminal", by[202]!!.group == TaskGroup.Terminal)
    check("anak app tak dikenal = Lainnya", by[300]!!.group == TaskGroup.Lainnya)
    check("python = Code, nama skrip", by[400]!!.group == TaskGroup.Code && by[400]!!.name == "job.py")
    check("cpu dipetakan, default 0", by[200]!!.cpuPercent == 12 && by[201]!!.cpuPercent == 0)

    // siklus PPid tidak membuat loop tak hingga
    val cyc = listOf(ProcInfo(1000, 1001, me, "a", 'S', 1), ProcInfo(1001, 1000, me, "b", 'S', 1))
    check("siklus PPid aman", TaskTable.build(cyc, 1, me, emptyMap(), emptyMap()).size == 2)

    // --- sort
    val s1 = TaskTable.sort(rows, TaskSort.Memory, true)
    check("sort memori turun", s1.first().pid == 100 && s1.zipWithNext().all { (a, b) -> a.rssKb >= b.rssKb })
    val s2 = TaskTable.sort(rows, TaskSort.Cpu, true)
    check("sort cpu turun", s2.first().pid == 400)
    val s3 = TaskTable.sort(rows, TaskSort.Pid, false)
    check("sort pid naik", s3.map { it.pid } == s3.map { it.pid }.sorted())
    val s4 = TaskTable.sort(rows, TaskSort.Name, false)
    check("sort nama tak peka huruf", s4.map { it.name.lowercase() } == s4.map { it.name.lowercase() }.sorted())
    check("seri diputus PID (stabil)", TaskTable.sort(rows, TaskSort.Cpu, true) == TaskTable.sort(rows.reversed(), TaskSort.Cpu, true))

    // --- ringkasan
    check("total rss", TaskTable.totalRssKb(rows) == 150_000L + 8_000 + 4_000 + 6_000 + 1_000 + 20_000)
    check("total cpu dijepit 100", TaskTable.totalCpu(rows.map { it.copy(cpuPercent = 80) }) == 100)

    // --- CpuTracker
    val t = CpuTracker(cores = 1)
    check("sampel pertama kosong", t.update(1_000_000_000L, mapOf(1 to 100L)).isEmpty())
    check("50 tick/1 dtk/1 inti = 50%", t.update(2_000_000_000L, mapOf(1 to 150L))[1] == 50)
    val t4 = CpuTracker(cores = 4)
    check("normalisasi 4 inti", t4.let { it.update(1_000_000_000L, mapOf(1 to 0L)); it.update(2_000_000_000L, mapOf(1 to 200L)) }[1] == 50)
    val tc = CpuTracker(cores = 1)
    tc.update(1_000_000_000L, mapOf(1 to 0L))
    check("dijepit 100", tc.update(2_000_000_000L, mapOf(1 to 5_000L))[1] == 100)
    val tn = CpuTracker(cores = 1)
    tn.update(1_000_000_000L, mapOf(1 to 500L))
    val r = tn.update(2_000_000_000L, mapOf(1 to 100L, 2 to 50L))
    check("tick turun (PID dipakai ulang) = 0, PID baru dilewati", r[1] == 0 && 2 !in r)
    val tz = CpuTracker(cores = 1)
    tz.update(5L, mapOf(1 to 0L))
    check("selisih waktu 0 tidak membagi nol", tz.update(5L, mapOf(1 to 10L)).isEmpty())

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails > 0) kotlin.system.exitProcess(1)
}

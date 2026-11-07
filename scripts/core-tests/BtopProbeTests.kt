import com.molina.core.common.BtopProbe
import com.molina.core.common.BtopRow
import com.molina.core.common.BtopSort
import com.molina.core.common.CoreCpuTracker
import com.molina.core.common.CoreTimes
import com.molina.core.common.ProcCpuTracker
import com.molina.core.common.TaskGroup

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun row(pid: Int, ppid: Int, name: String, cpu: Float = 0f, rss: Long = 0L, pss: Long = -1L, threads: Int = 1) = BtopRow(
    pid = pid, ppid = ppid, name = name, cmdline = "/opt/$name --x", state = 'S', threads = threads, nice = 0,
    rssKb = rss, pssKb = pss, cpu = cpu, runtimeSec = 0L, group = TaskGroup.Lainnya, isSelf = false,
)

fun main() {
    // --- meminfo
    val mi = BtopProbe.parseMemInfo(
        "MemTotal: 4000000 kB\nMemFree: 500000 kB\nMemAvailable: 1500000 kB\nBuffers: 20000 kB\n" +
            "Cached: 900000 kB\nSwapCached: 111 kB\nSwapTotal: 1000000 kB\nSwapFree: 400000 kB\n"
    )
    check("meminfo terpakai = total - tersedia", mi != null && mi.usedKb == 2_500_000L)
    check("meminfo Cached tidak tertukar SwapCached", mi != null && mi.cachedKb == 900_000L)
    check("meminfo swap terpakai", mi != null && mi.swapUsedKb == 600_000L)
    val mi2 = BtopProbe.parseMemInfo("MemTotal: 1000 kB\nMemFree: 100 kB\nBuffers: 10 kB\nCached: 90 kB\n")
    check("meminfo tanpa MemAvailable -> free+buffers+cached", mi2 != null && mi2.availableKb == 200L)
    check("meminfo rusak -> null", BtopProbe.parseMemInfo("garbage") == null)

    // --- loadavg & uptime
    val la = BtopProbe.parseLoadAvg("0.52 0.40 0.30 1/500 1234")
    check("loadavg", la != null && la.one == 0.52f && la.five == 0.40f && la.fifteen == 0.30f)
    check("loadavg rusak -> null", BtopProbe.parseLoadAvg("x y") == null)
    check("uptime", BtopProbe.parseUptimeSec("12345.67 99999.00") == 12345.67)

    // --- /proc/stat per inti
    val cs = BtopProbe.parseCpuStat(
        "cpu  100 0 50 800 50 0 0 0 0 0\ncpu0 60 0 30 400 10 0 0 0 0 0\ncpu1 40 0 20 400 40 0 0 0\nintr 1 2\n"
    )
    check("cpuStat hanya baris per inti", cs.size == 2)
    check("cpuStat busy/total inti 0", cs[0].busy == 90L && cs[0].total == 500L)
    check("cpuStat busy/total inti 1 (kolom lebih pendek)", cs[1].busy == 60L && cs[1].total == 500L)
    check("cpuStat kosong", BtopProbe.parseCpuStat("").isEmpty())

    // --- /proc/<pid>/stat
    val stat = "123 (my (weird) proc) S 1 123 123 0 -1 4194560 100 0 0 0 40 15 0 0 20 -5 4 0 5000 1000 200 18446744073709551615"
    val ps = BtopProbe.parseProcStat(stat)
    check("procStat ticks/nice/thread/start", ps != null && ps.ticks == 55L && ps.nice == -5 && ps.threads == 4 && ps.startTicks == 5000L)
    check("procStat rusak -> null", BtopProbe.parseProcStat("garbage") == null && BtopProbe.parseProcStat("1 (a) S 1") == null)

    // --- runtime
    check("runtimeSec", BtopProbe.runtimeSec(5000L, 100.0, 100) == 50L)
    check("runtimeSec tak negatif", BtopProbe.runtimeSec(99999L, 10.0, 100) == 0L)
    check("runtimeText detik/menit/jam", BtopProbe.runtimeText(50) == "50d" && BtopProbe.runtimeText(125) == "2m 05d" && BtopProbe.runtimeText(3900) == "1j 05m")

    // --- tracker CPU proses (desimal, 100 = satu inti)
    val t = ProcCpuTracker(100)
    check("tracker sampel pertama kosong", t.update(0L, mapOf(1 to 0L)).isEmpty())
    check("tracker 100% satu inti", t.update(1_000_000_000L, mapOf(1 to 100L))[1] == 100.0f)
    check("tracker 50%", t.update(2_000_000_000L, mapOf(1 to 150L))[1] == 50.0f)
    val t2 = ProcCpuTracker(100)
    t2.update(0L, mapOf(7 to 0L))
    check("tracker desimal 0,5%", t2.update(2_000_000_000L, mapOf(7 to 1L))[7] == 0.5f)
    check("tracker proses baru tak punya pembanding", ProcCpuTracker(100).also { it.update(0L, emptyMap()) }.update(1_000_000_000L, mapOf(9 to 50L)).isEmpty())

    // --- tracker per inti
    val ct = CoreCpuTracker()
    check("inti: sampel pertama kosong", ct.update(listOf(CoreTimes(90, 500), CoreTimes(60, 500))).isEmpty())
    val u = ct.update(listOf(CoreTimes(190, 600), CoreTimes(60, 600)))
    check("inti: 100% dan 0%", u.size == 2 && u[0] == 100f && u[1] == 0f)
    check("inti: jumlah inti berubah -> kosong", ct.update(listOf(CoreTimes(1, 2))).isEmpty())

    // --- sort & filter
    val rows = listOf(row(1, 0, "a", cpu = 5f, rss = 100), row(2, 1, "b", cpu = 20f, rss = 50, pss = 400), row(3, 1, "c", cpu = 1f, rss = 300, threads = 9))
    check("sort CPU turun", BtopProbe.sort(rows, BtopSort.Cpu, true).map { it.pid } == listOf(2, 1, 3))
    check("sort memori memakai PSS bila ada", BtopProbe.sort(rows, BtopSort.Memory, true).map { it.pid } == listOf(2, 3, 1))
    check("sort thread", BtopProbe.sort(rows, BtopSort.Threads, true).first().pid == 3)
    check("sort nama naik", BtopProbe.sort(rows, BtopSort.Name, false).map { it.name } == listOf("a", "b", "c"))
    check("filter nama (tak peka huruf)", BtopProbe.filter(rows, "B").map { it.pid } == listOf(2))
    check("filter PID persis", BtopProbe.filter(rows, "3").map { it.pid } == listOf(3))
    check("filter kosong = semua", BtopProbe.filter(rows, "  ").size == 3)

    // --- pohon
    val tr = BtopProbe.tree(
        listOf(row(1, 0, "init"), row(2, 1, "proot"), row(3, 1, "bash"), row(4, 2, "yt-dlp"), row(5, 99, "lone")),
        BtopSort.Pid, false
    )
    check("pohon urutan", tr.map { it.row.pid } == listOf(1, 2, 4, 3, 5))
    check("pohon awalan cabang", tr.map { it.prefix } == listOf("", "\u251C\u2500 ", "\u2502  \u2514\u2500 ", "\u2514\u2500 ", ""))
    check("pohon kedalaman", tr.map { it.depth } == listOf(0, 1, 2, 1, 0))
    val cyc = BtopProbe.tree(listOf(row(10, 11, "x"), row(11, 10, "y")), BtopSort.Pid, false)
    check("pohon aman dari siklus PID", cyc.size == 2)
    check("pohon PPID = PID sendiri jadi akar", BtopProbe.tree(listOf(row(8, 8, "self")), BtopSort.Pid, false).size == 1)

    if (fails > 0) { println("$fails GAGAL"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}

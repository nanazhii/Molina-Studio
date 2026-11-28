import com.molina.studio.terminal.FakeProc
import com.molina.studio.terminal.ProotBinds
import com.molina.studio.terminal.ProotCaps
import com.molina.studio.terminal.ProotConfig
import java.io.File
import kotlin.system.exitProcess

private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean) {
    if (ok) passed++ else { failed++; println("GAGAL: $name") }
}

fun main() {
    // 1) Golden: tanpa sysvipc, urutan sama persis dengan susunan lama di TerminalSessions.
    val env = listOf("HOME=/root", "TERM=xterm-256color", "LANG=C.UTF-8", "PATH=/usr/bin:/bin")
    val legacy = arrayOf(
        "proot", "--link2symlink", "--kill-on-exit", "-0",
        "--rootfs=/r", "--cwd=/root",
        "--bind=/dev", "--bind=/proc", "--bind=/sys",
        "--bind=/h/ws:/sdcard/Proyek",
        "/usr/bin/env", "-i", *env.toTypedArray(),
        "/bin/bash", "--rcfile", "/etc/molina/bashrc", "-i"
    )
    val cfg = ProotConfig("/r", "/root", listOf("--bind=/h/ws:/sdcard/Proyek"), env)
    check("golden tanpa sysvipc", cfg.args().contentEquals(legacy))

    // 2) sysvipc disisipkan setelah --kill-on-exit, sebelum -0
    val sv = ProotConfig("/r", "/root", emptyList(), emptyList(), sysvipc = true).args().toList()
    check("sysvipc ada", "--sysvipc" in sv)
    check("sysvipc urutan", sv.indexOf("--sysvipc") == sv.indexOf("--kill-on-exit") + 1 && sv.indexOf("-0") == sv.indexOf("--sysvipc") + 1)
    check("tanpa sysvipc default", "--sysvipc" !in cfg.args())

    // 3) Validasi bind
    check("bind valid", ProotBinds.arg("/a/b", "/proc/stat") == "--bind=/a/b:/proc/stat")
    check("bind host berisi ':'", ProotBinds.arg("/a:b", "/x") == null)
    check("bind guest relatif", ProotBinds.arg("/a", "x") == null)
    check("bind guest berisi ':'", ProotBinds.arg("/a", "/x:y") == null)
    check("bind host kosong", ProotBinds.arg("", "/x") == null)
    check("bind newline", ProotBinds.arg("/a\nb", "/x") == null)

    // 4) Deteksi kemampuan binary
    val bin = "xx\u0000kill-on-exit\u0000link2symlink\u0000yy".toByteArray(Charsets.ISO_8859_1)
    check("caps ada", ProotCaps.supports(bin, "kill-on-exit"))
    check("caps tidak ada", !ProotCaps.supports(bin, "sysvipc"))
    check("caps nama kosong", !ProotCaps.supports(bin, ""))
    check("caps binary kosong", !ProotCaps.supports(ByteArray(0), "a"))
    check("caps di ujung", ProotCaps.supports("abcsysvipc".toByteArray(), "sysvipc"))
    check("caps berkas tak ada", !ProotCaps.supports(File("/tidak/ada/libproot.so"), "sysvipc"))

    // 5) FakeProc
    val tmp = java.nio.file.Files.createTempDirectory("fakeproc").toFile()
    try {
        val none = FakeProc.prepare(File(tmp, "p"), 4) { true }
        check("semua terbaca: tanpa bind", none.isEmpty() && !File(tmp, "p").exists())

        val all = FakeProc.prepare(File(tmp, "p"), 4) { false }
        check("semua terblokir: 5 bind", all.size == FakeProc.NAMES.size)
        check("bind /proc/stat", all.any { it.endsWith(":/proc/stat") && it.startsWith("--bind=${File(tmp, "p").absolutePath}/stat") })
        val stat = File(tmp, "p/stat").readText()
        check("stat 4 cpu + agregat", stat.lines().count { it.matches(Regex("cpu\\d* .*")) } == 5)
        check("berkas diakhiri newline", FakeProc.NAMES.all { File(tmp, "p/$it").readText().endsWith("\n") })

        val mtime = File(tmp, "p/loadavg").lastModified()
        Thread.sleep(20)
        FakeProc.prepare(File(tmp, "p"), 4) { false }
        check("idempoten: tidak ditulis ulang", File(tmp, "p/loadavg").lastModified() == mtime)

        val only = FakeProc.prepare(File(tmp, "q"), 2) { it != "/proc/stat" }
        check("sebagian terblokir: hanya stat", only.size == 1 && only[0].endsWith(":/proc/stat"))
        check("cpu dibatasi 1..64", FakeProc.content("stat", 0)!!.lines().count { it.matches(Regex("cpu\\d+ .*")) } == 1 &&
            FakeProc.content("stat", 999)!!.lines().count { it.matches(Regex("cpu\\d+ .*")) } == 64)
        check("nama tak dikenal", FakeProc.content("meminfo", 4) == null)
    } finally {
        tmp.deleteRecursively()
    }

    println("ProotConfigTests: $passed lulus, $failed gagal")
    if (failed > 0) exitProcess(1)
}

// [DRAWER-RUN] Uji JVM murni untuk DrawerRun. Jalankan: bash scripts/core-tests/run_drawer_run.sh
import com.molina.core.exec.DrawerRun

private var fails = 0
private fun check(name: String, ok: Boolean) { if (ok) println("ok   $name") else { println("GAGAL $name"); fails++ } }

fun main() {
    check("sh didukung", DrawerRun.supports("a.sh"))
    check("bash didukung", DrawerRun.supports("a.bash"))
    check("py didukung (huruf besar)", DrawerRun.supports("A.PY"))
    check("kts didukung", DrawerRun.supports("x.kts"))
    check("gradle.kts ditolak", !DrawerRun.supports("build.gradle.kts"))
    check("txt ditolak", !DrawerRun.supports("a.txt"))
    check("md ditolak", !DrawerRun.supports("a.md"))
    check("tanpa ekstensi ditolak", !DrawerRun.supports("Makefile"))
    check("mp4 ditolak", !DrawerRun.supports("a.mp4"))

    val sh = DrawerRun.commandLine("tes.sh", null, "/sdcard/Molina/Scripts", "/sdcard/Molina/Scripts/tes.sh")!!
    check("sh: bash, bukan nano", "'/bin/bash' '/sdcard/Molina/Scripts/tes.sh'" in sh && "nano" !in sh)
    check("sh: cd ke folder berkas", sh.startsWith("cd '/sdcard/Molina/Scripts' && "))
    check("sh: diakhiri Enter tunggal", sh.endsWith("\r") && sh.count { it == '\r' } == 1 && '\n' !in sh)
    check("sh: ringkasan kode keluar", "keluar dengan kode" in sh && "dihentikan (Ctrl+C)" in sh)
    check("sh: dijalankan sekali", sh.split("'/bin/bash' '/sdcard/Molina/Scripts/tes.sh'").size == 2)

    val py = DrawerRun.commandLine("molina_dl.py", null, "/sdcard/Molina", "/sdcard/Molina/molina_dl.py")!!
    check("py: python3", "'/usr/bin/python3' '/sdcard/Molina/molina_dl.py'" in py)
    check("py: petunjuk pemasangan", "apt install python3" in py)
    check("py: cek interpreter ada", "[ -x '/usr/bin/python3' ]" in py)

    check("shebang python menang atas .sh", "'/usr/bin/python3'" in DrawerRun.commandLine("x.sh", "#!/usr/bin/env python3", "/d", "/d/x.sh")!!)
    check("shebang tak dikenal jatuh ke ekstensi", "'/bin/bash'" in DrawerRun.commandLine("x.sh", "#!/usr/bin/node", "/d", "/d/x.sh")!!)

    val q = DrawerRun.commandLine("a'b.sh", null, "/d e", "/d e/a'b.sh")!!
    check("kutip tunggal di nama aman", "'/d e/a'\\''b.sh'" in q && "cd '/d e' && " in q)
    check("nama dengan spasi dikutip", "'a'\\''b.sh'" in q)

    check("baris baru di path ditolak", DrawerRun.commandLine("a.sh", null, "/d", "/d/a\nrm -rf x.sh") == null)
    check("karakter kontrol di folder ditolak", DrawerRun.commandLine("a.sh", null, "/d\u0007", "/d/a.sh") == null)
    check("jenis tak didukung -> null", DrawerRun.commandLine("a.txt", null, "/d", "/d/a.txt") == null)

    val kts = DrawerRun.commandLine("x.kts", null, "/d", "/d/x.kts")!!
    check("kts: prasyarat kotlin dan java", "/usr/local/bin/kotlin" in kts && "'java'" in kts)
    check("kts: petunjuk Settings > Paket", "Settings > Paket > Kotlin" in kts)

    if (fails > 0) { println("$fails gagal"); kotlin.system.exitProcess(1) }
    println("DrawerRunTests: semua lulus")
}

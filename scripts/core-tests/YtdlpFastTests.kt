import com.molina.core.download.YtdlpCommand
import com.molina.core.download.YtdlpFast as F

private var fails = 0
private fun check(name: String, ok: Boolean) { if (ok) println("ok   $name") else { fails++; println("GAGAL $name") } }

fun main() {
    check("marker cocok", F.usable(F.marker(3_100_000, 1_700_000_000_000), 3_100_000, 1_700_000_000_000))
    check("marker dengan newline", F.usable(F.marker(5, 6) + "\n", 5, 6))
    check("marker basi: ukuran", !F.usable(F.marker(5, 6), 7, 6))
    check("marker basi: waktu", !F.usable(F.marker(5, 6), 5, 7))
    check("tanpa marker", !F.usable(null, 5, 6))
    check("ukuran nol", !F.usable(F.marker(0, 6), 0, 6))
    check("nama aman", F.safeName("yt_dlp/extractor/common.py") == "yt_dlp/extractor/common.py")
    check("direktori aman", F.safeName("yt_dlp/") == "yt_dlp/")
    check("tolak ..", F.safeName("../etc/passwd") == null)
    check("tolak .. di tengah", F.safeName("a/../../b") == null)
    check("tolak absolut", F.safeName("/etc/passwd") == null)
    check("tolak backslash", F.safeName("a\\b") == null)
    check("tolak kosong", F.safeName("") == null)
    check("rusak: no module", F.looksBroken("ModuleNotFoundError: No module named 'yt_dlp'"))
    check("rusak: __main__", F.looksBroken("python3: can't find '__main__' module in '/x'"))
    check("bukan rusak: galat situs", !F.looksBroken("ERROR: Unsupported URL: https://x"))
    val z = YtdlpCommand.prootArgs("/r", "/usr/local/bin/yt-dlp", listOf("--version"))
    check("zipapp: bytecode mati", "PYTHONDONTWRITEBYTECODE=1" in z)
    val d = YtdlpCommand.prootArgs("/r", F.GUEST_DIR, listOf("--version"), null, writeBytecode = true)
    check("direktori: bytecode hidup", "PYTHONDONTWRITEBYTECODE=1" !in d)
    check("direktori: python3 lalu jalur", d.indexOf(YtdlpCommand.GUEST_PYTHON) + 1 == d.indexOf(F.GUEST_DIR))
    // [YTDL-WARM]
    check("compileall: argumen", F.compileArgs("/d") == listOf("compileall", "-q", "/d/yt_dlp"))
    check("penanda hangat memakai format penanda sumber", F.usable(F.marker(9, 8), 9, 8) && !F.usable(F.marker(9, 8), 9, 7))
    if (fails > 0) { println("$fails GAGAL"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}

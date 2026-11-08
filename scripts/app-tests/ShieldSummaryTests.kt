import com.molina.studio.settings.ShieldSummary
import com.molina.studio.settings.ShieldSummary.Input

private var fails = 0
private var runs = 0
private fun check(name: String, ok: Boolean) { runs++; if (!ok) { fails++; println("GAGAL: $name") } }
private fun <T> eq(name: String, got: T, want: T) { runs++; if (got != want) { fails++; println("GAGAL: $name -> $got (seharusnya $want)") } }

private val FP = (1..32).joinToString(":") { "%02X".format(it) }
private fun inp(
    shield: Boolean = true, trackers: Boolean = true, lan: Boolean = false, secure: Boolean = true,
    rtmp: Boolean = false, debug: Boolean = false, blocked: Int = 0, fp: String? = FP,
) = Input(shield, trackers, lan, secure, rtmp, debug, blocked, fp)

fun main() {
    // semua inti aktif, opsional mati: skor penuh, opsional hanya disebut
    val full = ShieldSummary.build(inp())
    eq("skor penuh", full.title, "Perlindungan: 5 dari 5 aktif")
    check("semua inti aktif", full.body.contains("Semua perlindungan inti aktif."))
    check("opsional disebut", full.body.contains("Opsional: blokir rtmp di playlist mati."))
    check("batas selalu ada", full.body.contains(ShieldSummary.LIMITS) && full.report.contains(ShieldSummary.LIMITS))
    check("tidak ada Belum aktif", !full.body.contains("Belum aktif"))

    // opsi rtmp tidak mengubah skor
    val withRtmp = ShieldSummary.build(inp(rtmp = true))
    eq("rtmp tidak mengubah skor", withRtmp.title, full.title)
    check("opsional tidak disebut bila aktif", !withRtmp.body.contains("Opsional"))
    check("laporan rtmp aktif", withRtmp.report.contains("Opsional, blokir rtmp di playlist: aktif"))

    // semua mati
    val none = ShieldSummary.build(inp(shield = false, trackers = false, lan = true, secure = false, debug = true))
    eq("skor nol", none.title, "Perlindungan: 0 dari 5 aktif")
    for (n in listOf("Filter http/https (Shield)", "Blokir pelacak dan iklan", "Jaringan lokal ditolak", "Layar privat", "Build rilis (tidak debuggable)"))
        check("Belum aktif memuat $n", none.body.contains(n))
    check("tidak mengaku semua aktif", !none.body.contains("Semua perlindungan inti aktif"))

    // satu per satu
    eq("lan diizinkan", ShieldSummary.build(inp(lan = true)).title, "Perlindungan: 4 dari 5 aktif")
    check("lan disebut", ShieldSummary.build(inp(lan = true)).body.contains("Belum aktif: Jaringan lokal ditolak"))
    check("debug disebut", ShieldSummary.build(inp(debug = true)).body.contains("Belum aktif: Build rilis (tidak debuggable)"))
    check("shield mati disebut", ShieldSummary.build(inp(shield = false)).body.contains("Belum aktif: Filter http/https (Shield)"))

    // sidik jari: ringkas di kartu, lengkap di laporan
    check("kartu memotong", full.body.contains(FP.take(23) + "\u2026") && !full.body.contains(FP))
    check("laporan lengkap", full.report.contains(FP))
    val short = ShieldSummary.build(inp(fp = "AA:BB"))
    check("sidik jari pendek tanpa elipsis", short.body.contains("Tanda tangan: AA:BB") && !short.body.contains("AA:BB\u2026"))
    val nofp = ShieldSummary.build(inp(fp = null))
    check("tanpa sidik jari kartu", nofp.body.contains("Tanda tangan: tidak terbaca"))
    check("tanpa sidik jari laporan", nofp.report.contains("Tanda tangan SHA-256: tidak terbaca"))

    // jumlah diblokir
    check("jumlah di kartu", ShieldSummary.build(inp(blocked = 7)).body.contains("Diblokir sesi ini: 7"))
    check("jumlah di laporan", ShieldSummary.build(inp(blocked = 7)).report.contains("Diblokir sesi ini: 7"))

    // laporan menandai yang mati dengan jelas
    check("laporan MATI", ShieldSummary.build(inp(secure = false)).report.contains("Layar privat: MATI"))

    println(if (fails == 0) "LULUS: $runs kasus" else "$fails dari $runs kasus GAGAL")
    if (fails != 0) System.exit(1)
}

import com.molina.core.common.RssDelta

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    val a = RssDelta()
    check("aktif tanpa dasar -> -1 (tidak diketahui)", a.estimate(true, 300_000) == -1L)
    check("tidak aktif -> 0 dan dasar terisi", a.estimate(false, 200_000) == 0L)
    check("aktif -> selisih dari dasar", a.estimate(true, 290_000) == 90_000L)
    check("selisih tidak pernah negatif", a.estimate(true, 150_000) == 0L)
    check("dasar mengikuti sampel nonaktif terakhir", a.let { it.estimate(false, 250_000); it.estimate(true, 300_000) } == 50_000L)
    check("rss tak terbaca -> -1", a.estimate(true, -1) == -1L && a.estimate(false, -1) == -1L)
    check("rss tak terbaca tidak merusak dasar", a.estimate(true, 330_000) == 80_000L)

    check("combine keduanya tidak diketahui", RssDelta.combine(-1, -1) == -1L)
    check("combine salah satu diketahui (b)", RssDelta.combine(-1, 500) == 500L)
    check("combine salah satu diketahui (a)", RssDelta.combine(300, -1) == 300L)
    check("combine penjumlahan", RssDelta.combine(300, 200) == 500L)
    check("combine nol tetap nol (bukan -1)", RssDelta.combine(0, 0) == 0L)

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails > 0) kotlin.system.exitProcess(1)
}

import com.molina.code.run.RunTimeout

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    val o = RunTimeout.OPTIONS_MS
    check("pilihan menaik dan diawali bawaan 60 dtk", o == o.sorted() && o.first() == RunTimeout.DEFAULT_MS && RunTimeout.DEFAULT_MS == 60_000L)
    check("pilihan terpanjang tepat 1 jam", o.last() == 3_600_000L)
    check("label", RunTimeout.short(60_000L) == "60 dtk" && RunTimeout.short(300_000L) == "5 mnt" &&
        RunTimeout.short(900_000L) == "15 mnt" && RunTimeout.short(1_800_000L) == "30 mnt" && RunTimeout.short(3_600_000L) == "1 jam")
    check("next berputar dan kembali ke awal", RunTimeout.next(60_000L) == 300_000L && RunTimeout.next(3_600_000L) == 60_000L)
    check("satu putaran penuh melewati semua pilihan", generateSequence(60_000L) { RunTimeout.next(it) }.take(o.size + 1).toList() == o + o.first())
    check("parse nilai sah", RunTimeout.parse("900000") == 900_000L && RunTimeout.parse(" 300000 ") == 300_000L)
    check("parse rusak/kosong/nol/negatif kembali ke bawaan",
        RunTimeout.parse(null) == 60_000L && RunTimeout.parse("abc") == 60_000L && RunTimeout.parse("") == 60_000L &&
            RunTimeout.parse("0") == 60_000L && RunTimeout.parse("-5") == 60_000L)
    check("nilai di luar pilihan dibulatkan ke terdekat", RunTimeout.coerce(400_000L) == 300_000L && RunTimeout.coerce(99_999_999L) == 3_600_000L && RunTimeout.coerce(1L) == 60_000L)
    check("next dari nilai tak sah tetap valid", RunTimeout.next(123_456L) in o)
    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}

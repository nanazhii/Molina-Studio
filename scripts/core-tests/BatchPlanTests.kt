import com.molina.core.common.BatchPlan

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    val a = "https://www.youtube.com/watch?v=aaaaaaaaaaa"
    val b = "https://youtu.be/bbbbbbbbbbb"
    val c = "http://example.com/c"
    val done = setOf(b)
    val isDone = { u: String -> u in done }
    val all = listOf(a, b, c)

    val p1 = BatchPlan.plan(all)
    check("tanpa skip: semua masuk", p1.urls == all && p1.skippedDone == 0 && p1.overLimit == 0)
    val p2 = BatchPlan.plan(all, isDone, skipDone = true)
    check("skip: yang sudah diunduh dilewati", p2.urls == listOf(a, c) && p2.skippedDone == 1)
    val p3 = BatchPlan.plan(all, isDone, skipDone = false)
    check("skipDone=false: tidak dilewati", p3.urls == all && p3.skippedDone == 0)
    check("bukan http dibuang", BatchPlan.plan(listOf("ftp://x", "/sdcard/a.mp4", "", a)).urls == listOf(a))
    check("duplikat dibuang", BatchPlan.plan(listOf(a, a, " $a ")).urls == listOf(a))
    check("spasi dipangkas", BatchPlan.plan(listOf("  $a  ")).urls == listOf(a))
    check("urutan dipertahankan", BatchPlan.plan(listOf(c, a, b)).urls == listOf(c, a, b))

    val many = (1..60).map { "https://example.com/v$it" }
    val p4 = BatchPlan.plan(many)
    check("batas bawaan 50", p4.urls.size == BatchPlan.DEFAULT_MAX && p4.overLimit == 10)
    val p5 = BatchPlan.plan(many, max = 5)
    check("batas kustom", p5.urls == many.take(5) && p5.overLimit == 55)
    check("batas 0 -> kosong", BatchPlan.plan(many, max = 0).empty)
    val p6 = BatchPlan.plan(listOf(b), isDone, skipDone = true)
    check("semua sudah diunduh -> kosong", p6.empty && p6.skippedDone == 1)
    check("daftar kosong -> kosong", BatchPlan.plan(emptyList()).empty)
    check("batas dihitung setelah skip", BatchPlan.plan(listOf(b) + many, isDone, true).urls.size == 50)

    check("pesan: semua sudah diunduh", BatchPlan.message(p6) == "Semua sudah diunduh (1 dilewati).")
    check("pesan: kosong", BatchPlan.message(BatchPlan.plan(emptyList())) == "Tidak ada tautan yang bisa diunduh.")
    check("pesan: satu", BatchPlan.message(BatchPlan.plan(listOf(a))) == "Dikirim ke Unduhan")
    check("pesan: banyak", BatchPlan.message(p1) == "3 tautan dikirim ke Unduhan")
    check("pesan: dilewati", BatchPlan.message(p2) == "2 tautan dikirim ke Unduhan \u00B7 1 dilewati (sudah diunduh)")
    check("pesan: batas", BatchPlan.message(p4) == "50 tautan dikirim ke Unduhan (maks 50)")
    check("pesan: satu + dilewati",
        BatchPlan.message(BatchPlan.plan(listOf(a, b), isDone, true)) == "Dikirim ke Unduhan \u00B7 1 dilewati (sudah diunduh)")

    println(if (fails == 0) "\nSEMUA LOLOS" else "\n$fails GAGAL")
    if (fails > 0) System.exit(1)
}

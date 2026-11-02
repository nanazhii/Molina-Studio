import com.molina.core.common.SearchLang
import com.molina.core.common.SearchLang.Verdict
import com.molina.core.common.StreamSearchItem

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun item(t: String) = StreamSearchItem("https://example.com/" + t.hashCode(), t)
private fun titles(l: List<StreamSearchItem>) = l.joinToString(" | ") { it.title }

fun main() {
    SearchLang.enabled = true
    val id = listOf(
        "Cara Membuat Nasi Goreng yang Enak dan Mudah",
        "Berita Terbaru Hari Ini - Semua yang Perlu Kamu Tahu",
        "Belajar Kotlin untuk Pemula Lengkap",
        "Kisah Nyata Orang Indonesia di Jepang",
        "Film Indonesia Terbaik Sepanjang Masa",
    )
    for (t in id) check("ID: $t", SearchLang.classify(t) == Verdict.ID)

    val other = listOf(
        "How to Cook the Perfect Fried Rice",
        "The Best Songs of 2024 - Official Video",
        "What Is Going On With the Economy",
        "Learn Kotlin in One Hour for Beginners",
        "Как приготовить борщ",
        "東京の夜を歩く",
        "كيف تتعلم البرمجة",
    )
    for (t in other) check("OTHER: $t", SearchLang.classify(t) == Verdict.OTHER)

    val neutral = listOf("Lathi", "Weird Genius", "Sheila On 7 - Dan", "4K Nature 2024", "!!!", "")
    for (t in neutral) check("tidak jadi OTHER: '$t'", SearchLang.classify(t) != Verdict.OTHER)
    check("judul campur (id >= en) tetap ID", SearchLang.classify("Cara install the app di Android") == Verdict.ID)

    val mix = listOf(
        item("How to cook rice and the best tips"), item("Lathi"), item("Resep Nasi Goreng yang Enak"),
        item("Weird Genius"), item("Cara Masak Rendang untuk Pemula"), item("What is this video about"),
        item("Sheila On 7 - Dan"), item("Berita Hari Ini Lengkap"), item("Kisah Anak Rumahan"),
    )
    val r = SearchLang.apply(mix, 50)
    check("apply: OTHER dibuang", r.none { SearchLang.classify(it.title) == Verdict.OTHER })
    check("apply: ID di depan", titles(r).startsWith("Resep Nasi Goreng yang Enak | Cara Masak Rendang"))
    check("apply: netral menyusul urutan asli", titles(r).contains("Lathi | Weird Genius"))
    check("apply: urutan relevansi dalam ID dijaga", titles(r).indexOf("Resep Nasi") < titles(r).indexOf("Cara Masak Rendang"))
    check("apply: limit dihormati", SearchLang.apply(mix, 3).size == 3)

    val few = listOf(item("How to cook rice and the best tips"), item("Resep Nasi Goreng yang Enak"), item("What is this video about"))
    val f = SearchLang.apply(few, 50)
    check("fallback: tidak ada yang dibuang", f.size == 3)
    check("fallback: ID tetap paling depan", f.first().title.startsWith("Resep"))

    SearchLang.enabled = false
    check("mati: urutan asli", SearchLang.apply(mix, 50).map { it.title } == mix.map { it.title })
    check("mati: tetap dipotong limit", SearchLang.apply(mix, 2).size == 2)
    SearchLang.enabled = true

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}

import com.molina.core.common.OriginalTitles
import com.molina.core.common.StreamSearchItem
import java.util.concurrent.atomic.AtomicInteger

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun item(id: String, t: String) = StreamSearchItem("https://www.youtube.com/watch?v=$id", t)

fun main() {
    check("titleOf: JSON normal", OriginalTitles.titleOf("""{"title":" Judul Asli ","author_name":"x"}""") == "Judul Asli")
    check("titleOf: bukan JSON -> null", OriginalTitles.titleOf("Not Found") == null)
    check("titleOf: tanpa title -> null", OriginalTitles.titleOf("""{"author_name":"x"}""") == null)
    check("titleOf: null -> null", OriginalTitles.titleOf(null) == null)
    check("titleOf: escape unicode", OriginalTitles.titleOf("""{"title":"Lagu \u00e9 \"Baru\""}""") == "Lagu \u00e9 \"Baru\"")

    val calls = AtomicInteger(0)
    val originals = mapOf("a1" to "How I Cook Rice", "b2" to "Resep Nasi Goreng", "c3" to "Sama Saja")
    val fetch: (String) -> String? = { url ->
        calls.incrementAndGet()
        val id = url.substringAfter("v=")
        when (id) {
            "d4" -> null                                  // gagal diambil
            "e5" -> "<html>404</html>"                    // bukan JSON
            else -> originals[id]?.let { """{"title":"$it"}""" }
        }
    }
    val items = listOf(
        item("a1", "Cara Masak Nasi (terjemahan)"), item("b2", "Resep Nasi Goreng"), item("c3", "Sama Saja"),
        item("d4", "Gagal Ambil"), item("e5", "Bukan JSON"),
    )
    val r = OriginalTitles.apply(items, fetch)
    check("judul asli menggantikan terjemahan", r.items[0].title == "How I Cook Rice")
    check("judul yang sama tidak dihitung berubah", r.changed == 1 && r.items[1].title == "Resep Nasi Goreng")
    check("gagal/bukan JSON tetap judul pencarian", r.items[3].title == "Gagal Ambil" && r.items[4].title == "Bukan JSON")
    check("urutan dan URL tetap", r.items.map { it.pageUrl } == items.map { it.pageUrl })
    check("known = 3", r.known == 3)

    val before = calls.get()
    OriginalTitles.apply(items, fetch)
    check("cache: hanya yang gagal diambil ulang", calls.get() - before == 2)
    check("daftar kosong aman", OriginalTitles.apply(emptyList(), fetch).items.isEmpty())

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}

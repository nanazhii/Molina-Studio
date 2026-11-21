import com.molina.cinema.VariantPicker
import com.molina.cinema.VideoVariant

// Tes JVM murni untuk VariantPicker (aturan pilih varian terhadap batas tinggi, E-3c-1).

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun v(id: Int, h: Int) = VideoVariant(mpvId = id, width = h * 16 / 9, height = h, fps = 30.0, bitrateBps = 0L, codec = null)

fun main() {
    check("E-3c-1: daftar kosong menghasilkan null", VariantPicker.pick(emptyList(), 720) == null)
    run {
        val vs = listOf(v(1, 360), v(2, 480), v(3, 720), v(4, 1080))
        check("E-3c-1: pilih tertinggi yang tidak melebihi batas", VariantPicker.pick(vs, 720)?.mpvId == 3)
    }
    run {
        val vs = listOf(v(1, 360), v(2, 480), v(3, 720))
        check("E-3c-1: tinggi tepat sama dengan batas dipilih", VariantPicker.pick(vs, 480)?.mpvId == 2)
    }
    run {
        val vs = listOf(v(1, 720), v(2, 1080), v(3, 2160))
        check("E-3c-1: semua di atas batas memilih yang terendah", VariantPicker.pick(vs, 480)?.mpvId == 1)
    }
    check("E-3c-1: satu varian di atas batas tetap dipilih", VariantPicker.pick(listOf(v(7, 1080)), 480)?.mpvId == 7)
    run {
        val vs = listOf(v(4, 1080), v(1, 360), v(3, 720), v(2, 480))
        check("E-3c-1: urutan masukan tidak berpengaruh (tertinggi di bawah batas)", VariantPicker.pick(vs, 576)?.mpvId == 2)
    }
    run {
        val vs = listOf(v(4, 1080), v(3, 720), v(1, 360))
        check("E-3c-1: fallback terendah tidak bergantung urutan masukan", VariantPicker.pick(vs, 240)?.mpvId == 1)
    }
    run {
        val vs = listOf(v(1, 720), v(2, 720))
        check("E-3c-1: seri tinggi memilih yang lebih dulu dalam daftar", VariantPicker.pick(vs, 720)?.mpvId == 1)
    }
    run {
        val vs = listOf(v(1, 720), v(2, 720))
        check("E-3c-1: seri tinggi pada fallback juga memilih yang lebih dulu", VariantPicker.pick(vs, 480)?.mpvId == 1)
    }
    run {
        val vs = listOf(v(1, 0), v(2, 480), v(3, 720))
        check("E-3c-1: tinggi tidak diketahui (0) kalah dari varian bertinggi di bawah batas", VariantPicker.pick(vs, 720)?.mpvId == 3)
    }
    run {
        val vs = listOf(v(1, 720), v(2, 0), v(3, 1080))
        check("E-3c-1: semua bertinggi di atas batas, varian 0 jadi fallback (perilaku lama)", VariantPicker.pick(vs, 480)?.mpvId == 2)
    }
    check("E-3c-1: semua tinggi 0 memilih yang pertama", VariantPicker.pick(listOf(v(5, 0), v(6, 0)), 720)?.mpvId == 5)
    check("E-3c-1: batas 1080 memilih 1080 dari beragam pilihan", VariantPicker.pick(listOf(v(1, 480), v(2, 1080), v(3, 2160)), 1080)?.mpvId == 2)

    if (fails > 0) { println("$fails tes gagal"); System.exit(1) }
    println("semua tes VariantPicker lulus")
}

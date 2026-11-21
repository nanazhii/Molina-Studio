import com.molina.cinema.VariantPicker
import com.molina.cinema.VariantPicker.Reason
import com.molina.cinema.VideoVariant

// Tes JVM murni untuk VariantPicker (aturan pilih varian + alasan + ringkasan log, E-3c-1 dan DIAG-QUALITY).

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun v(id: Int, h: Int) = VideoVariant(mpvId = id, width = h * 16 / 9, height = h, fps = 30.0, bitrateBps = 0L, codec = null)
private const val DOT = "\u00B7"

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

    // ---- DIAG-QUALITY: alasan keputusan ----
    check("DIAG: alasan WITHIN_CAP", VariantPicker.decide(listOf(v(1, 480), v(2, 720)), 720).reason == Reason.WITHIN_CAP)
    check("DIAG: alasan FALLBACK_LOWEST", VariantPicker.decide(listOf(v(1, 720), v(2, 1080)), 480).reason == Reason.FALLBACK_LOWEST)
    check("DIAG: alasan NO_VARIANT dan varian null", VariantPicker.decide(emptyList(), 480).let { it.reason == Reason.NO_VARIANT && it.variant == null })

    // ---- DIAG-QUALITY: ringkasan satu baris ----
    run {
        val vs = listOf(v(1, 360), v(2, 480), v(3, 720), v(4, 1080))
        val s = VariantPicker.describe(vs, 720, VariantPicker.decide(vs, 720))
        check("DIAG: ringkasan WITHIN_CAP persis",
            s == "pilih varian: batas=720p kandidat=[360p,480p,720p,1080p] dipilih=720p $DOT 30 fps id=3 (tertinggi <= batas)")
    }
    run {
        val vs = listOf(v(1, 720), v(2, 1080))
        val s = VariantPicker.describe(vs, 480, VariantPicker.decide(vs, 480))
        check("DIAG: ringkasan fallback persis",
            s == "pilih varian: batas=480p kandidat=[720p,1080p] dipilih=720p $DOT 30 fps id=1 (semua di atas batas, ambil terendah)")
    }
    run {
        val s = VariantPicker.describe(emptyList(), 720, VariantPicker.decide(emptyList(), 720))
        check("DIAG: ringkasan tanpa varian persis", s == "pilih varian: batas=720p kandidat=[] dipilih=tidak ada (tanpa varian)")
    }
    run {
        val vs = listOf(v(1, 0), v(2, 480))
        val s = VariantPicker.describe(vs, 720, VariantPicker.decide(vs, 720))
        check("DIAG: tinggi 0 tampil sebagai ?p", s.contains("kandidat=[?p,480p]") && s.contains("id=2"))
    }
    run {
        val vs = (1..14).map { v(it, it * 100) }
        val s = VariantPicker.describe(vs, 2000, VariantPicker.decide(vs, 2000))
        val shown = (1..12).joinToString(",") { "${it * 100}p" }
        check("DIAG: kandidat dibatasi 12 dan sisanya dihitung",
            s == "pilih varian: batas=2000p kandidat=[$shown,+2] dipilih=1400p $DOT 30 fps id=14 (tertinggi <= batas)")
    }

    if (fails > 0) { println("$fails tes gagal"); System.exit(1) }
    println("semua tes VariantPicker lulus")
}

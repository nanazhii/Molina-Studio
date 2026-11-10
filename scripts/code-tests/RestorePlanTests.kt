import com.molina.code.history.HistoryKind
import com.molina.code.history.HistoryStore
import com.molina.code.history.RestorePlan
import java.io.File
import java.nio.file.Files

// [EXT-DIFF3] Uji rencana pemulihan (JVM murni).
private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    val tmp = Files.createTempDirectory("restoreplan").toFile()
    try {
        val st = HistoryStore(File(tmp, "h"))
        val f = File(tmp, "a.txt")
        st.record(f, "a\nb\n", HistoryKind.EXTERNAL_BEFORE, 1000L)
        st.record(f, "a\nB\n", HistoryKind.EXTERNAL_AFTER, 2000L)
        val list = st.list(f)
        val rd = { v: com.molina.code.history.HistoryVersion -> st.read(f, v) }

        val ok = RestorePlan.forChange(list, 'M', 2100L, "a\nB\n", rd)
        check("M: target = versi sebelum perubahan", ok.error == null && ok.target?.kind == HistoryKind.EXTERNAL_BEFORE && st.read(f, ok.target!!) == "a\nb\n")
        check("M: disk sudah berubah lagi -> ditolak", RestorePlan.forChange(list, 'M', 2100L, "a\nB\nx\n", rd).error?.contains("berubah lagi") == true)
        check("M: berkas dihapus -> ditolak", RestorePlan.forChange(list, 'M', 2100L, null, rd).error?.contains("dihapus") == true)
        check("M: tanpa versi Sesudah diubah luar -> ditolak", RestorePlan.forChange(list.filter { it.kind != HistoryKind.EXTERNAL_AFTER }, 'M', 2100L, "a\nB\n", rd).target == null)
        check("M: hanya satu versi (tak ada sebelumnya) -> ditolak", RestorePlan.forChange(list.filter { it.kind == HistoryKind.EXTERNAL_AFTER }, 'M', 2100L, "a\nB\n", rd).target == null)
        st.record(f, "a\nC\n", HistoryKind.EXTERNAL_AFTER, 90_000L)
        val list2 = st.list(f)
        val pick = RestorePlan.forChange(list2, 'M', 2100L, "a\nB\n", rd)
        check("M: memilih peristiwa yang sesuai waktu butir, bukan yang terbaru", pick.target?.kind == HistoryKind.EXTERNAL_BEFORE)

        val g = File(tmp, "gone.txt")
        st.record(g, "x\ny\n", HistoryKind.SHADOW, 1000L)
        val d = RestorePlan.forChange(st.list(g), 'D', 5L, null, rd)
        check("D: berkas hilang -> target versi terbaru", d.error == null && st.read(g, d.target!!) == "x\ny\n")
        check("D: berkas sudah ada lagi -> ditolak", RestorePlan.forChange(st.list(g), 'D', 5L, "x\n", rd).error?.contains("sudah ada") == true)
        check("D: tanpa riwayat -> ditolak", RestorePlan.forChange(emptyList(), 'D', 5L, null, rd).error != null)
        check("jenis A tidak bisa dipulihkan", RestorePlan.forChange(list, 'A', 5L, "x", rd).error != null)

        check("canOffer: M luar berhitungan", RestorePlan.canOffer(true, 'M', 2, 1))
        check("canOffer: M luar tanpa hitungan = tidak", !RestorePlan.canOffer(true, 'M', 0, 0))
        check("canOffer: D luar berisi lama", RestorePlan.canOffer(true, 'D', 0, 4))
        check("canOffer: A atau sumber lain = tidak", !RestorePlan.canOffer(true, 'A', 3, 0) && !RestorePlan.canOffer(false, 'M', 2, 1))
    } finally {
        tmp.deleteRecursively()
    }
    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}

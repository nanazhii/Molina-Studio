import com.molina.studio.terminal.NanoRunPrompt
import java.io.File

private var fails = 0
private var runs = 0
private fun <T> eq(name: String, got: T, want: T) { runs++; if (got != want) { fails++; println("GAGAL: $name -> $got (seharusnya $want)") } }

private fun keys(rows: List<String>) = NanoRunPrompt.parse(rows)?.joinToString("") { it.key.toString() }
private fun def(rows: List<String>) = NanoRunPrompt.parse(rows)?.firstOrNull { it.isDefault }?.key

fun main(args: Array<String>) {
    val full = "└ Pilih [1/2/a/3/e/x] (Enter = 1): "
    val truncFix = "└ Pilih [1/2/a/3/e/x] (Enter = x): " // skrip terpotong: tetap bisa diperbaiki, bawaan Batal
    val noFix = "└ Pilih [3/e/x] (Enter = 3): "
    val trunc = "└ Pilih [3/e/x] (Enter = x): "
    eq("penuh: tombol", keys(listOf("a", full)), "12a3ex")
    eq("penuh: bawaan 1", def(listOf(full)), '1')
    eq("terpotong+perbaikan: tombol", keys(listOf(truncFix)), "12a3ex")
    eq("terpotong+perbaikan: bawaan x", def(listOf(truncFix)), 'x')
    eq("hanya 3/e/x: tombol", keys(listOf(trunc)), "3ex")
    eq("hanya 3/e/x: bawaan x", def(listOf(trunc)), 'x')
    eq("tanpa perbaikan: bawaan 3", def(listOf(noFix)), '3')
    eq("label 1", NanoRunPrompt.parse(listOf(full))!![0].label, "PERBAIKI")
    eq("label x", NanoRunPrompt.parse(listOf(full))!!.last().label, "BATAL")
    eq("urutan kirim", NanoRunPrompt.sequenceOf(NanoRunPrompt.parse(listOf(full))!![2]), "a\r")
    eq("tanpa prompt", NanoRunPrompt.parse(listOf("halo", "dunia")), null)
    eq("daftar kosong", NanoRunPrompt.parse(emptyList()), null)
    eq("token asing ditolak", NanoRunPrompt.parse(listOf("└ Pilih [1/z/3/x] (Enter = 1): ")), null)
    eq("token ganda ditolak", NanoRunPrompt.parse(listOf("└ Pilih [3/3/x] (Enter = 3): ")), null)
    eq("tanpa x ditolak", NanoRunPrompt.parse(listOf("└ Pilih [1/3/e] (Enter = 1): ")), null)
    eq("tanpa 3 ditolak", NanoRunPrompt.parse(listOf("└ Pilih [1/e/x] (Enter = 1): ")), null)
    eq("token dua huruf ditolak", NanoRunPrompt.parse(listOf("└ Pilih [12/3/x] (Enter = 1): ")), null)
    // prompt lama di layar + prompt baru di bawahnya: yang terbawah menang
    eq("prompt terbawah menang", keys(listOf(full, "x", trunc)), "3ex")
    // layar sempit: prompt terlipat di tengah daftar tombol
    eq("terlipat: tombol", keys(listOf("└ Pilih [1/2/a/", "3/e/x] (Enter = 1): ")), "12a3ex")
    eq("terlipat: bawaan", def(listOf("└ Pilih [1/2/a/3/e/", "x] (Enter = 1): ")), '1')
    eq("terlipat sebelum bawaan", def(listOf("└ Pilih [1/2/a/3/e/x] (Enter =", " 1): ")), '1')
    eq("terlipat, spasi terpangkas", def(listOf("└ Pilih [1/2/a/3/e/x] (Enter =", "1): ")), '1')
    eq("bawaan di luar daftar -> tanpa sorot", def(listOf("└ Pilih [3/e/x] (Enter = 1): ")), null)
    // kunci teks dialog molina-run asli (hasil tangkapan pty dari run_nano_runprompt.sh)
    if (args.size >= 2) {
        val capFix = File(args[0]).readLines()
        val capTrunc = File(args[1]).readLines()
        eq("molina-run asli (dapat diperbaiki): tombol", keys(capFix), "12a3ex")
        eq("molina-run asli (dapat diperbaiki): bawaan", def(capFix), '1')
        eq("molina-run asli (terpotong): tombol", keys(capTrunc), "12a3ex")
        eq("molina-run asli (terpotong): bawaan", def(capTrunc), 'x')
    }
    println("Selesai: $runs kasus, $fails gagal")
    if (fails > 0) kotlin.system.exitProcess(1)
}

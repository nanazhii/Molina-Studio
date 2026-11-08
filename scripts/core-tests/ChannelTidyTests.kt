import com.molina.core.common.*
import java.io.File
import java.nio.file.Files

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun put(dir: File, name: String, text: String = "x"): File {
    dir.mkdirs()
    return File(dir, name).also { it.writeText(text) }
}

private fun info(dir: File, base: String, json: String) = put(dir, "$base.info.json", json)

/** Pasangan (masukan, keluaran) dihasilkan dari sanitize_folder_name di molina_dl.py: kontrak lintas bahasa. */
private val PARITY: List<Pair<String, String>> = listOf(
        "Molina TV" to "Molina TV",
        "A/B\\C:D*E?" to "A_B_C_D_E_",
        "  banyak   spasi  " to "banyak spasi",
        "..tersembunyi." to "tersembunyi",
        ".." to "Tanpa Kanal",
        "" to "Tanpa Kanal",
        "???" to "Tanpa Kanal",
        "CON" to "_CON",
        "lpt1.txt" to "_lpt1.txt",
        "a\u200Bb\u202Ec" to "abc",
        "-opsi" to "-opsi",
        "../../etc" to "_.._etc",
        "Kanal: A/B" to "Kanal_ A_B",
        "\u540D\u7121\u3057 \u30C1\u30E3\u30F3\u30CD\u30EB" to "\u540D\u7121\u3057 \u30C1\u30E3\u30F3\u30CD\u30EB",
        "Cafe\u0301" to "Caf\u00E9",
        "titik..." to "titik",
        " . " to "Tanpa Kanal",
        "nul." to "_nul",
        "COM3 " to "_COM3",
        "a\u0009b" to "a_b",
        "emoji \uD83C\uDFAC ok" to "emoji \uD83C\uDFAC ok",
        "\u00A0nbsp\u00A0x\u00A0" to "nbsp x",
        "Kanal <Baru> |1|" to "Kanal _Baru_ _1_",
        "___" to "Tanpa Kanal",
        "_ _" to "Tanpa Kanal",
        "Aux.log" to "_Aux.log",
        "trail \u2003em" to "trail em",
        "Kanal A: X/Y" to "Kanal A_ X_Y",
        "Uploader D" to "Uploader D",
)

fun main() {
    // ---- FolderName: sama dengan Python
    for ((input, want) in PARITY) {
        val got = FolderName.sanitize(input)
        check("FolderName: \"" + input.take(24).replace("\u0000", "") + "\" -> \"" + want.take(24) + "\"", got == want)
    }
    check("FolderName: null -> fallback", FolderName.sanitize(null) == FolderName.FALLBACK)
    check("FolderName: 200 x e-aksen dipotong 100 byte tanpa merusak karakter", FolderName.sanitize("\u00E9".repeat(200)) == "\u00E9".repeat(50))
    check("FolderName: 300 x x dipotong 100", FolderName.sanitize("x".repeat(300)) == "x".repeat(100))
    check("FolderName: 40 emoji dipotong ke 25 emoji (100 byte)", FolderName.sanitize("\uD83C\uDFAC".repeat(40)) == "\uD83C\uDFAC".repeat(25))
    val thai = FolderName.sanitize("\u0E2A\u0E27\u0E31\u0E2A\u0E14\u0E35".repeat(30))
    check("FolderName: Thai <= 100 byte dan utuh", thai.toByteArray(Charsets.UTF_8).size <= 100 && thai.codePointCount(0, thai.length) == 33)
    check("FolderName: tidak pernah memuat pemisah path", listOf("a/b", "a\\b", "../x", "..\\x").all { s -> FolderName.sanitize(s).none { it == '/' || it == '\\' } })

    val root = Files.createTempDirectory("tidy").toFile()
    try {
        val resume = File(root, "wl").also { it.mkdirs() }
        val dir = File(root, "Kanal Dir").also { it.mkdirs() }

        // video dengan sidecar lengkap (kanal bernama aneh), dua video sekanal, tanpa info, hanya uploader,
        // bentrok nama, dan yang sudah di folder kanalnya
        put(dir, "Satu [a].mp4"); put(dir, "Satu [a].jpg"); info(dir, "Satu [a]", """{"channel":"Kanal A: X/Y","title":"Satu"}""")
        put(dir, "Dua [b].mkv"); put(dir, "Dua [b].id.srt"); info(dir, "Dua [b]", """{"channel":"Kanal A: X/Y"}""")
        put(dir, "Tiga [c].mp4")
        put(dir, "Empat [d].mp4"); info(dir, "Empat [d]", """{"uploader":"Uploader D"}""")
        put(dir, "Lima [e].mp4"); info(dir, "Lima [e]", """{"channel":"Kanal B"}""")
        put(File(dir, "Kanal B"), "Lima [e].mp4", "lama")
        put(dir, "Enam [f].mp4"); info(dir, "Enam [f]", """{"channel":"kanal dir"}""")
        put(dir, "Tujuh [g].mp4"); put(dir, "Tujuh [g].info.json", "{bukan json")
        put(dir, ".tersembunyi.mp4"); put(dir, "lagu.mp3"); put(dir, "catatan.txt")

        val plan = ChannelTidy.plan(dir)
        check("plan: hanya video dengan info kanal yang bisa dipindah", plan.items.map { it.video.name } == listOf("Dua [b].mkv", "Empat [d].mp4", "Satu [a].mp4"))
        check("plan: folder tujuan memakai FolderName", plan.items.map { it.folder } == listOf("Kanal A_ X_Y", "Uploader D", "Kanal A_ X_Y"))
        check("plan: dua video sekanal = satu folder", plan.channels == 2)
        check("plan: tanpa info atau JSON rusak dihitung", plan.noInfo == 2)
        check("plan: bentrok nama dihitung dan tidak ikut", plan.conflicts == 1 && plan.items.none { it.video.name.startsWith("Lima") })
        check("plan: sudah di folder kanalnya dihitung (tanpa peka huruf besar)", plan.inPlace == 1)
        check("plan: pendamping = poster, info.json, subtitle", plan.items[2].related.map { it.name } == listOf("Satu [a].info.json", "Satu [a].jpg") && plan.items[0].related.map { it.name } == listOf("Dua [b].id.srt", "Dua [b].info.json"))
        check("plan: folder hilang / berkas -> kosong", ChannelTidy.plan(File(root, "gaib")).items.isEmpty() && ChannelTidy.plan(put(root, "bukan-folder.bin")).items.isEmpty())
        check("plan: tidak mengubah apa pun", File(dir, "Satu [a].mp4").isFile && !File(dir, "Kanal A_ X_Y").exists())

        // penanda "dilanjutkan" ikut pindah
        val oldRef = File(dir, "Satu [a].mp4").path
        File(resume, ResumeMark.key(oldRef)).writeText("start=75.5\n")

        // apply: dibatalkan sebelum mulai -> tidak ada perubahan
        val none = ChannelTidy.apply(plan, listOf(resume)) { true }
        check("apply: dibatalkan -> tidak ada yang pindah dan tak ada folder", none.moved.isEmpty() && none.videos == 0 && !File(dir, "Uploader D").exists())

        // apply: pendamping yang sudah ada di tujuan tidak ditimpa, video tetap pindah
        put(File(dir, "Kanal A_ X_Y"), "Satu [a].jpg", "poster-lama")
        val res = ChannelTidy.apply(plan, listOf(resume))
        val ka = File(dir, "Kanal A_ X_Y")
        check("apply: 3 video pindah, 0 gagal", res.videos == 3 && res.failed == 0)
        check("apply: video + pendamping ada di folder kanal", File(ka, "Satu [a].mp4").isFile && File(ka, "Satu [a].info.json").isFile && File(ka, "Dua [b].mkv").isFile && File(ka, "Dua [b].id.srt").isFile && File(dir, "Uploader D/Empat [d].mp4").isFile)
        check("apply: asal sudah kosong", !File(dir, "Satu [a].mp4").exists() && !File(dir, "Dua [b].mkv").exists() && !File(dir, "Dua [b].id.srt").exists())
        check("apply: poster yang sudah ada tidak ditimpa (poster baru tetap di asal)", File(ka, "Satu [a].jpg").readText() == "poster-lama" && File(dir, "Satu [a].jpg").isFile)
        check("apply: yang dilewati tetap di tempat", File(dir, "Tiga [c].mp4").isFile && File(dir, "Lima [e].mp4").isFile && File(dir, "Enam [f].mp4").isFile && File(File(dir, "Kanal B"), "Lima [e].mp4").readText() == "lama")
        check("apply: hanya folder yang dibuat apply yang tercatat (Kanal A_ X_Y sudah ada)", res.created.map { it.name } == listOf("Uploader D"))
        check("apply: penanda dilanjutkan ikut pindah", ResumeMark.readStart(listOf(resume), File(ka, "Satu [a].mp4").path) == 75.5 && ResumeMark.readStart(listOf(resume), oldRef) == null)

        // idempoten: rencana kedua tidak memindah apa pun lagi
        val again = ChannelTidy.plan(dir)
        check("plan kedua: tidak ada lagi yang dipindah", again.items.isEmpty())

        // urungkan
        val back = ChannelTidy.undo(res, listOf(resume))
        check("undo: berkas kembali ke asal", back == res.moved.size && File(dir, "Satu [a].mp4").isFile && File(dir, "Dua [b].id.srt").isFile && File(dir, "Empat [d].mp4").isFile)
        check("undo: folder kanal baru yang kosong dihapus, yang berisi tetap", !File(dir, "Uploader D").exists() && File(dir, "Kanal A_ X_Y").isDirectory && File(ka, "Satu [a].jpg").readText() == "poster-lama")
        check("undo: penanda dilanjutkan kembali ke path asal", ResumeMark.readStart(listOf(resume), oldRef) == 75.5)
        check("undo: pemanggilan kedua tidak mengubah apa pun", ChannelTidy.undo(res, listOf(resume)) == 0)

        // bentrok folder: ada BERKAS bernama sama dengan folder kanal -> dilewati sebagai bentrok
        val dir2 = File(root, "Unduhan").also { it.mkdirs() }
        put(dir2, "V [z].mp4"); info(dir2, "V [z]", """{"channel":"Kanal Z"}"""); put(dir2, "Kanal Z", "berkas")
        val p2 = ChannelTidy.plan(dir2)
        check("plan: berkas bernama sama dengan folder kanal = bentrok", p2.items.isEmpty() && p2.conflicts == 1)
    } finally {
        root.deleteRecursively()
    }
    if (fails > 0) { println("$fails GAGAL"); System.exit(1) }
    println("semua lulus")
}

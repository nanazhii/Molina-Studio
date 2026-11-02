import com.molina.core.common.Assurance
import com.molina.core.common.BundledComponents
import com.molina.core.common.ComponentRole
import com.molina.core.common.NewPipeBump

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    val all = BundledComponents.all("v0.26.5")
    check("id unik", all.map { it.id }.toSet().size == all.size)
    check("lisensi terisi", all.all { it.license.isNotBlank() && !it.license.startsWith("Lihat") })
    check("versi NewPipe disuntikkan", all.first { it.id == "newpipe" }.version == "v0.26.5")
    check("Sha256 wajib punya pinRef", all.filter { it.assurance == Assurance.Sha256 }.all { !it.pinRef.isNullOrBlank() })
    check("tanpa Sha256 tidak boleh punya pinRef", all.filter { it.assurance != Assurance.Sha256 }.all { it.pinRef == null })
    check("sumber tanpa skema URL", all.none { it.source.contains("://") || it.note.contains("://") })
    check("ada komponen Wajib", all.any { it.role == ComponentRole.Wajib })
    check("yt-dlp opsional dan runtime", all.first { it.id == "ytdlp" }.role == ComponentRole.Opsional)
    check("hitung peran", BundledComponents.roleCounts(all).values.sum() == all.size)

    check("saring: null = semua", BundledComponents.filter(all, null).size == all.size)
    check("saring: hanya Wajib", BundledComponents.filter(all, ComponentRole.Wajib).all { it.role == ComponentRole.Wajib })
    check("saring: jumlah sesuai hitungan", ComponentRole.values().all {
        BundledComponents.filter(all, it).size == BundledComponents.roleCounts(all)[it]
    })

    val r = BundledComponents.securityReport(all)
    check("laporan: total", r.total == all.size)
    check("laporan: penjumlahan kategori = total",
        r.hashPinned + r.aptSigned + r.sourceInRepo + r.versionPinned + r.assets == r.total)
    check("laporan: PRoot ikut dipin hash", all.first { it.id == "proot" }.assurance == Assurance.Sha256)
    check("laporan: semua Wajib terverifikasi", r.requiredWithoutHash.isEmpty())
    check("laporan: headline menyebut jumlah", r.headline().contains("${r.hashPinned} dari ${r.total}"))

    val bad = all.map { if (it.id == "terminal") it.copy(assurance = Assurance.Commit) else it }
    check("laporan: Wajib tanpa hash terdeteksi", BundledComponents.securityReport(bad).requiredWithoutHash == listOf("Terminal emulator + view"))

    check("bump: tag valid diterima", listOf("v0.26.6", "v1.0.0", "v12.345.6789").all { NewPipeBump.isValidTag(it) })
    check("bump: tag janggal/injeksi ditolak", listOf(
        "", "v1.2", "1.2.3", "v1.2.3;rm -rf /", "v1.2.3 && id", "v1.2.3\n", " v1.2.3", "v1.2.3 ", "v1.2.3`id`",
        "v\$(id).1.1", "v1.2.3|cat", "V1.2.3", "v1.2.3-rc1", "v99999.1.1"
    ).none { NewPipeBump.isValidTag(it) })
    check("bump: perintah gh benar", NewPipeBump.ghCommand("v0.26.6") == "gh workflow run bump-newpipe.yml -f version=v0.26.6")
    check("bump: tag buruk -> tanpa perintah", NewPipeBump.ghCommand("v1.2.3; id") == null)
    check("bump: perintah tanpa metakarakter shell", NewPipeBump.ghCommand("v0.26.6")!!.none { it in ";&|`$<>()\\\n" })
    check("bump: status pin menyebut versi", NewPipeBump.pinStatus("v0.26.6").contains("v0.26.6"))

    check("bump: ringkasan pin menyebut versi dan CI", NewPipeBump.pinSummary("v0.26.6").let { it.contains("v0.26.6") && it.contains("CI") })
    check("bump: ringkasan tanpa skema URL", !NewPipeBump.pinSummary("v0.26.6").contains("://"))

    check("laporan: requiredVerified true untuk daftar asli", r.requiredVerified)
    check("laporan: requiredVerified false bila Wajib tanpa hash", !BundledComponents.securityReport(bad).requiredVerified)

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}

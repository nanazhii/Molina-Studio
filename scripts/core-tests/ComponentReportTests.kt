import com.molina.core.common.Assurance
import com.molina.core.common.BundledComponents
import com.molina.core.common.ComponentReport
import com.molina.core.common.ComponentRole

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    val all = BundledComponents.all("v0.26.5")
    val sec = BundledComponents.securityReport(all)
    val txt = ComponentReport.format(all, sec, "2026-10-31 15:30", versionOf = { it.version.ifEmpty { "versi belum diperiksa" } })
    val lines = txt.lines()

    check("judul dan waktu", lines[0] == "Molina - laporan komponen" && lines[1] == "Dibuat: 2026-10-31 15:30")
    check("memuat ringkasan keamanan", lines[2] == sec.headline())
    check("semua Wajib terverifikasi disebut", lines[3] == "Semua komponen Wajib terverifikasi.")
    check("tiap komponen tepat sekali", all.all { c -> lines.count { it.startsWith("- " + c.name + " ") } == 1 })
    check("jumlah baris komponen = total", lines.count { it.startsWith("- ") } == all.size)
    check("urutan kelompok Wajib, Pendukung, Opsional", run {
        val idx = ComponentRole.values().map { txt.indexOf(it.label + " (") }
        idx.all { it >= 0 } && idx == idx.sorted()
    })
    check("jumlah per kelompok benar", ComponentRole.values().all { r -> txt.contains("${r.label} (${all.count { it.role == r }})") })
    check("baris komponen memuat asal dan jaminan", lines.first { it.startsWith("- NewPipeExtractor ") }
        == "- NewPipeExtractor v0.26.5 \u00b7 Dibundel di APK \u00b7 SHA-256 dipin dan diverifikasi")
    check("versi kosong memakai pengganti dari pemanggil", lines.first { it.startsWith("- Git ") }.startsWith("- Git versi belum diperiksa \u00b7 "))
    check("tanpa URL", !txt.contains("://"))
    check("diakhiri satu baris baru", txt.endsWith("\n") && !txt.endsWith("\n\n"))

    val long = "ffmpeg version 7.1.5\n  Copyright   (c) " + "x".repeat(300)
    val t2 = ComponentReport.format(all, sec, "t", versionOf = { if (it.id == "ffmpeg") long else it.version })
    val ff = t2.lines().first { it.startsWith("- FFmpeg (Debian) ") }
    check("versi panjang jadi satu baris dan dipotong", ff.contains("ffmpeg version 7.1.5 Copyright (c) x") && ff.contains("\u2026") && !ff.contains("\n"))
    check("potongan versi tidak lebih dari 120 karakter", ff.substringAfter("- FFmpeg (Debian) ").substringBefore(" \u00b7 ").length <= 120)

    val t3 = ComponentReport.format(all, sec, "t", versionOf = { it.version }, extrasOf = { c ->
        if (c.id == "git") listOf("dicek 31 Okt 14:09", "  ", "Pembaruan tersedia: 1 \u2192 2") else emptyList()
    })
    val l3 = t3.lines(); val gi = l3.indexOfFirst { it.startsWith("- Git ") }
    check("baris tambahan menjorok di bawah komponennya", l3[gi + 1] == "    dicek 31 Okt 14:09" && l3[gi + 2] == "    Pembaruan tersedia: 1 \u2192 2")
    check("baris tambahan kosong dibuang", !l3[gi + 3].startsWith("    "))
    check("komponen tanpa tambahan tidak punya baris menjorok", t2.lines().none { it.startsWith("    ") })

    val bad = all.map { if (it.id == "terminal") it.copy(assurance = Assurance.Commit) else it }
    val t4 = ComponentReport.format(bad, BundledComponents.securityReport(bad), "t", versionOf = { it.version })
    check("Wajib tanpa hash ditandai", t4.lines()[3] == "Perlu ditinjau (Wajib tanpa hash): Terminal emulator + view")

    val only = all.filter { it.role == ComponentRole.Wajib }
    val t5 = ComponentReport.format(only, BundledComponents.securityReport(only), "t", versionOf = { it.version })
    check("kelompok kosong tidak dicetak", !t5.contains("Opsional (") && !t5.contains("Pendukung ("))

    check("daftar kosong tidak error", ComponentReport.format(emptyList(), BundledComponents.securityReport(emptyList()), "t", versionOf = { it.version }).startsWith("Molina - laporan komponen"))

    if (fails > 0) { println("\n$fails pemeriksaan GAGAL"); kotlin.system.exitProcess(1) } else println("\nSemua pemeriksaan lulus")
}

import com.molina.code.history.ShadowPolicy

// [EXT-DIFF2] Uji kebijakan titik pantau (JVM murni).
private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    check("rahasia: .env", ShadowPolicy.isSecret(".env") && ShadowPolicy.isSecret("app/.env.local"))
    check("rahasia: kunci", ShadowPolicy.isSecret("k/server.PEM") && ShadowPolicy.isSecret("id_rsa") && ShadowPolicy.isSecret("id_ed25519.pub"))
    check("rahasia: folder .ssh/.aws", ShadowPolicy.isSecret("home/.ssh/config") && ShadowPolicy.isSecret(".aws/config"))
    check("rahasia: credentials/secrets", ShadowPolicy.isSecret("credentials.json") && ShadowPolicy.isSecret("cfg/secrets.yml"))
    check("bukan rahasia: kode biasa", !ShadowPolicy.isSecret("src/main.kt") && !ShadowPolicy.isSecret("README.md") && !ShadowPolicy.isSecret(".gitignore"))
    check("eligible: teks kecil", ShadowPolicy.eligible("a.sh", 100))
    check("eligible: batas 512 KB", ShadowPolicy.eligible("a.txt", 512L * 1024) && !ShadowPolicy.eligible("a.txt", 512L * 1024 + 1))
    check("eligible: biner ditolak", !ShadowPolicy.eligible("x.PNG", 10) && !ShadowPolicy.eligible("lib/a.so", 10))
    check("eligible: rahasia ditolak", !ShadowPolicy.eligible(".env", 10))
    val c = ShadowPolicy.candidates(listOf(
        ShadowPolicy.Entry("old.kt", 10, 1), ShadowPolicy.Entry("new.kt", 10, 9), ShadowPolicy.Entry(".env", 10, 99),
        ShadowPolicy.Entry("big.txt", 9_999_999, 50), ShadowPolicy.Entry("mid.kt", 10, 5)
    ))
    check("candidates: terbaru dulu, tanpa rahasia dan berkas besar", c == listOf("new.kt", "mid.kt", "old.kt"))
    check("sizeLabel: KB dibulatkan ke atas", ShadowPolicy.sizeLabel(0) == "0 KB" && ShadowPolicy.sizeLabel(1) == "1 KB" && ShadowPolicy.sizeLabel(512L * 1024) == "512 KB") // [EXT-DIFF4]
    check("sizeLabel: MB bulat dan desimal", ShadowPolicy.sizeLabel(30L * 1024 * 1024) == "30 MB" && ShadowPolicy.sizeLabel(1572864L) == "1,5 MB")
    check("usageLine: format pemakaian", ShadowPolicy.usageLine(12L * 1024 * 1024) == "Titik pantau: 12 MB dari 30 MB")
    check("EXCLUSION_NOTE: menyebut batas berkas dan .env", ShadowPolicy.EXCLUSION_NOTE.contains("512 KB") && ShadowPolicy.EXCLUSION_NOTE.contains(".env"))
    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}

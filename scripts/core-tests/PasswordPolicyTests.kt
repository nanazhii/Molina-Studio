import com.molina.core.common.PasswordPolicy
import com.molina.core.common.PasswordPolicy.Strength

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    val e = PasswordPolicy::evaluate
    check("kosong ditolak", !e("").acceptable)
    check("kurang dari 8 ditolak", !e("Ab1!xyz").acceptable)
    check("password/12345678 ditolak", !e("password").acceptable && !e("12345678").acceptable)
    check("urutan ditolak (abcdefgh, 87654321)", !e("abcdefgh").acceptable && !e("87654321").acceptable)
    check("berulang ditolak", !e("aaaaaaaaaaaa").acceptable)
    check("baris baru ditolak (stdin 7z satu baris)", !e("abcdefgh\nXy9!zzzz").acceptable)
    check("8 huruf kecil acak: lemah tapi boleh", e("kucingku").acceptable && e("kucingku").strength == Strength.WEAK)
    check("campuran 11 karakter diterima", e("Kopi#Susu42").acceptable)
    check("16 karakter acak = kuat", e("t7!Qm-vR2x@Lp9Zd").strength == Strength.STRONG)
    check("non-ASCII diterima", e("sandi-rahasia\u00e9\u00f1").acceptable)
    val g1 = PasswordPolicy.generate(); val g2 = PasswordPolicy.generate()
    check("generate: panjang bawaan 20", g1.length == PasswordPolicy.GENERATED_LENGTH)
    check("generate: dua hasil berbeda", g1 != g2)
    check("generate: tanpa spasi/baris baru/kutip", (g1 + g2).none { it == ' ' || it == '\n' || it == '\r' || it == '"' || it == '\'' })
    check("generate: lolos kebijakan dan >= cukup", e(g1).acceptable && e(g1).strength >= Strength.FAIR)
    check("generate: batas panjang 12..64", PasswordPolicy.generate(1).length == 12 && PasswordPolicy.generate(999).length == 64)
    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) System.exit(1)
}

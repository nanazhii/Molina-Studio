import com.molina.code.check.CheckException
import com.molina.code.check.MiniJson as CheckJson
import com.molina.code.guard.MiniJson as GuardJson
import com.molina.core.json.MiniJson as CoreJson

// Tes KARAKTERISASI tiga MiniJson (Fase E). Mengunci perilaku SAAT INI, bukan perilaku ideal:
//   core  = com.molina.core.json.MiniJson.parseObject  (objek saja, rusak -> null)
//   guard = com.molina.code.guard.MiniJson.valid       (validator RFC 8259 ketat, Boolean)
//   check = com.molina.code.check.MiniJson.parse       (nilai apa pun, rusak -> CheckException)
// Bagian "DIVERGEN" sengaja mengunci perbedaan; bila penyatuan mengubahnya, ubah tes itu secara sadar.

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun core(s: String): Map<String, Any?>? = CoreJson.parseObject(s)
private fun guard(s: String): Boolean = GuardJson.valid(s)
private fun chk(s: String): Any? = CheckJson.parse(s)
private fun chkFails(s: String): String? = try { CheckJson.parse(s); null } catch (e: CheckException) { e.message }
private fun maxDepth(ok: (Int) -> Boolean): Int { var n = 1; while (ok(n + 1)) n++; return n }

fun main() {
    // ---- KONTRAK BERSAMA: ketiganya sepakat
    check("sama: objek kosong", core("{}") == emptyMap<String, Any?>() && guard("{}") && chk("{}") == emptyMap<String, Any?>())
    check("sama: spasi ' \\n\\r\\t' diterima", core(" \n\r\t{ \"a\" : 1 }\n")?.size == 1 && guard(" \n\r\t{ \"a\" : 1 }\n") && (chk(" \n\r\t{ \"a\" : 1 }\n") as Map<*, *>).size == 1)
    val sample = "{\"a\":[1,2,[3]],\"b\":{\"c\":true,\"d\":null},\"e\":\"x\"}"
    check("sama: struktur bersarang sah", core(sample) != null && guard(sample) && chk(sample) is Map<*, *>)
    check("sama: kunci ganda -> terakhir menang", core("{\"a\":1,\"a\":2}")?.get("a") == 2L && guard("{\"a\":1,\"a\":2}") && (chk("{\"a\":1,\"a\":2}") as Map<*, *>)["a"] == 2.0)
    for ((n, s) in listOf(
        "koma menggantung objek" to "{\"a\":1,}", "koma menggantung larik" to "{\"a\":[1,2,]}", "koma di awal larik" to "{\"a\":[,1]}",
        "kutip tunggal" to "{'a':1}", "kunci tanpa nilai" to "{\"a\"}", "nilai kosong" to "{\"a\":}", "objek hanya koma" to "{,}",
        "teks sisa" to "{\"a\":1} x", "terpotong" to "{\"a\":[1,2", "literal tru" to "{\"a\":tru}", "literal nul" to "{\"a\":nul}",
        "NaN" to "{\"a\":NaN}", "Infinity" to "{\"a\":Infinity}", "escape tak dikenal" to "{\"a\":\"\\x\"}", "angka '-' saja" to "{\"a\":-}", "eksponen kosong" to "{\"a\":1e}",
    )) {
        check("sama: tolak $n", core(s) == null && !guard(s) && chkFails(s) != null)
    }
    val e = (core("{\"a\":\"\\u00e9\"}")!!["a"] as String)
    check("sama: \\u00e9 -> U+00E9", e.length == 1 && e[0].code == 0xE9 && (chk("\"\\u00e9\"") as String)[0].code == 0xE9)
    check("sama: pasangan surrogat -> 2 char", (core("{\"a\":\"\\uD83D\\uDE00\"}")!!["a"] as String).length == 2 && (chk("\"\\uD83D\\uDE00\"") as String).length == 2 && guard("\"\\uD83D\\uDE00\""))
    check("sama: angka desimal/eksponen bernilai sama", core("{\"a\":1.5,\"b\":-3e2}")!!.let { it["a"] == 1.5 && it["b"] == -300.0 } && (chk("{\"a\":1.5,\"b\":-3e2}") as Map<*, *>).let { it["a"] == 1.5 && it["b"] == -300.0 })

    // ---- DIVERGEN: perbedaan yang terkunci (calon keputusan penyatuan)
    // bentuk keluaran
    check("DIVERGEN bentuk: core objek-saja (larik/skalar -> null)", core("[]") == null && core("1") == null && core("\"x\"") == null && core("null") == null && core("true") == null)
    check("DIVERGEN bentuk: guard & check menerima nilai tingkat-atas apa pun", listOf("[]", "1", "\"x\"", "null", "true").all { guard(it) } && chk("[]") == emptyList<Any?>() && chk("1") == 1.0 && chk("\"x\"") == "x" && chk("null") == null && chk("true") == true)
    check("DIVERGEN galat: string kosong (core null, guard false, check 'JSON rusak')", core("") == null && !guard("") && chkFails("") == "JSON rusak")
    check("DIVERGEN galat: teks sisa dibedakan hanya oleh check ('sisa teks')", chkFails("{\"a\":1} x") == "sisa teks" && chkFails("{'a':1}") == "JSON rusak")
    // angka
    check("DIVERGEN angka: core Long untuk bilangan bulat, check selalu Double", core("{\"a\":1}")!!["a"] == 1L && (chk("{\"a\":1}") as Map<*, *>)["a"] == 1.0)
    check("DIVERGEN angka: bilangan bulat > Long -> Double di core dan check", core("{\"a\":99999999999999999999}")!!["a"] == 1.0E20 && (chk("{\"a\":99999999999999999999}") as Map<*, *>)["a"] == 1.0E20 && guard("{\"a\":99999999999999999999}"))
    check("DIVERGEN angka: 1e999 (core tolak, guard sah, check Infinity)", core("{\"a\":1e999}") == null && guard("{\"a\":1e999}") && (chk("{\"a\":1e999}") as Map<*, *>)["a"] == Double.POSITIVE_INFINITY)
    check("DIVERGEN angka: nol di depan '01' (core 1, guard tolak, check 1.0)", core("{\"a\":01}")!!["a"] == 1L && !guard("{\"a\":01}") && (chk("{\"a\":01}") as Map<*, *>)["a"] == 1.0)
    check("DIVERGEN angka: '+1' (core 1, guard tolak, check tolak)", core("{\"a\":+1}")!!["a"] == 1L && !guard("{\"a\":+1}") && chkFails("{\"a\":+1}") != null)
    check("DIVERGEN angka: '.5' (core 0.5, guard tolak, check tolak)", core("{\"a\":.5}")!!["a"] == 0.5 && !guard("{\"a\":.5}") && chkFails("{\"a\":.5}") != null)
    check("DIVERGEN angka: '1.' (core 1.0, guard tolak, check 1.0)", core("{\"a\":1.}")!!["a"] == 1.0 && !guard("{\"a\":1.}") && (chk("{\"a\":1.}") as Map<*, *>)["a"] == 1.0)
    check("guard: angka sah -0, 0.0, 1E+2, 1e-2; tolak -, 0e, [01], [1.e2], [-]", listOf("-0", "0.0", "1E+2", "1e-2").all { guard(it) } && listOf("-", "0e", "[01]", "[1.e2]", "[-]").none { guard(it) })
    // string
    check("DIVERGEN string: karakter kontrol mentah (core & guard tolak, check terima)", core("{\"a\":\"t\there\"}") == null && !guard("{\"a\":\"t\there\"}") && (chk("{\"a\":\"t\there\"}") as Map<*, *>)["a"] == "t\there" && (chk("{\"a\":\"n\nl\"}") as Map<*, *>)["a"] == "n\nl")
    check("DIVERGEN string: \\u+123 (core & guard tolak, check terima -> U+0123)", core("{\"a\":\"\\u+123\"}") == null && !guard("\"\\u+123\"") && (chk("\"\\u+123\"") as String)[0].code == 0x123)
    check("guard: \\uABCD sah, \\uABC tolak", guard("\"\\uABCD\"") && !guard("\"\\uABC\""))
    // awalan & spasi
    check("DIVERGEN BOM: hanya guard membuang BOM", guard("\uFEFF{\"a\":1}") && core("\uFEFF{\"a\":1}") == null && chkFails("\uFEFF{\"a\":1}") != null && !guard("\uFEFF") && !guard("[\uFEFF]"))
    check("DIVERGEN spasi: \\u000B dan NBSP hanya diterima check (isWhitespace)", chk("{\"a\":1}\u000B") is Map<*, *> && chk("\u00A0{}") is Map<*, *> && core("{\"a\":1}\u000B") == null && core("\u00A0{}") == null && !guard("{\"a\":1}\u000B") && !guard("\u00A0{}"))
    // batas
    check("DIVERGEN kedalaman: core 32 (objek & larik-dalam-objek)", maxDepth { n -> core("{\"a\":".repeat(n) + "1" + "}".repeat(n)) != null } == 32 && maxDepth { n -> core("{\"a\":" + "[".repeat(n) + "]".repeat(n) + "}") != null } == 32)
    check("DIVERGEN kedalaman: guard 200, check 65 (larik bersarang)", maxDepth { n -> guard("[".repeat(n) + "]".repeat(n)) } == 200 && maxDepth { n -> chkFails("[".repeat(n) + "]".repeat(n)) == null } == 65)
    check("DIVERGEN ukuran: core menolak > 4_000_000 karakter, tepat 4_000_000 diterima", core("{\"a\":\"" + "x".repeat(4_000_000 - 8) + "\"}") != null && core("{\"a\":\"" + "x".repeat(4_000_000 - 7) + "\"}") == null)

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}

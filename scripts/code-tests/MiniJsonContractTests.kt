import com.molina.core.json.JsonException
import com.molina.core.json.JsonLimits
import com.molina.core.json.MiniJson

// Tes KONTRAK parser JSON bersama (:core:json, Fase E-2). Menggantikan tes karakterisasi tiga MiniJson lama.
// Kontrak: RFC 8259 ketat; tiga preset batas (YTDLP, TOOL, EDITOR) hanya berbeda pada ukuran, kedalaman, dan mode angka.

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun obj(s: String, l: JsonLimits = JsonLimits.YTDLP): Map<String, Any?>? = MiniJson.parseObject(s, l)
private fun value(s: String, l: JsonLimits = JsonLimits.TOOL): Any? = MiniJson.parseValue(s, l)
private fun err(s: String, l: JsonLimits = JsonLimits.TOOL): String? = try { MiniJson.parseValue(s, l); null } catch (e: JsonException) { e.message }
private fun valid(s: String): Boolean = MiniJson.isValid(s)
private fun maxDepth(ok: (Int) -> Boolean): Int { var n = 1; while (ok(n + 1)) n++; return n }

fun main() {
    val presets = listOf("YTDLP" to JsonLimits.YTDLP, "TOOL" to JsonLimits.TOOL, "EDITOR" to JsonLimits.EDITOR)

    // ---- sah di semua preset
    check("objek kosong", presets.all { (_, l) -> obj("{}", l) == emptyMap<String, Any?>() } && valid("{}"))
    val ws = " \n\r\t{ \"a\" : 1 }\n"
    check("spasi ' \\n\\r\\t' diterima", presets.all { (_, l) -> obj(ws, l)?.size == 1 } && valid(ws))
    val sample = "{\"a\":[1,2,[3]],\"b\":{\"c\":true,\"d\":null},\"e\":\"x\"}"
    check("struktur bersarang sah", presets.all { (_, l) -> obj(sample, l) != null } && valid(sample))
    check("kunci ganda: terakhir menang", obj("{\"a\":1,\"a\":2}")?.get("a") == 2L && (value("{\"a\":1,\"a\":2}") as Map<*, *>)["a"] == 2.0)
    check("\\u00e9 menjadi U+00E9", (obj("{\"a\":\"\\u00e9\"}")!!["a"] as String).let { it.length == 1 && it[0].code == 0xE9 })
    check("pasangan surrogat menjadi 2 char", (obj("{\"a\":\"\\uD83D\\uDE00\"}")!!["a"] as String).length == 2 && valid("\"\\uD83D\\uDE00\""))
    check("escape dasar", obj("{\"a\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"}")!!["a"] == "\"\\/\b\u000C\n\r\t")

    // ---- tolak di semua preset
    for ((n, s) in listOf(
        "koma menggantung objek" to "{\"a\":1,}", "koma menggantung larik" to "{\"a\":[1,2,]}", "koma di awal larik" to "{\"a\":[,1]}",
        "kutip tunggal" to "{'a':1}", "kunci tanpa nilai" to "{\"a\"}", "nilai kosong" to "{\"a\":}", "objek hanya koma" to "{,}",
        "teks sisa" to "{\"a\":1} x", "terpotong" to "{\"a\":[1,2", "literal tru" to "{\"a\":tru}", "literal nul" to "{\"a\":nul}",
        "NaN" to "{\"a\":NaN}", "Infinity" to "{\"a\":Infinity}", "escape tak dikenal" to "{\"a\":\"\\x\"}",
        "angka '-' saja" to "{\"a\":-}", "eksponen kosong" to "{\"a\":1e}", "nol di depan" to "{\"a\":01}", "plus di depan" to "{\"a\":+1}",
        "titik di depan" to "{\"a\":.5}", "titik di belakang" to "{\"a\":1.}", "karakter kontrol mentah" to "{\"a\":\"t\there\"}",
        "baris baru mentah" to "{\"a\":\"n\nl\"}", "\\u+123" to "{\"a\":\"\\u+123\"}", "\\uABC pendek" to "{\"a\":\"\\uABC\"}",
        "spasi \\u000B di luar string" to "{\"a\":1}\u000B", "NBSP di depan" to "\u00A0{}", "kosong" to "",
    )) {
        check("tolak $n", presets.all { (_, l) -> obj(s, l) == null && err(s, l) != null } && !valid(s))
    }

    // ---- bentuk keluaran
    check("parseObject: bukan objek -> null", listOf("[]", "1", "\"x\"", "null", "true", "").all { obj(it) == null })
    check("parseValue: nilai tingkat-atas apa pun", value("[]") == emptyList<Any?>() && value("1") == 1.0 && value("\"x\"") == "x" && value("null") == null && value("true") == true)
    check("isValid: nilai tingkat-atas apa pun", listOf("[]", "1", "\"x\"", "null", "true").all { valid(it) })

    // ---- pesan galat stabil
    check("galat: 'JSON rusak' dan 'sisa teks'", err("") == "JSON rusak" && err("{'a':1}") == "JSON rusak" && err("{\"a\":1} x") == "sisa teks")

    // ---- angka
    check("AUTO: bulat Long, pecahan/eksponen Double", obj("{\"a\":1,\"b\":1.5,\"c\":-3e2}")!!.let { it["a"] == 1L && it["b"] == 1.5 && it["c"] == -300.0 })
    check("AUTO: bulat > Long menjadi Double", obj("{\"a\":99999999999999999999}")!!["a"] == 1.0E20)
    check("ALWAYS_DOUBLE: semua angka Double", (value("{\"a\":1,\"b\":1.5}") as Map<*, *>).let { it["a"] == 1.0 && it["b"] == 1.5 })
    check("1e999: parse menolak, isValid menerima (sintaks sah)", obj("{\"a\":1e999}") == null && err("{\"a\":1e999}") != null && valid("{\"a\":1e999}"))
    check("isValid: -0, 0.0, 1E+2, 1e-2 sah; -, 0e, [01], [1.e2], [-] ditolak", listOf("-0", "0.0", "1E+2", "1e-2").all { valid(it) } && listOf("-", "0e", "[01]", "[1.e2]", "[-]").none { valid(it) })

    // ---- BOM: hanya isValid yang membuangnya
    check("BOM: isValid membuang, parseValue menolak", valid("\uFEFF{\"a\":1}") && err("\uFEFF{\"a\":1}") != null && !valid("\uFEFF") && !valid("[\uFEFF]"))

    // ---- batas
    check("kedalaman YTDLP = 32", maxDepth { n -> obj("{\"a\":".repeat(n) + "1" + "}".repeat(n)) != null } == 32)
    check("kedalaman TOOL = 64", maxDepth { n -> err("[".repeat(n) + "]".repeat(n)) == null } == 64)
    check("kedalaman EDITOR = 200", maxDepth { n -> valid("[".repeat(n) + "]".repeat(n)) } == 200)
    check("ukuran YTDLP: tepat 4_000_000 diterima, lebih ditolak", obj("{\"a\":\"" + "x".repeat(4_000_000 - 8) + "\"}") != null && obj("{\"a\":\"" + "x".repeat(4_000_000 - 7) + "\"}") == null)
    check("ukuran: lebih dari maxChars -> 'terlalu besar'", err("[1,2,3]", JsonLimits(5, 8, JsonLimits.TOOL.numbers)) == "terlalu besar")

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}

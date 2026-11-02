import com.molina.core.common.IntentToken

private var fails = 0
private var runs = 0
private fun check(name: String, ok: Boolean) { runs++; if (!ok) { fails++; println("GAGAL: $name") } }

fun main() {
    val t = "0123456789abcdef0123456789abcdef"
    check("sama persis lolos", IntentToken.matches(t, t))
    check("beda satu karakter ditolak", !IntentToken.matches(t, "0123456789abcdef0123456789abcdeg"))
    check("huruf besar ditolak (peka huruf)", !IntentToken.matches(t, t.uppercase()))
    check("given null ditolak", !IntentToken.matches(t, null))
    check("expected null ditolak", !IntentToken.matches(null, t))
    check("given kosong ditolak", !IntentToken.matches(t, ""))
    check("kedua kosong ditolak", !IntentToken.matches("", ""))
    check("expected pendek ditolak walau sama", !IntentToken.matches("abc", "abc"))
    check("batas 31 ditolak", !IntentToken.matches("a".repeat(31), "a".repeat(31)))
    check("batas 32 lolos", IntentToken.matches("a".repeat(32), "a".repeat(32)))
    check("prefix ditolak", !IntentToken.matches(t, t.take(16)))
    check("sufiks tambahan ditolak", !IntentToken.matches(t, t + "0"))
    check("spasi ditolak", !IntentToken.matches(t, " $t"))

    val e = IntentToken.encode(byteArrayOf(0, 1, 15, 16, -1, 127, -128))
    check("encode heks kecil", e == "00010f10ff7f80")
    check("encode 16 byte = 32 karakter", IntentToken.encode(ByteArray(16)).length == IntentToken.MIN_LENGTH)
    check("encode kosong", IntentToken.encode(ByteArray(0)) == "")
    check("hasil encode 16 byte sah sebagai token", IntentToken.matches(IntentToken.encode(ByteArray(16) { 7 }), IntentToken.encode(ByteArray(16) { 7 })))
    check("dua token berbeda tidak saling cocok", !IntentToken.matches(IntentToken.encode(ByteArray(16) { 1 }), IntentToken.encode(ByteArray(16) { 2 })))

    println(if (fails == 0) "LULUS: $runs kasus" else "$fails dari $runs kasus GAGAL")
    if (fails != 0) System.exit(1)
}

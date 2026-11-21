import com.molina.core.policy.IntentExtras
import com.molina.core.policy.PlaybackSourcePolicy
import com.molina.core.policy.SourceKind
import com.molina.core.policy.SourceScheme
import java.io.File
import java.nio.file.Files

// Tes JVM murni untuk kebijakan sumber intent MPV (PlaybackSourcePolicy) dan aturan extra (IntentExtras).

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    val base = Files.createTempDirectory("molina-policy").toFile().canonicalFile
    val data = File(base, "data/app").apply { mkdirs() }
    val sibling = File(base, "data/app2").apply { mkdirs() }
    val outside = File(base, "sdcard").apply { mkdirs() }
    File(data, "secret.txt").writeText("x")
    val link = File(outside, "pintas")
    Files.createSymbolicLink(link.toPath(), data.toPath())

    val seen = ArrayList<String>()
    var urlOk = true
    val p = PlaybackSourcePolicy(
        dataDir = data.path,
        urlAllowed = { seen.add(it); urlOk },
        fileUriPath = { it.removePrefix("file://") },
    )

    // ---- path lokal
    check("path lokal di luar data privat diterima", p.isAllowedCustomPath(File(outside, "a.mp4").path))
    check("path di data privat ditolak", !p.isAllowedCustomPath(File(data, "secret.txt").path))
    check("akar data privat itu sendiri ditolak", !p.isAllowedCustomPath(data.path))
    check("symlink ke data privat ditolak", !p.isAllowedCustomPath(File(link, "secret.txt").path))
    check("direktori bernama mirip (app2) tidak tertukar", p.isAllowedCustomPath(File(sibling, "a.mp4").path))
    check("traversal ../ ke data privat ditolak", !p.isAllowedCustomPath(File(outside, "../data/app/secret.txt").path))
    check("kosong/blank ditolak", !p.isAllowedCustomPath("") && !p.isAllowedCustomPath("   ") && !p.isOutsidePrivateData(null) && !p.isOutsidePrivateData(" "))

    // ---- skema
    check("skema tak dikenal ditolak", listOf("ftp://x/a", "tcp://x", "udp://x", "javascript://x", "memory2://x").none { p.isAllowedCustomPath(it) })
    urlOk = true; seen.clear()
    check("http diteruskan ke kebijakan URL dan diterima", p.isAllowedCustomPath("http://host/v.mp4") && seen == listOf("http://host/v.mp4"))
    check("skema huruf besar dinormalkan", p.isAllowedCustomPath("HTTPS://host/v.mp4"))
    check("content:// diteruskan ke kebijakan URL", p.isAllowedCustomPath("content://media/1"))
    urlOk = false
    check("kebijakan URL menolak -> ditolak", !p.isAllowedCustomPath("rtsp://10.0.0.1/s") && !p.isAllowedCustomPath("https://host/v"))
    urlOk = true
    check("file:// di luar data privat diterima", p.isAllowedCustomPath("file://" + File(outside, "a.mp4").path))
    check("file:// ke data privat ditolak", !p.isAllowedCustomPath("file://" + File(data, "secret.txt").path))
    val pNull = PlaybackSourcePolicy(data.path, { true }, { null })
    check("file:// tanpa path terbaca ditolak", !pNull.isAllowedCustomPath("file://x"))

    // ---- E-3b-2: skema tanpa "//" bukan path lokal
    val scheme1 = listOf("data:text/plain,x", "DATA:text/plain;base64,AAAA", "file:" + File(data, "secret.txt").path, "http:host/v.mp4", "rtsp:x", "ftp:x", "a+b.c-d:x")
    check("E-3b-2: skema tanpa // ditolak sebagai path lokal", scheme1.none { p.isAllowedCustomPath(it) })
    check("E-3b-2: path absolut bertitik dua tetap diterima", p.isAllowedCustomPath(File(outside, "a: b.mp3").path) && p.isAllowedCustomPath(File(outside, "x:y").path))
    check("E-3b-2: entri memory:// bergaya data: menolak seluruh playlist", !p.isAllowedCustomPath("memory://#EXTM3U\n" + File(outside, "1.mp4").path + "\ndata:text/plain,x"))
    check("E-3b-2: path relatif tanpa skema tetap sama seperti sebelumnya", p.isAllowedCustomPath("relatif/a.mp4") && p.isAllowedCustomPath("a.mp4"))

    // ---- playlist memory://
    val ok1 = File(outside, "1.mp4").path
    val ok2 = File(outside, "2.mp4").path
    val bad = File(data, "secret.txt").path
    check("memory: semua baris sah diterima", p.isAllowedCustomPath("memory://#EXTM3U\n$ok1\n$ok2"))
    check("memory: header huruf besar/kecil", p.isAllowedCustomPath("MEMORY://#extm3u\n$ok1"))
    check("memory: CRLF dan komentar dilewati", p.isAllowedCustomPath("memory://#EXTM3U\r\n#EXTINF:1,x\r\n$ok1\r\n"))
    check("memory: satu baris buruk menolak seluruhnya", !p.isAllowedCustomPath("memory://#EXTM3U\n$ok1\n$bad"))
    check("memory: skema memory di dalam isi ditolak (cegah rekursi)", !p.isAllowedCustomPath("memory://#EXTM3U\nmemory://#EXTM3U\n$ok1"))
    check("memory: tanpa header ditolak", !p.isAllowedCustomPath("memory://$ok1"))
    check("memory: tanpa entri ditolak", !p.isAllowedCustomPath("memory://#EXTM3U") && !p.isAllowedCustomPath("memory://#EXTM3U\n#c\n\n"))
    val n = PlaybackSourcePolicy.MAX_MEMORY_PLAYLIST_ENTRIES
    check("memory: tepat batas diterima, lebih ditolak", p.isAllowedCustomPath("memory://#EXTM3U\n" + (1..n).joinToString("\n") { ok1 }) && !p.isAllowedCustomPath("memory://#EXTM3U\n" + (1..n + 1).joinToString("\n") { ok1 }))

    // ---- IntentExtras
    check("start: nol/negatif null; ms -> detik", IntentExtras.startOption(0) == null && IntentExtras.startOption(-5) == null && IntentExtras.startOption(1500) == "1.5" && IntentExtras.startOption(999) == "0.999" && IntentExtras.startOption(100000) == "100.0")
    check("judul: null/kosong -> null", IntentExtras.titleOption(null) == null && IntentExtras.titleOption("") == null)
    check("judul: karakter kontrol dan DEL dibuang", IntentExtras.titleOption("a\nb\u0000c\u007fd\te") == "abcde")
    check("judul: dipotong 200", IntentExtras.titleOption("x".repeat(500))!!.length == 200)
    check("judul: hanya kontrol -> string kosong (perilaku lama dipertahankan)", IntentExtras.titleOption("\n\n") == "")
    check("header: berpasangan, elemen ganjil terakhir dibuang", IntentExtras.pairHeaders(arrayOf("A", "1", "B", "2", "C")) == mapOf("A" to "1", "B" to "2"))
    check("header: null jadi string kosong, duplikat terakhir menang", IntentExtras.pairHeaders(arrayOf(null, "x", "K", null, "K", "v")) == mapOf("" to "x", "K" to "v"))
    check("header: maksimal 64 elemen (32 pasangan)", IntentExtras.pairHeaders(Array(100) { "e$it" }).size == 32 && IntentExtras.pairHeaders(emptyArray<String?>()).isEmpty())

    // ---- E-3b-3a: klasifikasi skema URI (SourceScheme)
    check("E-3b-3a: file -> LOCAL_FILE, content -> CONTENT", SourceScheme.classify("file") == SourceKind.LOCAL_FILE && SourceScheme.classify("content") == SourceKind.CONTENT)
    check("E-3b-3a: http/https/rtmp/rtmps/rtsp -> NETWORK", listOf("http", "https", "rtmp", "rtmps", "rtsp").all { SourceScheme.classify(it) == SourceKind.NETWORK })
    check("E-3b-3a: data/tcp/udp/ftp/mms/rtp/memory ditolak", listOf("data", "tcp", "udp", "ftp", "mms", "rtp", "memory").all { SourceScheme.classify(it) == SourceKind.REJECTED })
    check("E-3b-3a: null dan kosong ditolak", SourceScheme.classify(null) == SourceKind.REJECTED && SourceScheme.classify("") == SourceKind.REJECTED)
    check("E-3b-3a: huruf besar ditolak (perilaku lama dipertahankan)", SourceScheme.classify("HTTP") == SourceKind.REJECTED && SourceScheme.classify("File") == SourceKind.REJECTED)
    check("E-3b-3a: ALLOWED_SCHEMES = NETWORK + content + file (satu sumber kebenaran)", PlaybackSourcePolicy.ALLOWED_SCHEMES == SourceScheme.NETWORK_SCHEMES + setOf("content", "file"))

    base.deleteRecursively()
    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}

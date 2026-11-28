import com.molina.code.history.HistoryKind
import com.molina.code.history.HistoryStore
import com.molina.code.history.NanoInbox
import java.io.File
import java.nio.file.Files

// [NANO-INBOX] Uji impor inbox snapshot nano ke Riwayat (JVM murni).
private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun drop(inbox: File, id: String, guestPath: String, content: ByteArray) {
    inbox.mkdirs()
    File(inbox, "$id.snap").writeBytes(content)
    File(inbox, "$id.path").writeText(guestPath + "\n", Charsets.UTF_8)
}

private fun empty(inbox: File) = (inbox.listFiles() ?: emptyArray()).isEmpty()

fun main() {
    val tmp = Files.createTempDirectory("nanoinbox").toFile()
    try {
        val sd = File(tmp, "sd").apply { mkdirs() }
        val inbox = File(sd, "Molina/.history-inbox")
        val store = HistoryStore(File(tmp, "history")) { 1_000L }
        val target = File(sd, "Molina/skrip.sh")

        check("hostFile: /sdcard/x -> akar storage", NanoInbox.hostFile("/sdcard/Molina/skrip.sh", sd) == target)
        check("hostFile: /storage/emulated/0/x", NanoInbox.hostFile("/storage/emulated/0/a/b.txt", sd) == File(sd, "a/b.txt"))
        check("hostFile: tolak ..", NanoInbox.hostFile("/sdcard/../etc/passwd", sd) == null)
        check("hostFile: di luar storage = null", NanoInbox.hostFile("/root/a.sh", sd) == null)
        check("hostFile: relatif = null", NanoInbox.hostFile("a.sh", sd) == null)
        check("hostFile: /sdcard saja = null", NanoInbox.hostFile("/sdcard", sd) == null)

        drop(inbox, "0000000005000_n_1_1", "/sdcard/Molina/skrip.sh", "satu\r\ndua\r\n".toByteArray())
        drop(inbox, "0000000006000_o_1_2", "/sdcard/Molina/skrip.sh", "satu\ndua\ntiga\n".toByteArray())
        check("import: 2 versi dicatat", NanoInbox.import(inbox, store, sd, now = 10_000L) == 2)
        val vs = store.list(target)
        check("jenis benar, terbaru dulu", vs.size == 2 && vs[0].kind == HistoryKind.NANO_SAVED && vs[1].kind == HistoryKind.NANO_BEFORE)
        check("waktu asli (ms dari nama) dipakai", vs.size == 2 && vs[1].time == 5000L && vs[0].time == 6000L)
        check("CRLF dinormalkan ke LF", vs.size == 2 && store.read(target, vs[1]) == "satu\ndua\n")
        check("isi sesudah nano", vs.size == 2 && store.read(target, vs[0]) == "satu\ndua\ntiga\n")
        check("inbox dibersihkan", empty(inbox))

        drop(inbox, "0000000007000_n_2_1", "/sdcard/Molina/skrip.sh", "satu\ndua\ntiga\n".toByteArray())
        check("duplikat dilewati, inbox tetap dibersihkan", NanoInbox.import(inbox, store, sd, 10_000L) == 0 && empty(inbox))

        drop(inbox, "0000000008000_n_3_1", "/sdcard/Molina/bin.dat", byteArrayOf(1, 0, 2))
        check("biner (NUL) dilewati", NanoInbox.import(inbox, store, sd, 10_000L) == 0 && store.list(File(sd, "Molina/bin.dat")).isEmpty() && empty(inbox))

        drop(inbox, "0000000008100_n_3_2", "/sdcard/Molina/latin.txt", byteArrayOf(0xC3.toByte(), 0x28))
        check("UTF-8 tak valid dilewati", NanoInbox.import(inbox, store, sd, 10_000L) == 0 && empty(inbox))

        drop(inbox, "0000000009000_x_4_1", "/sdcard/Molina/z.txt", "z\n".toByteArray())
        drop(inbox, "0000000010000_n_5_1", "/root/z.txt", "z\n".toByteArray())
        check("jenis tak dikenal & path luar storage dilewati", NanoInbox.import(inbox, store, sd, 10_000L) == 0 && empty(inbox))

        File(inbox, "0000000011000_n_6_1.path").writeText("/sdcard/Molina/y.txt\n")
        check(".path tanpa .snap dibuang", NanoInbox.import(inbox, store, sd, 10_000L) == 0 && empty(inbox))

        drop(inbox, "0000000011500_o_6_2", "/sdcard/Molina/besar.txt", ByteArray(2_500_000) { 'a'.code.toByte() })
        check("terlalu besar dilewati", NanoInbox.import(inbox, store, sd, 10_000L) == 0 && empty(inbox))

        val orphanNew = File(inbox, "0000000012000_n_7_1.snap").apply { writeText("x") }
        val orphanOld = File(inbox, "0000000012001_n_8_1.snap").apply { writeText("x"); setLastModified(1_000L) }
        NanoInbox.import(inbox, store, sd, now = 4_001_000L)
        check("yatim baru dibiarkan, yatim lama dihapus", orphanNew.exists() && !orphanOld.exists())

        check("inbox tidak ada = 0", NanoInbox.import(File(tmp, "none"), store, sd) == 0)

        val first = store.record(File(sd, "Molina/t.txt"), "a\n", HistoryKind.SAVED)!!
        val v2 = store.record(File(sd, "Molina/t.txt"), "b\n", HistoryKind.NANO_SAVED, at = 1L)!!
        check("at lebih kecil dari versi terakhir: waktu tetap naik", v2.time == first.time + 1)
    } finally {
        tmp.deleteRecursively()
    }
    if (fails > 0) { println("$fails GAGAL"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}

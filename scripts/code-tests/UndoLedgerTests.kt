import com.molina.code.history.HistoryKind
import com.molina.code.history.HistoryStore
import com.molina.code.history.UndoLedger
import java.io.File
import java.nio.file.Files

// [EXT-DIFF5] Uji buku catatan Urungkan (JVM murni).
private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    val tmp = Files.createTempDirectory("undoledger").toFile()
    try {
        val lf = File(tmp, "sub/undo.tsv")
        val a = UndoLedger(lf)
        check("kosong: get null", a.get("/x/a.txt") == null)
        a.put("/x/a.txt", "aaaaaaaaaaaa", "bbbbbbbbbbbb")
        a.put("/x/new.txt", null, "cccccccccccc")
        check("put/get: berkas lama", a.get("/x/a.txt")?.prevFp == "aaaaaaaaaaaa" && a.get("/x/a.txt")?.writtenFp == "bbbbbbbbbbbb")
        check("put/get: berkas yang belum ada (prev null)", a.get("/x/new.txt")?.prevFp == null && a.get("/x/new.txt")?.writtenFp == "cccccccccccc")
        val b = UndoLedger(lf)
        check("tahan restart: instance baru membaca berkas", b.get("/x/a.txt")?.writtenFp == "bbbbbbbbbbbb" && b.get("/x/new.txt") != null && b.get("/x/new.txt")?.prevFp == null)
        b.put("/x/a.txt", "dddddddddddd", "eeeeeeeeeeee")
        check("timpa: entri terbaru menang", UndoLedger(lf).get("/x/a.txt")?.writtenFp == "eeeeeeeeeeee")
        b.remove("/x/a.txt")
        check("remove: hilang", UndoLedger(lf).get("/x/a.txt") == null && UndoLedger(lf).get("/x/new.txt") != null)
        b.put("/x/tab\tname", null, "ffffffffffff")
        check("jalur dengan TAB diabaikan", b.get("/x/tab\tname") == null)
        lf.appendText("rusak tanpa tab\n\t-\tzzz\n/x/y\t-\t\n", Charsets.UTF_8)
        check("baris rusak diabaikan, entri sehat tetap", UndoLedger(lf).get("/x/new.txt") != null && UndoLedger(lf).get("/x/y") == null)
        val c = UndoLedger(File(tmp, "cap.tsv"))
        for (i in 0 until UndoLedger.MAX_ENTRIES + 5) c.put("/p/$i", null, "fp$i")
        check("batas entri: yang tertua dibuang", c.get("/p/0") == null && c.get("/p/${UndoLedger.MAX_ENTRIES + 4}") != null && c.get("/p/5") != null)
        val d = UndoLedger(File(tmp, "one.tsv"))
        d.put("/q", null, "x1")
        d.remove("/q")
        check("remove entri terakhir: berkas dihapus", !File(tmp, "one.tsv").exists())

        val st = HistoryStore(File(tmp, "h"))
        val f = File(tmp, "a.txt")
        val v = st.record(f, "halo\n", HistoryKind.EXTERNAL_AFTER, 1000L)
        check("fingerprint sama dengan sidik jari versi Riwayat", v != null && v.fp == HistoryStore.fingerprint("halo\n") && v.fp.length == 12)
    } finally {
        tmp.deleteRecursively()
    }
    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}

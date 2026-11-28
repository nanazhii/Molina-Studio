import android.content.Context
import androidx.core.content.ContextCompat
import com.molina.core.fileops.FileOpOutcome
import com.molina.core.fileops.FileOps
import com.molina.core.library.ProtectedPaths
import com.molina.core.library.TrashBin
import com.molina.core.log.LogBus
import com.molina.explorer.ExplorerActions
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private class Fx(val vol: File) {
    var reloads = 0
    var clears = 0
    val app = File(vol, "Molina").also { it.mkdirs() }
    val guard = ProtectedPaths.of(listOf(vol), app)
    val a = ExplorerActions(Context(), object : CoroutineScope {}, listOf(vol), guard, { clears++ }, { reloads++ })
}

fun main() {
    val d = Files.createTempDirectory("ea").toFile()
    try {
        fun fx(name: String) = Fx(File(d, name).also { it.mkdirs() })
        fun file(f: Fx, n: String) = File(f.vol, n).also { it.writeText("x") }

        // ---- izin
        ContextCompat.result = -1
        var f = fx("izin")
        var called = false
        f.a.runOp(op = { called = true; "x" })
        check("izin ditolak: pesan baku, tidak sibuk, op tidak jalan", f.a.message == FileOpOutcome.NO_WRITE_PERMISSION && !f.a.messageOk && !f.a.busy && !called)
        check("izin ditolak: tidak memuat ulang", f.reloads == 0 && f.clears == 0)
        ContextCompat.result = 0

        // ---- sukses
        f = fx("sukses")
        var done = 0
        var inBusy = false; var inMsg = ""; var inUndo: Any? = "belum"
        f.a.runOp(op = { _ -> inBusy = f.a.busy; inMsg = f.a.message; inUndo = f.a.undo; "selesai" }, onDone = { done++ })
        check("sukses: selama op sibuk, 'Memproses', undo kosong", inBusy && inMsg == "Memproses\u2026" && inUndo == null)
        check("sukses: pesan hasil, tidak sibuk, progres null", f.a.message == "selesai" && f.a.messageOk && !f.a.busy && f.a.progress == null)
        check("sukses: onDone 1x, seleksi dibersihkan 1x, muat ulang 1x", done == 1 && f.clears == 1 && f.reloads == 1)

        // ---- gagal
        f = fx("gagal")
        LogBus.entries.clear()
        var failed: Throwable? = null
        f.a.runOp(op = { _ -> throw IOException("boom") }, onFail = { failed = it })
        check("gagal: pesan galat, bukan ok, onFail dipanggil", f.a.message == "boom" && !f.a.messageOk && failed?.message == "boom")
        check("gagal: dimuat ulang, seleksi dipertahankan, dicatat di log", f.reloads == 1 && f.clears == 0 && LogBus.entries.size == 1 && LogBus.entries[0].startsWith("penjelajah/Error/FileOps: java.io.IOException: boom"))
        f.a.runOp(op = { _ -> throw IllegalStateException() })
        check("gagal tanpa pesan: teks nama kelas", f.a.message == "Operasi gagal (IllegalStateException)")

        // ---- gagal yang tidak dicatat
        LogBus.entries.clear()
        f = fx("tak-dicatat")
        f.a.runOp(op = { _ -> throw FileOps.NeedPassword() })
        f.a.runOp(op = { _ -> throw FileOps.WrongPassword() })
        f.a.runOp(op = { _ -> throw IOException(FileOpOutcome.CANCELLED) })
        check("tak dicatat: NeedPassword, WrongPassword, Dibatalkan", LogBus.entries.isEmpty() && f.a.message == "Dibatalkan" && f.reloads == 3)

        // ---- re-entrancy dan batal
        f = fx("batal")
        var nested = false; var cancelledInside = false
        f.a.runOp(op = { ctl -> f.a.runOp(op = { _ -> nested = true; "dalam" }); f.a.cancel(); cancelledInside = ctl.cancelled(); "luar" })
        check("sibuk: op bersarang diabaikan", !nested && f.a.message == "luar")
        check("batal: cancel() terbaca Ctl", cancelledInside)
        var freshCancelled = true
        f.a.runOp(op = { ctl -> freshCancelled = ctl.cancelled(); "baru" })
        check("batal: flag direset di operasi berikutnya", !freshCancelled)

        // ---- pesan
        f = fx("pesan")
        f.a.say("halo", true)
        check("say: isi dan status", f.a.message == "halo" && f.a.messageOk)
        f.a.dismissMessage()
        check("dismissMessage: kosong", f.a.message.isEmpty())
        file(f, "a.txt")
        f.a.delete(listOf(File(f.vol, "a.txt")), false)
        check("resetFeedback: ada undo sebelum reset", f.a.undo != null)
        f.a.say("sisa"); f.a.resetFeedback()
        check("resetFeedback: pesan kosong dan undo null", f.a.message.isEmpty() && f.a.undo == null)

        // ---- hapus permanen
        f = fx("permanen")
        val p1 = file(f, "p1.txt")
        f.a.delete(listOf(p1), true)
        check("hapus permanen: berkas hilang, pesan, tanpa undo", !p1.exists() && f.a.message == "Dihapus permanen: 1 item" && f.a.undo == null)

        // ---- Recycle Bin + urungkan
        f = fx("bin")
        val b1 = file(f, "b1.txt")
        f.a.delete(listOf(b1), false)
        check("recycle bin: berkas pindah, pesan, undo tersedia", !b1.exists() && f.a.message == "Dipindahkan ke Recycle Bin: 1 item" && f.a.undo?.size == 1)
        check("recycle bin: berkas ada di folder trash volume", File(f.vol, TrashBin.DIR).isDirectory)
        f.a.undoDelete()
        check("urungkan: berkas pulih, pesan, undo null", b1.exists() && f.a.message == "Dipulihkan: 1 item" && f.a.undo == null)
        val before = f.reloads
        f.a.undoDelete()
        check("urungkan tanpa undo: no-op", f.reloads == before)

        // ---- jalur terlindung
        f = fx("lindung")
        f.a.delete(listOf(f.vol), true)
        check("terlindung (permanen): ditolak, folder utuh", f.vol.isDirectory && f.a.message.startsWith("Dilindungi") && !f.a.messageOk)
    } finally {
        d.deleteRecursively()
    }
    if (fails > 0) { println("$fails gagal"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}

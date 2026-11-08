import com.molina.core.common.SevenZipCommand
import com.molina.core.common.SevenZipCommand.Op
import java.io.File
import java.nio.file.Files

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}
private fun throwsIae(f: () -> Unit): Boolean = try { f(); false } catch (e: IllegalArgumentException) { true }

private fun runBin(bin: String, args: List<String>, stdin: ByteArray?): Int {
    val pb = ProcessBuilder(listOf(bin) + args).redirectErrorStream(true)
    pb.environment()["LC_ALL"] = "C.UTF-8"
    val pr = pb.start()
    if (stdin != null) pr.outputStream.use { it.write(stdin) } else pr.outputStream.close()
    pr.inputStream.readBytes()
    return pr.waitFor()
}

fun main() {
    check("opOf l/x/a", SevenZipCommand.opOf(listOf("l", "-slt")) == Op.LIST && SevenZipCommand.opOf(listOf("x")) == Op.EXTRACT && SevenZipCommand.opOf(listOf("a")) == Op.CREATE)
    check("opOf lain/kosong = null", SevenZipCommand.opOf(listOf("t")) == null && SevenZipCommand.opOf(emptyList()) == null)
    check("useDirect: list/create langsung", SevenZipCommand.useDirect(Op.LIST, true, true) && SevenZipCommand.useDirect(Op.CREATE, true, true))
    check("useDirect: ekstrak di PRoot bila Debian ada", !SevenZipCommand.useDirect(Op.EXTRACT, true, true))
    check("useDirect: ekstrak langsung bila Debian tak ada", SevenZipCommand.useDirect(Op.EXTRACT, true, false))
    check("useDirect: tanpa lib atau op tak dikenal = PRoot", !SevenZipCommand.useDirect(Op.LIST, false, true) && !SevenZipCommand.useDirect(null, true, false))

    val binds = listOf("/sd/arsip" to "/mnt/molina-in/0", "/sd/lain" to "/mnt/molina-in/1", "/sd/.stage-1" to SevenZipCommand.GUEST_OUT)
    val m = SevenZipCommand.mapToHost(
        listOf("a", "-t7z", "-p", "-x!n/sub", "--", "/mnt/molina-out/archive.7z", "/mnt/molina-in/0/foto", "/mnt/molina-in/1/a b"), binds)
    check("mapToHost: keluaran + sumber dipetakan", m[5] == "/sd/.stage-1/archive.7z" && m[6] == "/sd/arsip/foto" && m[7] == "/sd/lain/a b")
    check("mapToHost: opsi dan '--' tidak disentuh", m.subList(0, 5) == listOf("a", "-t7z", "-p", "-x!n/sub", "--"))
    check("mapToHost: -o<dir>", SevenZipCommand.mapToHost(listOf("x", "-o/mnt/molina-out", "--", "/mnt/molina-in/0/z.7z"), binds)[1] == "-o/sd/.stage-1")
    check("mapToHost: sama persis dengan titik mount", SevenZipCommand.mapToHost(listOf("a", "/mnt/molina-in/1"), binds)[1] == "/sd/lain")
    check("mapToHost: nama relatif tidak berubah", SevenZipCommand.mapToHost(listOf("x", "--", "/mnt/molina-in/0/z.7z", "dir/berkas"), binds)[3] == "dir/berkas")
    check("mapToHost: guest tanpa bind ditolak", throwsIae { SevenZipCommand.mapToHost(listOf("a", "/mnt/molina-in/9/x"), binds) })
    check("mapToHost: /mnt/molina-src tanpa bind ditolak", throwsIae { SevenZipCommand.mapToHost(listOf("l", "/mnt/molina-src/a.7z"), binds) })
    check("mapToHost: awalan mirip tidak salah cocok", throwsIae { SevenZipCommand.mapToHost(listOf("a", "/mnt/molina-in/00/x"), binds) })

    val bin = System.getenv("SEVENZIP_BIN")
    if (bin.isNullOrEmpty()) println("lewati integrasi (SEVENZIP_BIN kosong)") else {
        val root = Files.createTempDirectory("molina7z").toFile()
        try {
            val parent = File(root, "induk").apply { mkdirs() }
            val sub = File(parent, "folder \u00e9\u65e5").apply { mkdirs() }
            File(sub, "berkas \u00fc.txt").writeText("isi-rahasia")
            val stage = File(root, "stage").apply { mkdirs() }
            val pw = "S3ndi-Panjang!\u00e9"
            val cargs = SevenZipCommand.createArgs(
                SevenZipCommand.GUEST_OUT + "/" + SevenZipCommand.OUT_FILE, listOf("/mnt/molina-in/0/" + sub.name), 5, true, true)
            val rc = runBin(bin, SevenZipCommand.mapToHost(cargs, listOf(parent.path to "/mnt/molina-in/0", stage.path to SevenZipCommand.GUEST_OUT)), SevenZipCommand.stdinFor(pw))
            check("langsung: kompres bersandi + -mhe rc=0 dan arsip ada", rc == 0 && File(stage, SevenZipCommand.OUT_FILE).isFile)
            val out = File(root, "out").apply { mkdirs() }
            val xargs = SevenZipCommand.extractArgs("/mnt/molina-src/" + SevenZipCommand.OUT_FILE, SevenZipCommand.GUEST_OUT)
            val rx = runBin(bin, SevenZipCommand.mapToHost(xargs, listOf(stage.path to SevenZipCommand.GUEST_SRC, out.path to SevenZipCommand.GUEST_OUT)), SevenZipCommand.stdinFor(pw))
            check("langsung: ekstrak bersandi rc=0", rx == 0)
            check("langsung: isi utuh (nama Unicode)", File(out, "folder \u00e9\u65e5/berkas \u00fc.txt").let { it.isFile && it.readText() == "isi-rahasia" })
            val rw = runBin(bin, SevenZipCommand.mapToHost(xargs, listOf(stage.path to SevenZipCommand.GUEST_SRC, File(root, "out2").apply { mkdirs() }.path to SevenZipCommand.GUEST_OUT)), SevenZipCommand.stdinFor("salah-sandi"))
            check("langsung: sandi salah -> rc != 0", rw != 0)
        } finally { root.deleteRecursively() }
    }
    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) System.exit(1)
}

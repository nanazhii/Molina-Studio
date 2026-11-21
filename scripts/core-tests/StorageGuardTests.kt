import com.molina.core.library.*
import java.io.File
import java.nio.file.Files

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    val d = Files.createTempDirectory("sg").toFile()
    try {
        // ---- StorageVolumes
        val internal = File(d, "emulated0").also { it.mkdirs() }
        val storage = File(d, "storage").also { it.mkdirs() }
        File(storage, "emulated").mkdirs(); File(storage, "self").mkdirs()
        File(storage, "ABCD-1234").mkdirs()
        File(storage, "catatan.txt").writeText("x")
        val vols = StorageVolumes.list(internal, storage)
        check("volumes: internal pertama", vols.first() == internal)
        check("volumes: kartu SD ikut, emulated/self/berkas dibuang", vols.map { it.name } == listOf("emulated0", "ABCD-1234"))
        check("volumes: /storage hilang -> hanya internal", StorageVolumes.list(internal, File(d, "tidak-ada")) == listOf(internal))

        // ---- ProtectedPaths
        val app = File(internal, "Molina").also { it.mkdirs() }
        val unduh = File(app, "Downloads").also { it.mkdirs() }
        val g = ProtectedPaths.of(vols, app, listOf(unduh))
        check("protected: akar volume (semua) dilindungi", vols.all { it.absolutePath in g })
        check("protected: Recycle Bin tiap volume dilindungi", vols.all { File(it, TrashBin.DIR).absolutePath in g })
        check("protected: appRoot dan extra dilindungi", app.absolutePath in g && unduh.absolutePath in g)
        check("protected: isi folder terlindung boleh diubah", ProtectedPaths.canModify(File(unduh, "film.mp4"), g))
        check("protected: jalur terlindung tidak boleh diubah", !ProtectedPaths.canModify(unduh, g) && !ProtectedPaths.canModify(internal, g))
        check("protected: tanpa extra = hanya volume, trash, appRoot", ProtectedPaths.of(vols, app).size == vols.size * 2 + 1)

        // ---- Kesetaraan dengan TvGuide.guard (SSOT: TvGuide hanya mendelegasi)
        check("protected: TvGuide.guard identik", TvGuide.guard(vols, app, listOf(unduh)) == g)
        check("protected: TvGuide.canModify identik", TvGuide.canModify(unduh, g) == ProtectedPaths.canModify(unduh, g))
    } finally {
        d.deleteRecursively()
    }
    if (fails > 0) { println("$fails gagal"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}

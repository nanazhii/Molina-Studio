import com.molina.core.common.DirOrder
import com.molina.core.common.FileTypes
import com.molina.core.common.FolderKind
import com.molina.core.common.FolderKinds
import com.molina.core.common.NaturalOrder
import java.io.File
import kotlin.system.exitProcess

private var fails = 0
private fun check(name: String, ok: Boolean) { if (!ok) { fails++; println("GAGAL: $name") } }

fun main() {
    check("ep2 < ep10", NaturalOrder.compare("ep2", "ep10") < 0)
    check("abaikan huruf besar", NaturalOrder.compare("EP2", "ep2") == 0)
    check("nol di depan", NaturalOrder.compare("a01", "a1") == 0)
    check("jenis dasar", "mkv" in FileTypes.VIDEO && "mp3" in FileTypes.AUDIO && "m3u" in FileTypes.LISTS)
    check("MEDIA = gabungan", FileTypes.MEDIA == FileTypes.AUDIO + FileTypes.VIDEO + FileTypes.LISTS)
    check("extOf", FileTypes.extOf("Film.MKV") == "mkv" && FileTypes.extOf("tanpa") == "")
    check("isHidden", FileTypes.isHidden(".x") && !FileTypes.isHidden("x"))
    check("isVideo/isAudio/isMedia", FileTypes.isVideo("a.MP4") && FileTypes.isAudio("b.flac") && FileTypes.isMedia("c.m3u8") && !FileTypes.isMedia("d.txt"))
    check("FolderKinds", FolderKinds.of("Downloads") == FolderKind.DOWNLOAD && FolderKinds.of("MOVIES") == FolderKind.VIDEO &&
        FolderKinds.of("Music") == FolderKind.MUSIC && FolderKinds.of("DCIM") == FolderKind.PICTURES &&
        FolderKinds.of("Documents") == FolderKind.DOCS && FolderKinds.of("src") == FolderKind.DEFAULT && FolderKinds.of("") == FolderKind.DEFAULT)
    val d = java.nio.file.Files.createTempDirectory("ft").toFile()
    try {
        File(d, "b").mkdir(); File(d, "A").mkdir()
        File(d, "c.txt").writeText("halo"); File(d, ".h").writeText("x")
        File(d, "bin.dat").writeBytes(byteArrayOf(1, 0, 2))
        check("folder dulu lalu berkas", DirOrder.list(d).map { it.name } == listOf("A", "b", ".h", "bin.dat", "c.txt"))
        check("tanpa tersembunyi", DirOrder.list(d, showHidden = false).map { it.name } == listOf("A", "b", "bin.dat", "c.txt"))
        check("folder tak terbaca = kosong", DirOrder.list(File(d, "tidak-ada")).isEmpty())
        check("looksText teks", FileTypes.looksText(File(d, "c.txt")))
        check("looksText biner", !FileTypes.looksText(File(d, "bin.dat")))
        check("looksText folder", !FileTypes.looksText(File(d, "A")))
    } finally { d.deleteRecursively() }
    if (fails > 0) exitProcess(1)
    println("FileTypes OK")
}

import com.molina.core.files.FolderBadge
import com.molina.core.files.FolderBadges

// [FOLDER-BADGE] Uji pemetaan nama folder -> lencana (JVM murni).
private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    check("Molina = bintang", FolderBadges.of("Molina") == FolderBadge.STAR)
    check("molina tanpa peka huruf", FolderBadges.of("MOLINA") == FolderBadge.STAR && FolderBadges.of("molina") == FolderBadge.STAR)
    check("Molina-Studio bukan bintang (nama persis)", FolderBadges.of("Molina-Studio") == FolderBadge.NONE)
    check("Download dan Unduhan = panah turun", FolderBadges.of("Download") == FolderBadge.DOWNLOAD && FolderBadges.of("Unduhan") == FolderBadge.DOWNLOAD)
    check("Movies/Video/Film = putar", FolderBadges.of("Movies") == FolderBadge.PLAY && FolderBadges.of("Videos") == FolderBadge.PLAY && FolderBadges.of("Film") == FolderBadge.PLAY)
    check("Music, DCIM, Documents tanpa lencana", FolderBadges.of("Music") == FolderBadge.NONE && FolderBadges.of("DCIM") == FolderBadge.NONE && FolderBadges.of("Documents") == FolderBadge.NONE)
    check("folder biasa dan nama kosong tanpa lencana", FolderBadges.of("Scripts") == FolderBadge.NONE && FolderBadges.of("") == FolderBadge.NONE)
    if (fails > 0) { println("$fails GAGAL"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}

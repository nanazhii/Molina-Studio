package com.molina.core.files

import java.util.Locale

/** [FOLDER-BADGE] Lencana kecil di ikon folder (semua folder tetap satu warna mint; yang penting dibedakan lencana). */
enum class FolderBadge { NONE, STAR, DOWNLOAD, PLAY }

object FolderBadges {
    /** Menurut nama folder persis (tanpa peka huruf): Molina = bintang, Download = panah turun, Movies/Video = putar. Lainnya tanpa lencana. */
    fun of(name: String): FolderBadge {
        if (name.lowercase(Locale.ROOT) == "molina") return FolderBadge.STAR
        return when (FolderKinds.of(name)) {
            FolderKind.DOWNLOAD -> FolderBadge.DOWNLOAD
            FolderKind.VIDEO -> FolderBadge.PLAY
            else -> FolderBadge.NONE
        }
    }
}

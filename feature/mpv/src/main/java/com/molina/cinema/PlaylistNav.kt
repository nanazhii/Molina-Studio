package com.molina.cinema

/**
 * [PLAYLIST-WRAP] Next/prev berputar: next di trek terakhir kembali ke trek pertama, prev di trek pertama
 * lompat ke trek terakhir. Dipakai tombol di pemutar, sesi media, dan tombol notifikasi agar perilakunya sama.
 * Playlist berisi satu item memakai perilaku bawaan mpv.
 */
internal object PlaylistNav {
    /** Indeks tujuan untuk wrap, atau null bila cukup memakai perintah bawaan mpv. */
    fun nextWrapIndex(pos: Int, count: Int): Int? = if (count > 1 && pos == count - 1) 0 else null

    fun prevWrapIndex(pos: Int, count: Int): Int? = if (count > 1 && pos == 0) count - 1 else null

    fun next() {
        val count = MPVLib.getPropertyInt("playlist-count") ?: 0
        val pos = MPVLib.getPropertyInt("playlist-pos") ?: 0
        val target = nextWrapIndex(pos, count)
        if (target != null) {
            MpvLog.i(TAG, "Next di trek terakhir: kembali ke awal playlist")
            MPVLib.command(arrayOf("playlist-play-index", target.toString()))
        } else {
            MPVLib.command(arrayOf("playlist-next"))
        }
    }

    fun prev() {
        val count = MPVLib.getPropertyInt("playlist-count") ?: 0
        val pos = MPVLib.getPropertyInt("playlist-pos") ?: 0
        val target = prevWrapIndex(pos, count)
        if (target != null) {
            MpvLog.i(TAG, "Prev di trek pertama: lompat ke trek terakhir")
            MPVLib.command(arrayOf("playlist-play-index", target.toString()))
        } else {
            MPVLib.command(arrayOf("playlist-prev"))
        }
    }

    private const val TAG = "playlist"
}

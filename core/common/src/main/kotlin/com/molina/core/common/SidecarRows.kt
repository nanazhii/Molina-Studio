package com.molina.core.common

/**
 * Baris tambahan untuk dialog Properti dari `.info.json` sidecar. Durasi dan judul media sudah
 * dibaca W7MediaInfo dari berkasnya sendiri, jadi di sini memakai label berbeda ("Judul unduhan").
 */
object SidecarRows {
    fun of(info: MediaSidecar.Info?): List<Pair<String, String>> {
        if (info == null) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        info.title?.let { out.add("Judul unduhan" to it) }
        info.channel?.let { out.add("Kanal" to it) }
        info.year?.let { out.add("Tahun" to it.toString()) }
        info.description?.let { out.add("Deskripsi" to it) }
        info.webUrl?.let { out.add("Sumber" to it) }
        return out
    }
}

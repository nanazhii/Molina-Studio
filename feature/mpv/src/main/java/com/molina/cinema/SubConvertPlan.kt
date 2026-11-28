package com.molina.cinema

import java.io.File

/*
 * [SUBTITLE-CONVERT] Keputusan murni (tanpa Android) untuk menu "Konversi subtitle": dari trek subtitle aktif
 * menentukan apakah bisa dibaca dari file, perlu diekstrak dari video, atau tidak bisa dikonversi, serta
 * penamaan berkas hasil. Dipanggil dari SubConvertUi; diuji di scripts/mpv-tests.
 */

/** Ringkasan trek subtitle aktif dari properti track-list mpv. */
internal data class SubTrackInfo(
    val id: Int,
    val external: Boolean,
    val externalFilename: String?,
    val codec: String?,
    /** Indeks stream di kontainer (track-list/N/ff-index); dipakai ffmpeg sebagai -map 0:<indeks>. */
    val ffIndex: Int?,
    val lang: String?,
)

internal sealed class ConvertPlan {
    /** Subtitle eksternal berupa berkas lokal: baca langsung. */
    data class FromFile(val path: String) : ConvertPlan()
    /** Tertanam di video, berbentuk teks, dan toggle ekstraksi hidup: perlu ekstraktor. */
    data class NeedsExtraction(val streamIndex: Int) : ConvertPlan()
    /** Tertanam, tetapi toggle ekstraksi mati. */
    object EmbeddedDisabled : ConvertPlan()
    /** Berbasis gambar (PGS/VobSub/DVB): tidak bisa jadi teks tanpa OCR. */
    object Bitmap : ConvertPlan()
    object Unsupported : ConvertPlan()
}

internal object SubConvertPlanner {
    private val BITMAP = setOf("hdmv_pgs_subtitle", "pgssub", "dvd_subtitle", "dvdsub", "dvb_subtitle", "dvbsub", "xsub", "dvb_teletext")
    private val TEXT = setOf(
        "subrip", "srt", "ass", "ssa", "mov_text", "webvtt", "text", "subviewer", "subviewer1",
        "microdvd", "mpl2", "jacosub", "sami", "realtext", "stl", "vplayer",
    )

    fun plan(t: SubTrackInfo, embeddedEnabled: Boolean): ConvertPlan {
        val codec = t.codec?.lowercase()
        if (codec != null && codec in BITMAP) return ConvertPlan.Bitmap
        if (t.external) return localPath(t.externalFilename)?.let { ConvertPlan.FromFile(it) } ?: ConvertPlan.Unsupported
        if (codec != null && codec !in TEXT) return ConvertPlan.Unsupported
        val idx = t.ffIndex ?: return ConvertPlan.Unsupported
        return if (embeddedEnabled) ConvertPlan.NeedsExtraction(idx) else ConvertPlan.EmbeddedDisabled
    }

    /** Path lokal absolut dari nama berkas mpv ("file://..." diterima); null untuk memory://, http, dan relatif. */
    fun localPath(name: String?): String? {
        val n = name?.trim()?.removePrefix("file://") ?: return null
        return if (n.startsWith("/") && n.length > 1) n else null
    }

    private val BAD_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    fun sanitize(name: String): String = name.replace(BAD_CHARS, "_").trim().trim('.').take(120)

    /**
     * "<dir>/<nama-media>[.<bahasa>].srt|ass". Bila sudah ada, ditambah "-2", "-3", ... (tidak menimpa berkas lama,
     * kecuali sudah mencapai -99). [exists] disuntikkan supaya fungsi ini murni.
     */
    fun outputPath(dir: String, mediaPath: String?, lang: String?, format: SubFormat, exists: (String) -> Boolean): String {
        val ext = if (format == SubFormat.ASS) "ass" else "srt"
        val base = sanitize(mediaPath?.let { File(it).nameWithoutExtension }.orEmpty()).ifEmpty { "subtitle" }
        val l = lang.orEmpty().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(8)
        val stem = if (l.isEmpty()) base else "$base.$l"
        var n = 1
        while (true) {
            val path = "${dir.trimEnd('/')}/" + (if (n == 1) "$stem.$ext" else "$stem-$n.$ext")
            if (!exists(path) || n >= 99) return path
            n++
        }
    }
}

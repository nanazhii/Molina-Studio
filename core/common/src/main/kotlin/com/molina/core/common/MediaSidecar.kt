package com.molina.core.common

import java.io.File

/**
 * Poster dan metadata ringkas yang ditulis `molina_dl.py --sidecar` di samping video:
 * `Film [id].mp4` + `Film [id].jpg` + `Film [id].info.json`. Dibaca Molina Cinema untuk kartu rak film.
 * Murni JVM (java.io saja) supaya bisa diuji; semua fungsi tahan berkas hilang/rusak (hasil null, tidak melempar).
 */
object MediaSidecar {
    val POSTER_EXTS = listOf("jpg", "jpeg", "png", "webp")
    const val INFO_SUFFIX = ".info.json"

    /** Batas baca; molina_dl meringkas berkas jadi beberapa KB, yang lebih besar dari ini bukan buatan kita. */
    const val MAX_INFO_BYTES = 2L * 1024 * 1024
    private const val MAX_DESC = 500

    class Found(val poster: File?, val info: File?)

    /** Metadata ringkas untuk kartu film. Semua kolom opsional. */
    data class Info(
        val title: String?,
        val channel: String?,
        val durationSec: Int?,
        val year: Int?,
        val description: String?,
        val webUrl: String?,
    )

    /** Poster (jpg lebih dulu, lalu jpeg/png/webp) dan .info.json yang namanya sama dengan [video]. */
    fun find(video: File): Found {
        val dir = video.parentFile ?: return Found(null, null)
        val base = video.nameWithoutExtension
        val poster = POSTER_EXTS.asSequence().map { File(dir, "$base.$it") }.firstOrNull { it.isFile }
        val info = File(dir, base + INFO_SUFFIX).takeIf { it.isFile }
        return Found(poster, info)
    }

    /** Baca .info.json ringkas; null bila tidak ada, terlalu besar, atau bukan objek JSON yang sah. */
    fun readInfo(file: File?): Info? {
        if (file == null) return null
        return try {
            if (!file.isFile || file.length() > MAX_INFO_BYTES) return null
            val o = MiniJson.parseObject(file.readText(Charsets.UTF_8)) ?: return null
            Info(
                title = o.text("title") ?: o.text("fulltitle"),
                channel = o.text("channel") ?: o.text("uploader"),
                durationSec = o.number("duration")?.toInt()?.takeIf { it > 0 },
                year = year(o),
                description = o.text("description")?.let { clip(it) },
                webUrl = o.text("webpage_url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            )
        } catch (e: java.io.IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    private fun Map<String, Any?>.text(k: String): String? = (this[k] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun Map<String, Any?>.number(k: String): Double? = when (val v = this[k]) {
        is Long -> v.toDouble()
        is Double -> v.takeIf { it.isFinite() }
        else -> null
    }

    private fun year(o: Map<String, Any?>): Int? {
        o.number("release_year")?.toInt()?.let { if (it in 1900..2100) return it }
        val d = o.text("upload_date") ?: return null
        if (d.length < 4 || !d.take(4).all { it.isDigit() }) return null
        return d.take(4).toInt().takeIf { it in 1900..2100 }
    }

    private fun clip(s: String): String {
        val one = s.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= MAX_DESC) one else one.take(MAX_DESC - 1).trimEnd() + "\u2026"
    }
}

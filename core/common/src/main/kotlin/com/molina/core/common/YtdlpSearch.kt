package com.molina.core.common

import java.time.LocalDate
import java.time.ZoneOffset

/** Baris `yt-dlp --dump-json` hasil pencarian -> [StreamSearchItem], terbaru ke terlama. */
object YtdlpSearch {
    private class Hit(val item: StreamSearchItem, val id: String, val at: Long?)

    fun parse(lines: List<String>, limit: Int = 20): List<StreamSearchItem> =
        lines.mapNotNull(::hit).distinctBy { it.id }
            .sortedByDescending { it.at ?: Long.MIN_VALUE }
            .take(limit.coerceAtLeast(0)).map { it.item }

    private fun hit(line: String): Hit? {
        val o = line.trim().takeIf { it.startsWith("{") }?.let(MiniJson::parseObject) ?: return null
        val id = txt(o["id"]) ?: return null
        val title = txt(o["title"]) ?: return null
        if (o["_type"] == "playlist") return null
        // Mode penuh: "url" = tautan media kedaluwarsa, jangan dipakai; hanya mode flat.
        val page = (txt(o["webpage_url"]) ?: if (o["_type"] == "url") txt(o["url"]) else null)
            ?.takeIf { it.startsWith("https://") } ?: return null
        val secs = (o["release_timestamp"] as? Number)?.toLong() ?: (o["timestamp"] as? Number)?.toLong()
        val at = secs?.times(1000) ?: day(txt(o["upload_date"]))
        val item = StreamSearchItem(
            pageUrl = page,
            title = title,
            uploader = txt(o["channel"]) ?: txt(o["uploader"]),
            durationSec = (o["duration"] as? Number)?.toLong()?.takeIf { it > 0 },
            thumbnailUrl = thumb(o["thumbnails"]),
            uploadedAtMs = at,
        )
        return Hit(item, id, at)
    }

    private fun thumb(a: Any?): String? =
        (a as? List<*>)?.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val u = txt(m["url"])
            val w = (m["width"] as? Number)?.toInt() ?: 0
            if (u != null && u.startsWith("https://") && w in 1..480) w to u else null
        }?.maxByOrNull { it.first }?.second

    private fun day(d: String?): Long? =
        if (d == null || d.length != 8) null else try {
            LocalDate.of(d.take(4).toInt(), d.substring(4, 6).toInt(), d.substring(6).toInt())
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } catch (e: RuntimeException) {
            null
        }

    private fun txt(v: Any?): String? = (v as? String)?.trim()?.ifEmpty { null }
}

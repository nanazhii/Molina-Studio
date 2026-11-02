package com.molina.core.common

/**
 * Satu item pada riwayat. [url] adalah URL PENUH hanya untuk item yang belum berhasil (dipakai tombol Ulangi);
 * untuk item berhasil hanya skema + host ([LogRedact.target]) supaya token di query tidak tersimpan di perangkat.
 */
data class DownloadHistoryItem(
    val url: String,
    val title: String?,
    val ok: Boolean,
    val error: String?,
    val files: List<String>,
)

/** Ringkasan satu sesi unduhan yang sudah berhenti (selesai, dibatalkan, atau gagal mulai). */
data class DownloadHistoryEntry(
    val at: Long,
    val choices: DownloadChoices,
    val cancelled: Boolean,
    val fatal: String?,
    val items: List<DownloadHistoryItem>,
) {
    val okCount: Int get() = items.count { it.ok }
    val failedCount: Int get() = items.size - okCount

    /** URL yang bisa diulang: semua item belum berhasil yang masih punya URL. */
    fun retryUrls(): List<String> = items.filter { !it.ok && it.url.isNotEmpty() }.map { it.url }

    fun retryRequest(urls: List<String> = retryUrls()): DownloadRequest = DownloadRequest(
        urls = urls,
        mode = if (choices.video) DownloadMode.VIDEO else DownloadMode.AUDIO,
        height = choices.height,
        videoExt = choices.videoExt,
        audioFormat = choices.audioFmt,
        bitrate = choices.bitrate,
        subs = choices.subs,
        subsMode = choices.subsMode,
        cover = choices.cover,
        limitRate = choices.limitRate.ifEmpty { null },
        sidecar = choices.sidecar,
        perChannel = choices.perChannel,
    )

    /** Mis. "Video 1080p MP4", "Video Terbaik MKV", "Audio FLAC 320k". */
    fun modeLabel(): String {
        val base = if (choices.video) "Video " + (if (choices.height == 0) "Terbaik" else "${choices.height}p") + " " + choices.videoExt.uppercase()
        else "Audio " + choices.audioFmt.uppercase() + " " + choices.bitrate
        val extra = buildList {
            if (choices.video && choices.subs.isNotEmpty()) {
                add("sub " + DownloadChoices.subsLabel(choices.subs) + (if (choices.subsMode == "file") " .srt" else ""))
            }
            if (choices.video && choices.sidecar) add("poster")
            if (choices.perChannel) add("per kanal")
            if (choices.limitRate.isNotEmpty()) add("maks " + choices.limitRate + "/dtk")
        }
        return if (extra.isEmpty()) base else base + " · " + extra.joinToString(" · ")
    }
}

/** Pembuatan, penyaringan, dan serialisasi riwayat. Murni JVM (tanpa Android) supaya bisa diuji. */
object DownloadHistory {
    const val MAX_ENTRIES = 20
    private const val MAX_TEXT = 200
    private const val MAX_FILES = 5
    private const val VERSION = 1L

    /**
     * Susun entri dari [req] dan keadaan akhir [session]. Item diambil dari daftar URL permintaan (bukan hanya
     * item yang sempat berjalan) sehingga item yang tak sempat mulai (dibatalkan, atau galat global) tetap bisa
     * diulang. Null bila permintaan tidak punya URL.
     */
    fun build(now: Long, req: DownloadRequest, session: DownloadSessionState): DownloadHistoryEntry? {
        val urls = DownloadCommand.clean(req.urls).take(DownloadCommand.MAX_URLS)
        if (urls.isEmpty()) return null
        val items = urls.mapIndexed { i, requested ->
            val st = session.items.getOrNull(i)
            val ok = st?.status == DownloadItemStatus.DONE
            val full = (st?.url?.takeIf { it.isNotEmpty() } ?: requested).take(DownloadCommand.MAX_URL_CHARS)
            val err = if (ok) null else (st?.errorMsg ?: session.fatalError)
            DownloadHistoryItem(
                url = if (ok) LogRedact.target(full) else full,
                title = st?.title?.take(MAX_TEXT),
                ok = ok,
                error = err?.let { LogRedact.text(it).take(MAX_TEXT) },
                files = if (ok) st!!.files.map { it.name.take(MAX_TEXT) }.take(MAX_FILES) else emptyList(),
            )
        }
        val choices = DownloadChoices.sanitize(
            req.mode == DownloadMode.VIDEO, req.height, req.videoExt, req.audioFormat, req.bitrate,
            req.subs, req.subsMode, req.cover, req.limitRate.orEmpty(), req.sidecar, req.perChannel,
        )
        return DownloadHistoryEntry(now, choices, session.cancelled, session.fatalError?.let { LogRedact.text(it).take(MAX_TEXT) }, items)
    }

    /** Entri baru di depan; entri dengan waktu sama diganti; dibatasi [MAX_ENTRIES]. */
    fun push(list: List<DownloadHistoryEntry>, entry: DownloadHistoryEntry): List<DownloadHistoryEntry> =
        (listOf(entry) + list.filter { it.at != entry.at }).sortedByDescending { it.at }.take(MAX_ENTRIES)

    // ---- serialisasi (JSON kecil, tanpa dependensi) ----

    fun encode(entries: List<DownloadHistoryEntry>): String {
        val b = StringBuilder()
        b.append("{\"v\":").append(VERSION).append(",\"entries\":[")
        entries.take(MAX_ENTRIES).forEachIndexed { i, e ->
            if (i > 0) b.append(',')
            b.append("{\"at\":").append(e.at)
                .append(",\"video\":").append(e.choices.video)
                .append(",\"h\":").append(e.choices.height)
                .append(",\"vx\":").append(q(e.choices.videoExt))
                .append(",\"af\":").append(q(e.choices.audioFmt))
                .append(",\"br\":").append(q(e.choices.bitrate))
                .append(",\"sb\":").append(q(e.choices.subs.joinToString(",")))
                .append(",\"sm\":").append(q(e.choices.subsMode))
                .append(",\"cv\":").append(e.choices.cover)
                .append(",\"lr\":").append(q(e.choices.limitRate))
                .append(",\"sc\":").append(e.choices.sidecar)
                .append(",\"pc\":").append(e.choices.perChannel)
                .append(",\"c\":").append(e.cancelled)
                .append(",\"fatal\":").append(e.fatal?.let { q(it) } ?: "null")
                .append(",\"items\":[")
            e.items.forEachIndexed { j, it ->
                if (j > 0) b.append(',')
                b.append("{\"u\":").append(q(it.url))
                    .append(",\"t\":").append(it.title?.let { t -> q(t) } ?: "null")
                    .append(",\"ok\":").append(it.ok)
                    .append(",\"e\":").append(it.error?.let { t -> q(t) } ?: "null")
                    .append(",\"f\":[").append(it.files.joinToString(",") { f -> q(f) }).append("]}")
            }
            b.append("]}")
        }
        b.append("]}")
        return b.toString()
    }

    /** Berkas rusak atau versi tak dikenal -> daftar kosong; entri/bidang rusak dilewati, tidak pernah melempar. */
    fun decode(text: String): List<DownloadHistoryEntry> {
        val root = MiniJson.parseObject(text) ?: return emptyList()
        if ((root["v"] as? Number)?.toLong() != VERSION) return emptyList()
        val raw = root["entries"] as? List<*> ?: return emptyList()
        return raw.asSequence().mapNotNull { decodeEntry(it) }.sortedByDescending { it.at }.take(MAX_ENTRIES).toList()
    }

    private fun decodeEntry(o: Any?): DownloadHistoryEntry? {
        val m = o as? Map<*, *> ?: return null
        val at = (m["at"] as? Number)?.toLong() ?: return null
        val choices = DownloadChoices.sanitize(
            m["video"] as? Boolean, (m["h"] as? Number)?.toInt(), m["vx"] as? String, m["af"] as? String, m["br"] as? String,
            (m["sb"] as? String)?.split(',')?.filter { it.isNotBlank() }, m["sm"] as? String, m["cv"] as? Boolean, m["lr"] as? String,
            m["sc"] as? Boolean, m["pc"] as? Boolean,
        )
        val items = (m["items"] as? List<*>).orEmpty().take(DownloadCommand.MAX_URLS).mapNotNull { x ->
            val im = x as? Map<*, *> ?: return@mapNotNull null
            val url = im["u"] as? String ?: return@mapNotNull null
            DownloadHistoryItem(
                url = url.take(DownloadCommand.MAX_URL_CHARS),
                title = (im["t"] as? String)?.take(MAX_TEXT),
                ok = im["ok"] as? Boolean ?: false,
                error = (im["e"] as? String)?.take(MAX_TEXT),
                files = (im["f"] as? List<*>).orEmpty().filterIsInstance<String>().take(MAX_FILES).map { it.take(MAX_TEXT) },
            )
        }
        if (items.isEmpty()) return null
        return DownloadHistoryEntry(at, choices, m["c"] as? Boolean ?: false, (m["fatal"] as? String)?.take(MAX_TEXT), items)
    }

    private fun q(s: String): String {
        val b = StringBuilder(s.length + 2).append('"')
        for (ch in s) when {
            ch == '"' -> b.append("\\\"")
            ch == '\\' -> b.append("\\\\")
            ch < ' ' -> b.append("\\u").append(String.format("%04x", ch.code))
            else -> b.append(ch)
        }
        return b.append('"').toString()
    }
}

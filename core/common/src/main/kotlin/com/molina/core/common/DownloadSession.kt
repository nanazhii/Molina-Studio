package com.molina.core.common

enum class DownloadItemStatus { QUEUED, RUNNING, PROCESSING, DONE, FAILED }

data class DownloadProgress(
    val percent: Double?,
    val doneBytes: Long?,
    val totalBytes: Long?,
    val speedBps: Long?,
    val etaSec: Long?,
)

data class DownloadItemState(
    val index: Int,
    val url: String = "",
    val title: String? = null,
    val thumbnail: String? = null,
    val status: DownloadItemStatus = DownloadItemStatus.QUEUED,
    val streams: Map<DownloadStream, DownloadProgress> = emptyMap(),
    val stage: String? = null,
    val files: List<DownloadedFile> = emptyList(),
    val existed: Boolean = false,
    val errorCode: String? = null,
    val errorMsg: String? = null,
    /** Pesan mentah (teknis) untuk Log saja; JANGAN ditampilkan ke pengguna. */
    val errorRaw: String? = null,
)

/** Keadaan satu sesi unduhan untuk UI. [fatalError] = galat global (mis. yt-dlp tidak terpasang). */
data class DownloadSessionState(
    val items: List<DownloadItemState> = emptyList(),
    val warnings: List<String> = emptyList(),
    val fatalError: String? = null,
    /** Pesan mentah galat global untuk Log saja; JANGAN ditampilkan ke pengguna. */
    val fatalRaw: String? = null,
    val finished: Boolean = false,
    val ok: Int = 0,
    val failed: Int = 0,
    val cancelled: Boolean = false,
    /** Hasil `--doctor` / `--update-ytdlp` terakhir (sesi perawatan, bukan unduhan). */
    val doctor: DownloadEvent.Doctor? = null,
    val update: DownloadEvent.Update? = null,
)

/** Reducer murni: (keadaan, event) -> keadaan baru. Halaman Unduhan cukup melipat aliran event ke sini. */
object DownloadReducer {
    fun reduce(s: DownloadSessionState, e: DownloadEvent): DownloadSessionState = when (e) {
        is DownloadEvent.Hello -> s
        is DownloadEvent.Warn -> s.copy(warnings = (s.warnings + e.msg).takeLast(20))
        is DownloadEvent.Queue -> s.copy(items = List(e.total.coerceIn(0, MAX_ITEMS)) { DownloadItemState(it + 1) })
        is DownloadEvent.Item -> s.update(e.index) {
            it.copy(url = e.url, status = DownloadItemStatus.RUNNING, streams = emptyMap(), stage = null,
                errorCode = null, errorMsg = null, errorRaw = null)
        }
        is DownloadEvent.Info -> s.update(e.index) { it.copy(title = e.title ?: it.title, thumbnail = e.thumbnail ?: it.thumbnail) }
        is DownloadEvent.Progress -> s.update(e.index) {
            it.copy(
                status = DownloadItemStatus.RUNNING,
                streams = it.streams + (e.stream to DownloadProgress(e.percent, e.doneBytes, e.totalBytes, e.speedBps, e.etaSec)),
            )
        }
        is DownloadEvent.Stage -> s.update(e.index) { it.copy(status = DownloadItemStatus.PROCESSING, stage = e.stage) }
        is DownloadEvent.Done -> s.update(e.index) {
            it.copy(status = DownloadItemStatus.DONE, stage = null, files = e.files, existed = e.existed)
        }
        is DownloadEvent.Error -> {
            val shown = DownloadErrorText.friendly(e.code, e.msg)
            val raw = e.msg.take(400)
            if (e.index <= 0) s.copy(fatalError = shown, fatalRaw = raw)
            else s.update(e.index) {
                it.copy(status = DownloadItemStatus.FAILED, errorCode = e.code, errorMsg = shown, errorRaw = raw)
            }
        }
        is DownloadEvent.Summary -> s.copy(finished = true, ok = e.ok, failed = e.failed, cancelled = e.cancelled)
        is DownloadEvent.Doctor -> s.copy(doctor = e)
        is DownloadEvent.Update -> s.copy(update = e)
    }

    /** Persen gabungan item untuk satu bar: video+audio dirata-ratakan; null bila belum diketahui. */
    fun overallPercent(item: DownloadItemState): Double? = when (item.status) {
        DownloadItemStatus.DONE -> 100.0
        DownloadItemStatus.QUEUED -> 0.0
        else -> item.streams.values.mapNotNull { it.percent }.takeIf { it.isNotEmpty() }?.average()
    }

    private const val MAX_ITEMS = 500

    private fun DownloadSessionState.update(index: Int, f: (DownloadItemState) -> DownloadItemState): DownloadSessionState {
        if (index <= 0 || index > MAX_ITEMS) return this
        val list = items.toMutableList()
        while (list.size < index) list.add(DownloadItemState(list.size + 1))
        list[index - 1] = f(list[index - 1])
        return copy(items = list)
    }
}

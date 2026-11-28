package com.molina.studio.stream

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.molina.core.api.AppLog
import com.molina.core.stream.FailureAction
import com.molina.core.api.LogLevel
import com.molina.core.diag.LogRedact
import com.molina.core.stream.PageMedia // [PAGE-MEDIA]
import com.molina.core.stream.ResolvedStream
import com.molina.core.stream.SearchLang
import com.molina.core.stream.StreamError
import com.molina.core.stream.StreamOutcome
import com.molina.core.stream.StreamDiag // [YTDLP-RETRY-IMP]
import com.molina.core.stream.StreamQuality
import com.molina.core.stream.YtdlDowngrade // [YTDLP-CHIP-IMP]
import com.molina.core.stream.YtdlNote
import com.molina.core.stream.StreamResolver
import com.molina.core.stream.StreamSearchItem
import com.molina.core.stream.YtdlPreResolve // [YTDL-PRERESOLVE]
import com.molina.core.stream.YtdlUrls
import com.molina.core.download.YtdlpCommand
import com.molina.core.stream.YtdlpFetch
import com.molina.core.stream.YtdlpParse
import com.molina.core.stream.YtdlpSearch

/**
 * Resolver cadangan: yt-dlp di Debian PRoot untuk situs yang tidak dikenali NewPipe (TikTok,
 * Facebook, dll.). [enabled] dibaca tiap kali sehingga sakelar di Pengaturan berlaku langsung
 * tanpa memasang ulang resolver. [canResolve] cepat dan tanpa jaringan/proses.
 */
internal class YtdlpStreamResolver(
    private val ctx: Context,
    private val enabled: () -> Boolean,
) : StreamResolver {

    override val id: String = "ytdlp"

    override fun canResolve(url: String): Boolean = enabled() && YtdlUrls.needsYtdl(url)

    override suspend fun resolve(pageUrl: String): StreamOutcome<ResolvedStream> {
        val bin = when (val st = YtdlpLocator.status(ctx)) {
            is YtdlpStatus.Ready -> st.guestBinary
            else -> return unsupported(st.hint(), FailureAction.INSTALL_YTDLP)
        }
        val where = LogRedact.target(pageUrl)
        AppLog.log("stream", LogLevel.Info, TAG, "resolve $where")
        if (YtdlpFetch.needsLocalFetch(pageUrl)) return fetchLocal(bin, pageUrl, where)
        // [YTDLP-RETRY-LOOP] Format tidak tersedia -> turun satu tingkat dan catat tiap percobaan.
        val stage = StreamQuality.ytdlStage
        if (stage == 0) StreamDiag.clear()
        val attempts = attemptsFor(stage)
        // [YTDL-PRERESOLVE] Tautan share Facebook: ikuti pengalihan sendiri (lewat Shield) agar yt-dlp tidak lewat extractor generic. Gagal = URL asli.
        val t0 = System.nanoTime()
        val target = withContext(Dispatchers.IO) { YtdlPreResolve.resolve(pageUrl) }
        if (YtdlPreResolve.isShareLink(pageUrl)) {
            note(LogLevel.Info, "pra-resolusi " + (if (target != null) "berhasil -> " + LogRedact.target(target) else "tidak berubah (URL asli)") + " " + (System.nanoTime() - t0) / 1_000_000 + "ms")
        }
        val ytUrl = target ?: pageUrl
        for ((i, a) in attempts.withIndex()) {
            note(LogLevel.Info, "percobaan " + (i + 1) + "/" + attempts.size + " tahap=" + stage + " " + a.label + " (" + where + ")")
            val o = YtdlpFastDir.run(ctx, bin, YtdlpCommand.resolveArgs(ytUrl, a.format), RESOLVE_TIMEOUT_MS)
            if (o.timedOut) return fail(StreamError.NETWORK, "lewat waktu ${RESOLVE_TIMEOUT_MS / 1000}s ($where)")
            if (o.exit != 0) {
                val cls = if (o.exit != 126 && o.exit != 127) YtdlpCommand.classify(o.stderr) else null
                // yt-dlp tidak mengenal situsnya: anggap stream langsung (seperti ytdl_hook mpv), mpv yang membuka.
                if (cls == StreamError.UNSUPPORTED) {
                    // [YTDLP-HTML-GUARD] Halaman HTML bukan stream: tampilkan kartu galat, jangan serahkan ke mpv ("Failed to recognize file format").
                    if (isHtmlPage(pageUrl)) {
                        // [PAGE-MEDIA] Cari manifest .mpd/.m3u8 di isi halaman: terenkripsi -> DRM (galat jujur), bersih -> putar langsung.
                        when (val m = withContext(Dispatchers.IO) { PageMedia.probe(pageUrl) }) {
                            is PageMedia.Result.Drm -> {
                                pmLog(LogLevel.Warn, "DRM $where")
                                return fail(StreamError.DRM, "siaran dilindungi DRM ($where)")
                            }
                            is PageMedia.Result.Clear -> {
                                pmLog(LogLevel.Info, "MANIFEST_OK $where")
                                note(LogLevel.Info, "manifest ditemukan di halaman, diputar langsung ($where)")
                                return StreamOutcome.Success(ResolvedStream(url = m.url, headers = m.headers))
                            }
                            is PageMedia.Result.None -> pmLog(LogLevel.Warn, "NO_MEDIA_FOUND $where")
                        }
                        return fail(StreamError.UNSUPPORTED, "situs belum didukung: halaman web tanpa media yang dikenali ($where)")
                    }
                    note(LogLevel.Info, "yt-dlp tidak mengenal URL; diteruskan ke mpv ($where)")
                    return StreamOutcome.Success(ResolvedStream(url = pageUrl))
                }
                if (cls == StreamError.FORMAT && i < attempts.lastIndex) {
                    note(LogLevel.Warn, "format tidak tersedia (" + a.label + "), turun ke " + attempts[i + 1].label + " [" + YtdlpCommand.briefError(o.stderr) + "]")
                    continue
                }
                return failure(o.exit, o.stderr, where)
            }
            val parsed = YtdlpParse.resolve(o.stdout)
                ?: return fail(StreamError.PARSE, "keluaran tidak terbaca ($where)")
            parsed.audioUrl?.let { StreamQuality.rememberAudio(parsed.url, it) } // [YTDLP-ADAPTIVE-REG]
            // [YTDLP-CHIP-REG] Catat penurunan agar pemutar menampilkan chip "Auto-turun ke NNNp" (null = tanpa penurunan).
            StreamQuality.rememberYtdlNote(parsed.url, when {
                stage >= 2 -> YtdlNote(YtdlDowngrade.AUDIO)
                stage == 1 -> YtdlNote(YtdlDowngrade.RELAXED)
                else -> null
            })
            StreamQuality.resetYtdlStage()
            note(LogLevel.Info, "berhasil " + a.label + (if (parsed.audioUrl != null) " (video+audio terpisah)" else "") + " (" + where + ")")
            return StreamOutcome.Success(parsed)
        }
        return fail(StreamError.FORMAT, "semua percobaan format gagal ($where)")
    }

    override suspend fun search(query: String, limit: Int): StreamOutcome<List<StreamSearchItem>> {
        if (!enabled()) return StreamOutcome.Failure(StreamError.UNSUPPORTED, "yt-dlp dimatikan")
        val bin = when (val st = YtdlpLocator.status(ctx)) {
            is YtdlpStatus.Ready -> st.guestBinary
            else -> return unsupported(st.hint(), FailureAction.INSTALL_YTDLP)
        }
        val o = YtdlpFastDir.run(ctx, bin, YtdlpCommand.searchArgs(query, limit), SEARCH_TIMEOUT_MS)
        if (o.timedOut) return fail(StreamError.NETWORK, "pencarian lewat waktu")
        if (o.exit != 0) return failure(o.exit, o.stderr, "pencarian")
        return StreamOutcome.Success(SearchLang.applyFor(query, YtdlpSearch.parse(o.stdout.lines(), limit), limit))
    }

    /**
     * Mode unduh-ke-cache (TikTok): CDN-nya menolak mpv dengan 403, tetapi yt-dlp sendiri bisa
     * mengambil berkasnya. yt-dlp mengunduh ke cache Debian, mpv memutar berkas lokalnya.
     */
    private suspend fun fetchLocal(bin: String, pageUrl: String, where: String): StreamOutcome<ResolvedStream> {
        // [LIVE-PROBE] Siaran live tidak punya akhir: jangan diunduh ke cache. Cek dulu (tanpa unduh);
        // live -> URL stream langsung ke mpv, offline -> galat OFFLINE segera, selain itu -> unduh ke cache seperti biasa.
        val probe = YtdlpFastDir.run(ctx, bin, YtdlpCommand.resolveArgs(pageUrl, YtdlpParse.FORMAT), LIVE_PROBE_TIMEOUT_MS)
        if (!probe.timedOut) {
            if (probe.exit == 0 && LIVE_RE.containsMatchIn(probe.stdout)) {
                val live = YtdlpParse.resolve(probe.stdout)
                if (live != null) {
                    note(LogLevel.Info, "siaran live: diputar langsung tanpa unduh ($where)")
                    return StreamOutcome.Success(live)
                }
            } else if (probe.exit != 0 && YtdlpCommand.classify(probe.stderr) == StreamError.OFFLINE) {
                return failure(probe.exit, probe.stderr, where)
            }
        }
        StreamCache.prune(ctx)
        val o = YtdlpFastDir.run(ctx, bin, YtdlpFetch.fetchArgs(pageUrl), FETCH_TIMEOUT_MS)
        if (o.timedOut) return fail(StreamError.NETWORK, "unduhan lewat waktu ${FETCH_TIMEOUT_MS / 1000}s ($where)")
        if (o.exit != 0) return failure(o.exit, o.stderr, where)
        val d = YtdlpFetch.parseDownloaded(o.stdout)
            ?: return fail(StreamError.PARSE, "keluaran unduhan tidak terbaca ($where)")
        val file = StreamCache.resolveHost(ctx, d.guestPath)
            ?: return fail(StreamError.PARSE, "berkas unduhan tidak ditemukan ($where)")
        if (file.length() < MIN_LOCAL_BYTES) return fail(StreamError.PARSE, "berkas unduhan terlalu kecil ($where)")
        file.setLastModified(System.currentTimeMillis())
        AppLog.log("stream", LogLevel.Info, TAG, "unduh ke cache selesai ${file.length() / 1024} KB ($where)")
        return StreamOutcome.Success(
            ResolvedStream(url = file.absolutePath, title = d.title, durationSec = d.durationSec)
        )
    }

    /**
     * [YTDLP-HTML-GUARD] Pemeriksaan Content-Type singkat (GET + Range, tanpa membaca isi). Hanya true bila server
     * jelas menjawab HTML; galat/timeout = false agar perilaku lama (serahkan ke mpv) untuk stream tanpa ekstensi tetap jalan.
     */
    private suspend fun isHtmlPage(url: String): Boolean = withContext(Dispatchers.IO) {
        // [SHIELD-PROBE] kebijakan Shield (UrlPolicy, DNS, pelacak) + pengalihan diperiksa ulang
        YtdlUrls.isHtmlContentType(com.molina.core.net.ShieldProbe.contentType(url, PROBE_TIMEOUT_MS))
    }

    private fun unsupported(hint: String?, action: FailureAction? = null): StreamOutcome.Failure {
        AppLog.log("stream", LogLevel.Warn, TAG, hint ?: "yt-dlp tidak siap")
        return StreamOutcome.Failure(StreamError.UNSUPPORTED, hint, action)
    }

    private fun failure(exit: Int, stderr: String, where: String): StreamOutcome.Failure {
        // 126/127: biner/python tidak bisa dijalankan di guest (bukan galat situs).
        if (exit == 126 || exit == 127) {
            return unsupported("yt-dlp tidak bisa dijalankan (exit $exit). ${YtdlpCommand.HINT_INSTALL}", FailureAction.INSTALL_YTDLP)
        }
        val err = YtdlpCommand.classify(stderr)
        return fail(err, "exit=$exit ${YtdlpCommand.briefError(stderr)} ($where)")
    }

    private fun fail(error: StreamError, detail: String): StreamOutcome.Failure {
        note(LogLevel.Warn, "$error: $detail") // [YTDLP-RETRY-FAIL]
        return StreamOutcome.Failure(error, detail)
    }

    /** [PAGE-MEDIA-LOG] Kode kegagalan per situs ke Log Screen: grup stream, tag page-media (DRM, NO_MEDIA_FOUND, MANIFEST_OK). */
    private fun pmLog(level: LogLevel, msg: String) = AppLog.log("stream", level, TAG_PM, msg)

    /** [YTDLP-RETRY] Satu percobaan format yt-dlp. */
    private class Attempt(val format: String, val label: String)

    /** [MOBILE-CAP] Tinggi maks percobaan tahap 0: Otomatis di luar Wi-Fi/Ethernet dibatasi 720p (sama dengan jalur NewPipe); selain itu 1080p. */
    private fun ytdlCap(): Int {
        val top = StreamQuality.YTDL_LADDER.first()
        val auto = StreamQuality.current == com.molina.core.stream.VideoQuality.AUTO
        return if (auto && !com.molina.core.net.NetworkHint.snapshot().wifi) minOf(top, com.molina.core.stream.VideoQuality.P720.maxHeight) else top
    }

    /** [YTDLP-RETRY] Tahap 0: satu percobaan maks 1080p (H.264 diutamakan); 1: longgar; 2: audio saja. */
    private fun attemptsFor(stage: Int): List<Attempt> = when (stage) {
        1 -> listOf(Attempt(YtdlpParse.FORMAT_RELAXED, "semua-kualitas"))
        2 -> listOf(Attempt(YtdlpParse.FORMAT_AUDIO, "audio-saja"))
        // [YTDL-ONE-SHOT] Satu proses. Selector memakai /best di ujung dan batas tinggi bersifat monoton, jadi tiap anak tangga
        // 720/480 hanya cocok dengan format yang sudah cocok di 1080: galat FORMAT di 1080 pasti berulang (2 proses sia-sia).
        else -> { // [MOBILE-CAP]
            val cap = ytdlCap()
            listOf(Attempt(if (cap == StreamQuality.YTDL_LADDER.first()) YtdlpParse.FORMAT else YtdlpParse.formatFor(cap), "<=" + cap + "p"))
        }
    }

    /** [YTDLP-RETRY] Ke Log Screen (grup stream/ytdlp) sekaligus ke catatan "Salin log" di kartu galat. */
    private fun note(level: LogLevel, msg: String) {
        AppLog.log("stream", level, TAG, msg)
        StreamDiag.add(level.code.toString() + " " + msg)
    }

    private companion object {
        const val TAG = "ytdlp"
        const val TAG_PM = PageMedia.LABEL // [PAGE-MEDIA-LOG]
        const val RESOLVE_TIMEOUT_MS = 75_000L
        const val PROBE_TIMEOUT_MS = 6_000 // [YTDLP-HTML-GUARD]
        const val FETCH_TIMEOUT_MS = 120_000L
        const val LIVE_PROBE_TIMEOUT_MS = 45_000L // [LIVE-PROBE]
        val LIVE_RE = Regex("\"is_live\"\\s*:\\s*true") // [LIVE-PROBE]
        const val MIN_LOCAL_BYTES = 10_000L
        const val SEARCH_TIMEOUT_MS = 60_000L
    }
}

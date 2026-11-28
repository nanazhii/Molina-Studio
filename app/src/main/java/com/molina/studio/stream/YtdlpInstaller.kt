package com.molina.studio.stream

import android.content.Context
import com.molina.core.api.AppLog
import com.molina.core.api.LogLevel
import com.molina.core.download.YtdlpCommand
import com.molina.core.download.YtdlpInstall
import com.molina.core.net.ShieldHttp
import com.molina.core.net.ShieldProbe
import java.io.File
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface YtdlpInstallState {
    data object Idle : YtdlpInstallState
    data class Working(val message: String) : YtdlpInstallState
    data class Done(val version: String?) : YtdlpInstallState
    data class Failed(val message: String) : YtdlpInstallState
}

/**
 * Memasang/memperbarui yt-dlp langsung ke rootfs (tanpa Terminal): unduh zipapp resmi lewat HTTPS,
 * verifikasi SHA-256 terhadap SHA2-256SUMS rilis yang sama, ganti berkas secara atomik, lalu
 * `--version` sebagai uji jalan. Butuh rootfs dan python3 sudah ada.
 */
object YtdlpInstaller {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<YtdlpInstallState>(YtdlpInstallState.Idle)
    val state: StateFlow<YtdlpInstallState> = _state.asStateFlow()
    @Volatile private var running = false

    /** [T20] Disuntik MolinaApp: dipanggil dengan versi yt-dlp yang baru terpasang. :stream tidak impor :automation. */
    @Volatile var versionSink: ((Context, String) -> Unit)? = null

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        if (running) return
        running = true
        scope.launch {
            try {
                val res = doInstall(app)
                _state.value = res
                if (res is YtdlpInstallState.Done) res.version?.let { versionSink?.invoke(app, it) }
            } catch (e: Exception) {
                AppLog.log("stream", LogLevel.Warn, "ytdlp-install", "gagal: ${e.javaClass.simpleName}")
                _state.value = YtdlpInstallState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                running = false
            }
        }
    }

    /**
     * [STARTER-YTDLP] Untuk profil Starter: pasang hanya bila yt-dlp belum ada (tanpa unduh ulang). [onDone] dipanggil
     * dari thread latar dengan hasil akhir; bila pemasangan lain sedang berjalan, langsung dilaporkan Failed.
     */
    fun installIfMissing(ctx: Context, onDone: (YtdlpInstallState) -> Unit) {
        val app = ctx.applicationContext
        if (running) { onDone(YtdlpInstallState.Failed("pemasangan yt-dlp lain sedang berjalan")); return }
        running = true
        scope.launch {
            try {
                val ready = YtdlpLocator.status(app) is YtdlpStatus.Ready
                val res = if (ready) YtdlpInstallState.Done(null) else doInstall(app)
                if (!ready) {
                    _state.value = res
                    if (res is YtdlpInstallState.Done) res.version?.let { versionSink?.invoke(app, it) }
                }
                onDone(res)
            } catch (e: Exception) {
                AppLog.log("stream", LogLevel.Warn, "ytdlp-install", "gagal: ${e.javaClass.simpleName}")
                val f = YtdlpInstallState.Failed(e.message ?: e.javaClass.simpleName)
                _state.value = f
                onDone(f)
            } finally {
                running = false
            }
        }
    }

    private suspend fun doInstall(ctx: Context): YtdlpInstallState {
        // Prasyarat selain berkas yt-dlp sendiri.
        when (val st = YtdlpLocator.status(ctx)) {
            YtdlpStatus.NoRootfs, YtdlpStatus.NoPython -> return YtdlpInstallState.Failed(st.hint() ?: "prasyarat belum siap")
            else -> Unit
        }
        val target = YtdlpLocator.hostFile(ctx, "/" + YtdlpInstall.TARGET_GUEST_PATH)
        val dir = target.parentFile ?: return YtdlpInstallState.Failed("folder tujuan tidak valid")
        dir.mkdirs()
        val part = File(dir, "yt-dlp.part")

        _state.value = YtdlpInstallState.Working("Mengambil daftar hash…")
        val sums = String(fetch(YtdlpInstall.SUMS_URL, 64 * 1024, null), Charsets.UTF_8)
        val expected = YtdlpInstall.expectedSha(sums)
            ?: return YtdlpInstallState.Failed("hash resmi yt-dlp tidak ditemukan")

        _state.value = YtdlpInstallState.Working("Mengunduh yt-dlp…")
        val bytes = fetch(YtdlpInstall.BINARY_URL, YtdlpInstall.MAX_BYTES.toInt(), part)
        if (bytes.size < YtdlpInstall.MIN_BYTES) { part.delete(); return YtdlpInstallState.Failed("unduhan terlalu kecil") }

        _state.value = YtdlpInstallState.Working("Memverifikasi…")
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            part.delete()
            return YtdlpInstallState.Failed("hash tidak cocok; berkas dibuang")
        }
        if (!YtdlpInstall.looksLikeZipapp(bytes)) {
            part.delete()
            return YtdlpInstallState.Failed("format berkas bukan zipapp yt-dlp; berkas dibuang")
        }
        if (!part.renameTo(target)) {
            part.delete()
            return YtdlpInstallState.Failed("gagal mengganti berkas tujuan")
        }
        target.setExecutable(true, false)
        target.setReadable(true, false)

        _state.value = YtdlpInstallState.Working("Menguji yt-dlp…")
        val o = YtdlpRunner.run(ctx, "/" + YtdlpInstall.TARGET_GUEST_PATH, listOf("--version"), 60_000L)
        val version = o.stdout.trim().lineSequence().firstOrNull()?.takeIf { o.exit == 0 && it.isNotEmpty() }
        AppLog.log("stream", LogLevel.Info, "ytdlp-install", "terpasang versi=${version ?: "?"} exit=${o.exit}")
        if (version != null) YtdlpFastDir.ensureAsync(ctx) // [YTDL-FAST] ekstrak di latar; zipapp baru membuat penanda lama basi
        return if (version != null) YtdlpInstallState.Done(version)
        else YtdlpInstallState.Failed(
            if (o.exit == 126 || o.exit == 127) "terpasang tetapi tidak bisa dijalankan; ${YtdlpCommand.INSTALL_PYTHON_HINT}"
            else "terpasang tetapi uji --version gagal (exit ${o.exit})"
        )
    }

    /**
     * Unduh [url] (HTTPS saja) ke memori; bila [spool] diberikan, isinya juga ditulis ke berkas itu.
     * Pengalihan diikuti manual (maks 5): tiap lompatan wajib HTTPS dan ke host tepercaya ([YtdlpInstall.isTrustedHost]).
     */
    private fun fetch(url: String, maxBytes: Int, spool: File?): ByteArray {
        // [SHIELD-HTTP] lewat ShieldHttp: fail-closed, tiap lompatan wajib HTTPS + host tepercaya + kebijakan Shield, batas byte
        require(url.startsWith("https://")) { "hanya HTTPS" }
        var problem: String? = null
        val r = ShieldHttp.execute(
            ShieldHttp.Request(url, 30_000, mapOf("Accept-Encoding" to "identity"), 5, maxBytes + 1, "ytdlp-install")
        ) { u ->
            problem = when {
                !u.startsWith("https://") -> "hanya HTTPS"
                !YtdlpInstall.isTrustedHost(runCatching { URL(u).host }.getOrNull()) -> "host unduhan tidak tepercaya"
                !ShieldProbe.isAllowed(u) -> "tautan ditolak kebijakan Shield"
                else -> null
            }
            problem == null
        }
        val bytes = when (r) {
            is ShieldHttp.Result.Blocked -> error(problem ?: r.reason)
            is ShieldHttp.Result.Failed -> error(r.reason)
            is ShieldHttp.Result.Ok -> {
                if (r.code != 200) error("HTTP ${r.code}")
                r.body
            }
        }
        if (bytes.size > maxBytes) error("unduhan melebihi batas")
        spool?.writeBytes(bytes)
        return bytes
    }
}

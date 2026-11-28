package com.molina.code.lsp

import com.molina.code.check.Diagnostic
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** [CODE-LSP3] Server yang dikenal. Sekarang hanya pylsp (Python); shell tetap sekali jalan (audit rootfs). */
class ServerSpec(
    val key: String,
    val label: String,
    val languageId: String,
    val argv: List<String>,
    val missingHint: String,
)

object LspServers {
    val PYTHON = ServerSpec(
        key = "python",
        label = "Python",
        languageId = "python",
        argv = listOf("pylsp"),
        missingHint = "pylsp belum ada di Debian. Di Terminal jalankan: apt install -y python3-pylsp",
    )

    /** Server untuk berkas bernama [fileName] (baris pertama [firstLine] untuk shebang), atau null. */
    fun of(fileName: String, firstLine: String?): ServerSpec? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext == "py" || ext == "pyw") return PYTHON
        if (ext.isEmpty() || ext == fileName.lowercase()) {
            val l = firstLine?.trimEnd('\r') ?: return null
            if (l.startsWith("#!") && l.contains("python")) return PYTHON
        }
        return null
    }
}

/** Pembaca /proc/meminfo (murni agar bisa diuji). */
object MemInfo {
    /** MemAvailable dalam kB; -1 bila tidak ada atau tidak terbaca. */
    fun availableKb(meminfo: String): Long {
        for (line in meminfo.lineSequence()) {
            if (!line.startsWith("MemAvailable:")) continue
            return line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: -1L
        }
        return -1L
    }
}

enum class LiveState { OFF, IDLE, STARTING, READY, SLEEPING, ERROR }

/** Penjadwal dan sensor yang dipasang pemanggil (Android: Handler + /proc/meminfo; uji: palsu). */
interface LspLiveEnv {
    /** Jalankan [task] di main thread setelah [delayMs]; kembalikan pembatalnya. */
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit

    /** Jalankan [task] di main thread secepatnya (dipanggil dari thread latar). */
    fun post(task: () -> Unit)

    fun now(): Long

    /** RAM tersedia (kB); negatif = tidak diketahui (penjaga RAM dilewati). */
    fun memAvailableKb(): Long
}

/** Sidik isi teks yang dikirim ke server; diagnostik hanya dipasang bila isi editor masih sama. */
class TextStamp(val length: Int, val hash: Int) {
    fun matches(text: String): Boolean = text.length == length && text.hashCode() == hash
}

interface LspLiveEvents {
    fun onState(state: LiveState, detail: String?)
    fun onDiagnostics(tabId: Int, hostPath: String, items: List<Diagnostic>, stamp: TextStamp?)
    fun onNotice(message: String)
}

class LspLiveConfig(
    val debounceMs: Long = 700L,
    val idleMs: Long = 5 * 60_000L,
    val killGraceMs: Long = 3_000L,
    val ramPollMs: Long = 20_000L,
    val startMinKb: Long = 600_000L,
    val stopBelowKb: Long = 250_000L,
    val ramCooldownMs: Long = 60_000L,
    val maxDocChars: Int = 300_000,
    val maxFailures: Int = 3,
    val retryCooldownMs: Long = 10_000L,
    /** Sumber diagnostik yang disembunyikan (gaya pycodestyle terlalu berisik untuk garis bawah). */
    val hideSources: Set<String> = setOf("pycodestyle"),
)

/**
 * [CODE-LSP3] Diagnostik langsung: menjaga SATU sesi LSP menetap, mengirim perubahan dengan debounce, mematikan
 * sesi saat diam ([LspLiveConfig.idleMs]) atau saat RAM tipis, dan membangunkannya lagi pada edit berikutnya.
 *
 * Semua API dipanggil dari main thread; callback sesi (thread latar) dipindah lewat [LspLiveEnv.post].
 * Dokumen dikunci per tab ([touch]/[release]) supaya Simpan sebagai (path berubah) tidak meninggalkan dokumen yatim.
 * Kegagalan beruntun menyerah setelah [LspLiveConfig.maxFailures] kali (sampai saklar dinyalakan ulang).
 */
class LspLive(
    private val env: LspLiveEnv,
    private val events: LspLiveEvents,
    private val executorProvider: () -> StreamExecutor?,
    private val pathsProvider: () -> LspPathMap,
    private val config: LspLiveConfig = LspLiveConfig(),
) {
    private class Doc(var hostPath: String, var spec: ServerSpec, var text: () -> String?) {
        var opened = false
        var stamp: TextStamp? = null
    }

    private enum class StopWhy { IDLE, RAM, DISABLED, SHUTDOWN }

    private val docs = LinkedHashMap<Int, Doc>()
    private var session: LspSession? = null
    private var sessionId = 0
    private var sessionSpec: ServerSpec? = null
    private var stopWhy: StopWhy? = null
    private var enabled = false
    private var failures = 0
    private var gaveUp = false
    private var blockedUntil = 0L
    private var lastRamNotice: Long? = null
    private var noExecutorNoted = false

    private var cancelDebounce: (() -> Unit)? = null
    private var cancelIdle: (() -> Unit)? = null
    private var cancelRam: (() -> Unit)? = null
    private var cancelKill: (() -> Unit)? = null

    var state: LiveState = LiveState.OFF
        private set

    /** [CODE-LSP5] Cermin sesi yang READY untuk dibaca thread latar (completion). Hanya ditulis dari thread utama. */
    @Volatile private var readySession: LspSession? = null

    /**
     * [CODE-LSP5] Completion untuk thread latar (CompletionThread Sora): memblokir sampai ada balasan atau [timeoutMs].
     * [text] dikirim lebih dulu sebagai isi dokumen terbaru (satu pipa, berurutan). Null = tidak tersedia, gagal, atau
     * lewat waktu; pemanggil jatuh ke completion bawaan editor. JANGAN dipanggil dari thread utama.
     */
    fun completeBlocking(hostPath: String, languageId: String, text: String, line: Int, column: Int, timeoutMs: Long): List<LspCompletionItem>? {
        val s = readySession ?: return null
        if (text.length > config.maxDocChars) return null
        if (!s.openDocument(hostPath, languageId, text)) return null // dokumen sudah terbuka = ganti teks
        val latch = CountDownLatch(1)
        val box = AtomicReference<List<LspCompletionItem>?>(null)
        if (!s.completion(hostPath, line, column) { list, _ -> box.set(list); latch.countDown() }) return null
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        return box.get()
    }

    /**
     * [CODE-LSP6] Definition untuk main thread: tidak memblokir. [cb] dipanggil TEPAT SEKALI di main thread (lewat
     * [LspLiveEnv.post]) dengan target atau alasan gagal yang siap ditampilkan. [text] dikirim lebih dulu sebagai isi
     * dokumen terbaru. Bila sesi belum READY, sesi yang tidur dibangunkan dan pemanggil diminta mengulang sebentar lagi.
     */
    fun requestDefinition(
        hostPath: String, languageId: String, text: String, line: Int, column: Int, timeoutMs: Long,
        cb: (LspDefinitionReply) -> Unit,
    ) {
        if (!enabled) return cb(LspDefinitionReply.Error("Python langsung belum dinyalakan"))
        val s = readySession
        if (s == null) {
            if (state == LiveState.SLEEPING || state == LiveState.IDLE) {
                cancelDebounce?.invoke(); cancelDebounce = null
                flush() // membangunkan sesi bila ada dokumen terdaftar dan RAM cukup
            }
            return cb(
                LspDefinitionReply.Error(
                    when (state) {
                        LiveState.STARTING -> "Server Python sedang dimulai, coba lagi sebentar"
                        LiveState.ERROR -> "Server Python galat, lihat lencana status"
                        LiveState.IDLE -> "Server Python belum bisa dimulai (RAM rendah?)"
                        else -> "Server Python belum siap"
                    },
                ),
            )
        }
        if (text.length > config.maxDocChars) return cb(LspDefinitionReply.Error("Berkas terlalu besar untuk server"))
        if (!s.openDocument(hostPath, languageId, text)) return cb(LspDefinitionReply.Error("Berkas ini tidak terlihat dari Debian"))
        var done = false
        val cancelTimer = env.schedule(timeoutMs) {
            if (done) return@schedule
            done = true
            cb(LspDefinitionReply.Error("Server tidak menjawab dalam ${timeoutMs / 1000} dtk"))
        }
        val sent = s.definition(hostPath, line, column) { list, err ->
            env.post {
                if (done) return@post
                done = true
                cancelTimer()
                cb(if (list == null) LspDefinitionReply.Error(err ?: "Permintaan gagal") else LspDefinitionReply.Targets(list))
            }
        }
        if (!sent && !done) {
            done = true
            cancelTimer()
            cb(LspDefinitionReply.Error("Server belum siap menerima permintaan"))
            return
        }
        bumpIdle()
    }

    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        if (on) {
            failures = 0; gaveUp = false; blockedUntil = 0L; noExecutorNoted = false
            setState(LiveState.IDLE, null)
            if (docs.isNotEmpty()) scheduleFlush()
        } else {
            cancelDebounce?.invoke(); cancelDebounce = null
            stopSession(StopWhy.DISABLED)
            for (d in docs.values) { d.opened = false; d.stamp = null }
            if (session == null) setState(LiveState.OFF, null)
        }
    }

    /** Tab [tabId] berisi berkas [hostPath]; [text] dibaca saat debounce selesai (null = terlalu besar/belum siap). */
    fun touch(tabId: Int, hostPath: String, spec: ServerSpec, text: () -> String?) {
        if (!enabled) return
        val d = docs[tabId]
        if (d == null) {
            docs[tabId] = Doc(hostPath, spec, text)
        } else {
            if (d.hostPath != hostPath) { // Simpan sebagai: tutup path lama di server, buka path baru
                if (d.opened) session?.closeDocument(d.hostPath)
                d.hostPath = hostPath; d.opened = false; d.stamp = null
            }
            d.spec = spec; d.text = text
        }
        scheduleFlush()
    }

    /** Tab ditutup atau tidak lagi berkas yang didukung. */
    fun release(tabId: Int) {
        val d = docs.remove(tabId) ?: return
        if (d.opened) session?.closeDocument(d.hostPath)
    }

    /** Aplikasi/sesi editor dilepas: matikan segalanya. */
    fun shutdown() {
        enabled = false
        cancelDebounce?.invoke(); cancelDebounce = null
        docs.clear()
        stopSession(StopWhy.SHUTDOWN)
        session?.kill()
    }

    // ------------------------------------------------------------------ internal

    private fun scheduleFlush() {
        cancelDebounce?.invoke()
        cancelDebounce = env.schedule(config.debounceMs) { cancelDebounce = null; flush() }
    }

    private fun flush() {
        if (!enabled || docs.isEmpty()) return
        val s = ensureSession() ?: return
        for ((_, d) in docs) {
            if (d.spec.key != sessionSpec?.key) continue
            val text = d.text()
            if (text == null || text.length > config.maxDocChars) {
                if (d.opened) { s.closeDocument(d.hostPath); d.opened = false; d.stamp = null }
                continue
            }
            if (!d.opened) {
                d.opened = s.openDocument(d.hostPath, d.spec.languageId, text)
            } else {
                s.changeDocument(d.hostPath, text)
            }
            d.stamp = TextStamp(text.length, text.hashCode())
        }
        bumpIdle()
    }

    private fun ensureSession(): LspSession? {
        session?.let { return it }
        if (gaveUp) return null
        val now = env.now()
        if (now < blockedUntil) return null
        val avail = env.memAvailableKb()
        if (avail in 0 until config.startMinKb) {
            blockedUntil = now + config.ramCooldownMs
            if (lastRamNotice.let { it == null || now - it >= config.ramCooldownMs }) {
                lastRamNotice = now
                events.onNotice("Diagnostik langsung ditunda: RAM tersedia ${avail / 1024} MB")
            }
            setState(LiveState.IDLE, "RAM rendah")
            return null
        }
        val executor = executorProvider()
        if (executor == null) {
            if (!noExecutorNoted) { noExecutorNoted = true; events.onNotice("Diagnostik langsung belum tersedia di perangkat ini") }
            return null
        }
        val first = docs.values.firstOrNull() ?: return null
        val spec = first.spec
        val id = ++sessionId
        stopWhy = null
        val rootDir = first.hostPath.substringBeforeLast('/', "").ifEmpty { null }
        val s = LspSession(
            executor, pathsProvider(),
            StreamRequest(spec.argv.first(), spec.argv, rootDir, missingHint = spec.missingHint),
            SessionEvents(id),
        )
        session = s
        sessionSpec = spec
        for (d in docs.values) { d.opened = false; d.stamp = null }
        setState(LiveState.STARTING, null)
        s.start(rootDir) // kegagalan start sinkron masuk lewat onClosed (diposkan), session tetap terpasang sampai itu
        return if (session === s) s else null
    }

    private fun bumpIdle() {
        cancelIdle?.invoke()
        cancelIdle = env.schedule(config.idleMs) { cancelIdle = null; stopSession(StopWhy.IDLE) }
    }

    private fun scheduleRamPoll() {
        cancelRam?.invoke()
        cancelRam = env.schedule(config.ramPollMs) {
            cancelRam = null
            if (session == null) return@schedule
            val avail = env.memAvailableKb()
            if (avail in 0 until config.stopBelowKb) {
                blockedUntil = env.now() + config.ramCooldownMs
                events.onNotice("Diagnostik langsung dihentikan: RAM tersedia tinggal ${avail / 1024} MB")
                stopSession(StopWhy.RAM)
            } else {
                scheduleRamPoll()
            }
        }
    }

    private fun stopSession(why: StopWhy) {
        val s = session ?: return
        readySession = null // [CODE-LSP5]
        stopWhy = why
        cancelIdle?.invoke(); cancelIdle = null
        cancelRam?.invoke(); cancelRam = null
        val id = sessionId
        s.stop()
        cancelKill?.invoke()
        cancelKill = env.schedule(config.killGraceMs) {
            cancelKill = null
            if (session === s && sessionId == id) s.kill() // server tidak menjawab shutdown
        }
    }

    private fun sessionEnded(reason: String) {
        val why = stopWhy
        stopWhy = null
        session = null
        readySession = null // [CODE-LSP5]
        sessionSpec = null
        cancelIdle?.invoke(); cancelIdle = null
        cancelRam?.invoke(); cancelRam = null
        cancelKill?.invoke(); cancelKill = null
        for (d in docs.values) { d.opened = false; d.stamp = null }
        when (why) {
            StopWhy.IDLE -> setState(if (enabled) LiveState.SLEEPING else LiveState.OFF, null)
            StopWhy.RAM -> setState(LiveState.IDLE, "RAM rendah")
            StopWhy.DISABLED, StopWhy.SHUTDOWN -> setState(LiveState.OFF, null)
            null -> {
                failures++
                if (failures >= config.maxFailures) {
                    gaveUp = true
                    events.onNotice("Diagnostik langsung dimatikan setelah $failures kegagalan: $reason")
                } else {
                    if (failures == 1) events.onNotice("Diagnostik langsung berhenti: $reason")
                    blockedUntil = env.now() + config.retryCooldownMs
                }
                setState(LiveState.ERROR, reason)
            }
        }
    }

    private fun setState(s: LiveState, detail: String?) {
        state = s
        events.onState(s, detail)
    }

    private inner class SessionEvents(private val id: Int) : LspSessionListener {
        override fun onReady() = env.post {
            if (id != sessionId || session == null) return@post
            failures = 0
            readySession = session // [CODE-LSP5]
            setState(LiveState.READY, null)
            scheduleRamPoll()
            bumpIdle()
        }

        override fun onDiagnostics(hostPath: String, version: Int?, items: List<Diagnostic>) = env.post {
            if (id != sessionId || session == null) return@post
            val entry = docs.entries.firstOrNull { it.value.hostPath == hostPath } ?: return@post
            val shown = if (config.hideSources.isEmpty()) items else items.filter { it.source !in config.hideSources }
            events.onDiagnostics(entry.key, hostPath, shown, entry.value.stamp)
        }

        override fun onClosed(reason: String) = env.post {
            if (id != sessionId || session == null) return@post
            sessionEnded(reason)
        }
    }
}

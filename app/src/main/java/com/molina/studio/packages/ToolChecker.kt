package com.molina.studio.packages

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.molina.code.preview.RunPlan
import com.molina.code.run.RunHandle
import com.molina.code.run.RunListener
import com.molina.code.run.RunRequest
import com.molina.code.run.RunResult
import com.molina.studio.automation.AutoPrefs
import com.molina.studio.code.ProotScriptExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [PAKET-CHECKER] Satu-satunya pemeriksa versi alat. Tanpa state Compose: halaman Paket hanya mengamati [state],
 * sehingga pemeriksaan tidak ikut mati saat halaman ditutup dan bisa dipicu dari luar UI (mis. worker).
 * Antrean BERURUTAN (semua memakai satu PRoot Debian) di lane [ProotScriptExecutor.Lane.TOOLS], terpisah dari Run.
 */
object ToolChecker {
    data class State(
        val checkingId: String? = null,
        /** Sisa alat di antrean, tidak termasuk yang sedang diperiksa. */
        val remaining: Int = 0,
        /** Jumlah alat pada pemeriksaan massal; 0 = pemeriksaan tunggal atau otomatis. */
        val total: Int = 0,
        /** [PAKET-HEADER] Jumlah alat pada antrean aktif (selalu terisi, juga untuk cek otomatis); 0 = tidak ada pemeriksaan. */
        val size: Int = 0,
        val note: String? = null,
        val notes: Map<String, String> = emptyMap()
    )

    private const val VERSION_TIMEOUT_MS = 2 * 60_000L
    private const val STALE_MS = 24L * 60 * 60 * 1000
    private const val NEXT_DELAY_MS = 50L // beri waktu eksekutor melepas lane sebelum alat berikutnya

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var handle: RunHandle? = null
    private var app: Context? = null
    private var autoRun = false
    private var total = 0
    private var ok = 0
    private var autoDone = false

    /** Mulai memeriksa [ids] berurutan. false bila pemeriksaan lain masih berjalan. */
    @Synchronized
    fun start(ctx: Context, ids: List<String>, batch: Boolean, auto: Boolean = false): Boolean {
        if (_state.value.checkingId != null) return false
        app = ctx.applicationContext
        ToolStore.init(ctx)
        autoRun = auto
        ok = 0
        total = if (batch) ids.size else 0
        queue.clear()
        queue.addAll(ids)
        _state.update { it.copy(note = null, total = total, size = ids.size, remaining = queue.size) }
        runNext()
        return true
    }

    /** Hentikan alat yang sedang diperiksa dan kosongkan antrean. */
    @Synchronized
    fun cancel() {
        queue.clear()
        handle?.cancel()
    }

    /** Alat ber-skrip versi yang belum pernah diperiksa atau hasilnya lebih tua dari 24 jam. */
    fun staleIds(): List<String> {
        val now = System.currentTimeMillis()
        return (BuiltinTools.all + ToolStore.custom.value)
            .filter { it.version.isNotBlank() }
            .filter { t -> ToolStore.checks.value[t.id]?.let { it.text.isBlank() || now - it.at > STALE_MS } ?: true }
            .map { it.id }
    }

    /** [PAKET-AUTOCEK] Cek otomatis hanya sekali per proses aplikasi, agar PRoot tidak dibebani berulang. */
    @Synchronized
    fun requestStale(ctx: Context, force: Boolean = false): Boolean { // [PAKET-WORKER2] force = dipanggil worker harian, abaikan penjaga per proses
        if (autoDone && !force) return false
        ToolStore.init(ctx)
        val ids = staleIds()
        if (ids.isEmpty()) {
            autoDone = true
            return false
        }
        val started = start(ctx, ids, batch = false, auto = true) // [PAKET-WORKER] gagal mulai (cek lain berjalan) = boleh dicoba lagi
        if (started) autoDone = true
        return started
    }

    @Synchronized
    private fun runNext() {
        val ctx = app ?: return
        val tid = queue.removeFirstOrNull()
        if (tid == null) {
            handle = null
            _state.update { it.copy(checkingId = null, remaining = 0, total = 0, size = 0, note = if (total > 0) "Selesai: $ok dari $total terdeteksi." else it.note) }
            total = 0
            return
        }
        val tool = (BuiltinTools.all + ToolStore.custom.value).firstOrNull { it.id == tid }
        val script = tool?.version.orEmpty()
        if (tool == null || script.isBlank()) {
            putNote(tid, "Skrip versi belum diisi.")
            runNext()
            return
        }
        _state.update { it.copy(checkingId = tid, remaining = queue.size, notes = it.notes - tid) }
        val out = StringBuilder()
        val req = RunRequest(
            name = "tool",
            scriptText = "set -e\n" + script.trim() + "\n",
            plan = RunPlan("sh", "/bin/sh", "sh", false),
            hostDir = null,
            timeoutMs = VERSION_TIMEOUT_MS
        )
        handle = ProotScriptExecutor(ctx, ProotScriptExecutor.Lane.TOOLS).start(req, object : RunListener {
            override fun onOutput(text: String, isError: Boolean) { main.post { if (out.length < 20_000) out.append(text) } }
            override fun onFinished(result: RunResult) { main.post { finish(tool, tid, out.toString(), result) } }
        })
    }

    @Synchronized
    private fun finish(tool: ToolItem, tid: String, output: String, r: RunResult) {
        handle = null
        when {
            r.startError != null || r.timedOut -> putNote(tid, r.startError ?: "Lewat batas waktu.")
            r.cancelled -> putNote(tid, "Pemeriksaan dihentikan.")
            else -> {
                val detected = r.exitCode == 0
                val line = firstLine(output)
                if (detected) ok += 1
                ToolStore.setCheck(tid, if (detected) line else "Tidak terdeteksi (kode ${r.exitCode})", autoRun)
                if (tool.nativeInstall && detected) syncYtdlp(line)
            }
        }
        if (r.cancelled) {
            queue.clear()
            _state.update { it.copy(checkingId = null, remaining = 0, total = 0, size = 0, note = if (total > 0) "Dihentikan: $ok dari $total terdeteksi." else it.note) }
            total = 0
        } else {
            main.postDelayed({ runNext() }, NEXT_DELAY_MS)
        }
    }

    private fun putNote(id: String, msg: String) {
        _state.update { it.copy(notes = it.notes + (id to msg)) }
    }

    /** [PAKET-SERAGAM] Jaga lencana pembaruan tetap sinkron dengan hasil cek versi di kartu. */
    private fun syncYtdlp(version: String) {
        val ctx = app ?: return
        if (version.isNotBlank()) AutoPrefs.setYtdlpInstalled(ctx, version)
    }

    private fun firstLine(out: String): String =
        out.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80) ?: "OK"
}

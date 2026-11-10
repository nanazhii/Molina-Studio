package com.molina.studio.code

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.molina.code.preview.RunPlan
import com.molina.code.run.RunListener
import com.molina.code.run.RunRequest
import com.molina.code.run.RunResult
import com.molina.core.common.MolinaDir
import com.molina.studio.AppPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [SCRIPT-HINT] Penanda amber di Script Execution. Satu proses PRoot (`molina-run --scan <folder>`) memeriksa seluruh
 * folder Molina/Scripts dengan logika --hint yang sama seperti nano; hasilnya di-cache lewat tanda tangan folder
 * (nama + waktu ubah + ukuran + sakelar tips), jadi pindai 2 detik di halaman tidak memanggil PRoot bila tidak ada perubahan.
 * Pindai dan perbaikan tidak pernah berjalan bersamaan. Semua kegagalan (Debian/molina-run belum ada, waktu habis,
 * folder tak terlihat di guest) berujung tanpa penanda, bukan galat.
 */
object ScriptHints {
    private const val SCAN_TIMEOUT_MS = 20_000L
    private const val FIX_TIMEOUT_MS = 15_000L
    private const val MAX_OUT = 64_000
    private const val MISSING = "molina-run belum terpasang. Buka Molina Terminal sekali."

    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    @Volatile private var lastSig: String? = null
    private val _hints = MutableStateFlow<Map<String, ScriptHintParser.Hint>>(emptyMap())

    /** Nama berkas skrip -> saran. Kosong bila tidak ada saran atau tips dimatikan. */
    val hints: StateFlow<Map<String, ScriptHintParser.Hint>> = _hints.asStateFlow()

    private fun request(vararg argv: String, timeoutMs: Long) = RunRequest(
        name = "molina-run",
        scriptText = "",
        plan = RunPlan("sh", "/bin/sh", "sh", false),
        hostDir = null,
        timeoutMs = timeoutMs,
        maxOutputChars = MAX_OUT.toLong(),
        guestArgv = listOf("molina-run") + argv.toList(),
        missingHint = MISSING
    )

    private fun ok(r: RunResult) = r.startError == null && !r.timedOut && !r.cancelled

    /**
     * Pindai bila keadaan folder berubah sejak pindai terakhir. Tidak memblokir (proses jalan di thread latar executor),
     * tetapi membaca berkas pengaturan tips: panggil di luar main thread.
     */
    fun refresh(ctx: Context, items: List<ScriptItem>) {
        val app = ctx.applicationContext
        val files = items.mapNotNull { it.path?.let { p -> Triple(File(p).name, it.updatedAt, it.body.length) } }
        val sig = ScriptHintParser.signature(files, RunCheckPrefs.notesOn(app))
        if (sig == lastSig) return
        if (files.isEmpty()) {
            lastSig = sig
            _hints.value = emptyMap()
            return
        }
        if (!busy.compareAndSet(false, true)) return // dicoba lagi pada refresh berikutnya (lastSig belum berubah)
        val dir = AppPaths.get(app).dir(MolinaDir.Scripts).path
        val out = StringBuilder()
        try {
            ProotScriptExecutor(app, ProotScriptExecutor.Lane.SCRIPTS).start(
                request("--scan", dir, timeoutMs = SCAN_TIMEOUT_MS),
                object : RunListener {
                    override fun onOutput(text: String, isError: Boolean) {
                        if (!isError) synchronized(out) { if (out.length < MAX_OUT) out.append(text) }
                    }

                    override fun onFinished(result: RunResult) {
                        try {
                            _hints.value = if (ok(result) && result.exitCode == 0) {
                                ScriptHintParser.parse(synchronized(out) { out.toString() })
                            } else {
                                emptyMap()
                            }
                            lastSig = sig
                        } finally {
                            busy.set(false)
                        }
                    }
                }
            )
        } catch (e: Exception) {
            busy.set(false)
        }
    }

    /**
     * Tombol FIX: jalankan `molina-run --fix <berkas>` (tambah shebang + cadangan .bak). [onDone] dipanggil di main thread
     * dengan pesan siap tampil. Penanda hilang segera bila berhasil; sisanya mengikuti pindai berikutnya.
     */
    fun fix(ctx: Context, item: ScriptItem, onDone: (String) -> Unit) {
        val app = ctx.applicationContext
        val path = item.path
        if (path == null) {
            onDone("Skrip ini belum berupa berkas di folder Scripts.")
            return
        }
        if (!busy.compareAndSet(false, true)) {
            onDone("Pemeriksaan sedang berjalan, coba lagi sebentar.")
            return
        }
        val out = StringBuilder()
        try {
            ProotScriptExecutor(app, ProotScriptExecutor.Lane.SCRIPTS).start(
                request("--fix", path, timeoutMs = FIX_TIMEOUT_MS),
                object : RunListener {
                    override fun onOutput(text: String, isError: Boolean) {
                        synchronized(out) { if (out.length < MAX_OUT) out.append(text) }
                    }

                    override fun onFinished(result: RunResult) {
                        val text = synchronized(out) { out.toString() }
                        val fixed = ok(result) && result.exitCode == 0
                        if (fixed) _hints.value = _hints.value - File(path).name
                        lastSig = null // paksa pindai ulang pada refresh berikutnya
                        busy.set(false)
                        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                        val msg = when {
                            result.startError != null -> result.startError.orEmpty()
                            result.timedOut -> "Perbaikan melewati batas waktu."
                            fixed -> first ?: "Diperbaiki: ${File(path).name}"
                            else -> first ?: "Gagal memperbaiki ${File(path).name}."
                        }
                        main.post { onDone(msg) }
                    }
                }
            )
        } catch (e: Exception) {
            busy.set(false)
            onDone("Gagal menjalankan perbaikan.")
        }
    }
}

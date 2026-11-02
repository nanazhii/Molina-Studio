package com.molina.code.ui

import android.os.Handler
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import com.molina.code.lsp.LiveState
import com.molina.code.lsp.LspLiveEnv
import com.molina.code.lsp.MemInfo
import java.io.File

/** [CODE-LSP3] Penjadwal main thread + sensor RAM (/proc/meminfo) untuk [com.molina.code.lsp.LspLive]. */
internal class AndroidLiveEnv(private val main: Handler) : LspLiveEnv {
    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
        val r = Runnable { task() }
        main.postDelayed(r, delayMs)
        return { main.removeCallbacks(r) }
    }

    override fun post(task: () -> Unit) { main.post { task() } }

    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun memAvailableKb(): Long = try {
        MemInfo.availableKb(File("/proc/meminfo").readText())
    } catch (e: Exception) {
        -1L
    }
}

/** Lencana kecil di bilah status: teks pendek + warna token Molina. Null = tidak ditampilkan. */
internal class LiveBadge(val label: String, val color: Color)

internal fun liveBadge(state: LiveState, detail: String?): LiveBadge? = when (state) {
    LiveState.OFF -> null
    // [CODE-BADGE] Emas hangat (keluarga Molina Cinema, sama dengan penanda aktif pemutar) = siap; redup = belum siap.
    LiveState.IDLE -> if (detail != null) LiveBadge("LSP RAM", CodeColors.ClaudeOrange) else LiveBadge("LSP siaga", CodeColors.TextSecondary)
    LiveState.STARTING -> LiveBadge("LSP memuat", CodeColors.TextSecondary)
    LiveState.READY -> LiveBadge("LSP siap", CodeColors.Gold)
    LiveState.SLEEPING -> LiveBadge("LSP tidur", CodeColors.TextSecondary)
    LiveState.ERROR -> LiveBadge("LSP galat", CodeColors.Danger)
}

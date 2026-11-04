package com.molina.studio.settings

import android.content.Context

internal class HistoryEntry(val time: Long, val level: CheckLevel, val summary: String)

/**
 * [DIAG-HISTORY] Lima hasil pemeriksaan terakhir yang dilihat pengguna di dialog Diagnostik. Hanya ringkasan
 * ("5/8 sehat; Debian jalan"): tanpa jalur, URL, atau pesan galat. Disimpan di SharedPreferences privat.
 */
internal object HealthHistory {
    private const val PREFS = "molina_diag"
    private const val KEY = "health_history"
    private const val MAX = 5

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Terbaru lebih dulu. */
    fun load(ctx: Context): List<HistoryEntry> = synchronized(this) {
        (prefs(ctx).getString(KEY, null) ?: "").lineSequence().mapNotNull { line ->
            val p = line.split("|", limit = 3)
            val time = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val level = CheckLevel.values().getOrNull(p.getOrNull(1)?.toIntOrNull() ?: -1) ?: return@mapNotNull null
            HistoryEntry(time, level, p.getOrNull(2).orEmpty())
        }.toList()
    }

    /** [DIAG-PATTERN] Apakah masalah pada pemeriksaan terbaru baru atau berulang di riwayat. Null bila tidak ada yang bisa disimpulkan. */
    fun pattern(list: List<HistoryEntry>): String? {
        val latest = list.firstOrNull() ?: return null
        if (latest.level == CheckLevel.Ok) return "pemeriksaan terakhir sehat"
        val culprit = latest.summary.substringAfter("; ", "")
        if (culprit.isEmpty()) return null
        val n = list.count { it.level != CheckLevel.Ok && it.summary.substringAfter("; ", "") == culprit }
        return if (n <= 1) "$culprit bermasalah (baru, belum berulang di riwayat)"
        else "$culprit bermasalah di $n dari ${list.size} pemeriksaan terakhir"
    }

    fun add(ctx: Context, list: List<CheckResult>) = synchronized(this) {
        val worst = HealthCheck.worst(list)
        val ok = list.count { it.level == CheckLevel.Ok }
        val culprit = if (worst == CheckLevel.Ok) null else list.firstOrNull { it.level == worst }?.name
        val summary = "$ok/${list.size} sehat" + (culprit?.let { "; $it" } ?: "")
        val line = "${System.currentTimeMillis()}|${worst.ordinal}|$summary"
        val all = (listOf(line) + (prefs(ctx).getString(KEY, null) ?: "").lineSequence().filter { it.isNotBlank() }).take(MAX)
        prefs(ctx).edit().putString(KEY, all.joinToString("\n")).apply()
    }
}

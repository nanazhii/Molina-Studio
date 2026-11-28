package com.molina.core.net

/**
 * [ERR-SUMMARY] Ringkasan satu baris untuk bar status dialog Saluran: total channel ERR dan jumlah per grup
 * (terbanyak dulu, maks [maxGroups] grup). Kosong bila tidak ada channel ERR. Murni Kotlin.
 */
object ChannelErrSummary {
    const val NO_GROUP = "Tanpa grup"

    fun summarize(channels: List<IptvChannel>, maxGroups: Int = 3): String {
        val byGroup = LinkedHashMap<String, Int>()
        var total = 0
        for (c in channels) {
            if (!c.broken) continue
            total++
            val g = c.group.ifEmpty { NO_GROUP }
            byGroup[g] = (byGroup[g] ?: 0) + 1
        }
        if (total == 0) return ""
        val sorted = byGroup.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val shown = sorted.take(maxGroups.coerceAtLeast(0))
        val sb = StringBuilder("$total ERR")
        if (shown.isNotEmpty()) sb.append(" \u00B7 ").append(shown.joinToString(" \u00B7 ") { it.key + ": " + it.value })
        val rest = sorted.size - shown.size
        if (rest > 0) sb.append(" \u00B7 +").append(rest).append(" grup lain")
        return sb.toString()
    }
}

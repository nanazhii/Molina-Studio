package com.molina.studio.cinema

import com.molina.core.common.StreamResolvers
import java.io.File

/**
 * Penyegaran memori ringan untuk tombol Reset di Cinema Home: melepas resolver stream (bila tidak
 * ada video di latar), meminta GC, lalu mengukur RSS proses sebelum dan sesudah. Tidak bisa
 * mengembalikan memori native yang sudah dipegang alokator (mis. bekas libmpv) ke sistem.
 */
internal object MemoryRefresh {
    class Result(val beforeKb: Long, val afterKb: Long, val released: Boolean)

    /** Nilai VmRSS (KB) dari isi /proc/self/status, atau -1 bila tidak ada/rusak. */
    fun parseRssKb(status: String): Long {
        val line = status.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return -1
        return line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: -1
    }

    fun rssKb(): Long = runCatching { parseRssKb(File("/proc/self/status").readText()) }.getOrDefault(-1)

    /** Panggil dari thread latar (ada jeda untuk GC). */
    fun run(releaseResolver: Boolean): Result {
        val before = rssKb()
        if (releaseResolver) StreamResolvers.release()
        repeat(2) {
            System.gc()
            try { Thread.sleep(150) } catch (e: InterruptedException) { }
        }
        return Result(before, rssKb(), releaseResolver)
    }

    fun describe(r: Result): String {
        if (r.beforeKb < 0 || r.afterKb < 0) return "Pencarian dibersihkan."
        val note = if (r.released) "" else " (video di latar, mesin tetap aktif)"
        return "RAM proses: ${r.beforeKb / 1024} \u2192 ${r.afterKb / 1024} MB$note"
    }
}

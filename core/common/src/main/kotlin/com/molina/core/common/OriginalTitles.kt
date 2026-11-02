package com.molina.core.common

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Judul hasil pencarian YouTube dilokalkan/diterjemahkan oleh YouTube menurut hl, sedangkan halaman
 * video (player) memakai judul asli pengunggah. Objek ini mengambil judul asli lewat oEmbed (ringan,
 * satu permintaan per video) lalu memakainya menggantikan judul hasil pencarian. Yang gagal diambil
 * tetap memakai judul pencarian. JVM murni: pengambil [fetch] disuntikkan agar bisa diuji.
 */
object OriginalTitles {
    private const val THREADS = 6
    private const val BUDGET_S = 12L
    private const val CACHE_MAX = 400
    private val cache = ConcurrentHashMap<String, String>()

    class Result(val items: List<StreamSearchItem>, val changed: Int, val known: Int)

    /** Isi badan JSON oEmbed -> judul, atau null bila bukan JSON/ tanpa judul. */
    fun titleOf(body: String?): String? =
        body?.let { MiniJson.parseObject(it)?.get("title") as? String }?.trim()?.takeIf { it.isNotEmpty() }

    /** Pembatalan (interupsi) diteruskan; galat tiap video diabaikan. */
    fun apply(items: List<StreamSearchItem>, fetch: (String) -> String?): Result {
        if (items.isEmpty()) return Result(items, 0, 0)
        val todo = items.map { it.pageUrl }.filter { !cache.containsKey(it) }.distinct()
        if (todo.isNotEmpty()) {
            val pool = Executors.newFixedThreadPool(minOf(THREADS, todo.size))
            try {
                val tasks = todo.map { u -> Callable { u to titleOf(fetch(u)) } }
                for (f in pool.invokeAll(tasks, BUDGET_S, TimeUnit.SECONDS)) {
                    if (f.isCancelled) continue
                    val r = try { f.get() } catch (e: ExecutionException) { continue }
                    val t = r.second ?: continue
                    if (cache.size >= CACHE_MAX) cache.clear()
                    cache[r.first] = t
                }
            } finally {
                pool.shutdownNow()
            }
        }
        var changed = 0
        val out = items.map { i ->
            val t = cache[i.pageUrl]
            if (t != null && t != i.title) { changed++; i.copy(title = t) } else i
        }
        return Result(out, changed, items.count { cache.containsKey(it.pageUrl) })
    }
}

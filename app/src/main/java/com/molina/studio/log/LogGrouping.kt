package com.molina.studio.log

import com.molina.core.common.LogEvent
import com.molina.core.common.LogLevel
import com.molina.core.common.LogSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class LogEntry(
    val id: Long,
    val time: Long,
    val source: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val count: Int = 1
)

/**
 * Bus log tunggal aplikasi (LogGrouping).
 * - Ring buffer terbatas (MAX_ENTRIES).
 * - Grouping: baris beruntun yang identik digabung menjadi satu entri dengan hitungan.
 * - Publikasi ke UI dibatasi (sekali per PUBLISH_DELAY_MS) agar ledakan log tidak membebani main thread.
 * Aman dipanggil dari thread mana pun.
 */
object LogBus : LogSink {
    private const val MAX_ENTRIES = 1000
    private const val PUBLISH_DELAY_MS = 100L

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>()
    private var nextId = 0L
    private val pending = AtomicBoolean(false)
    private val publisher = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "molina-log-publish").apply { isDaemon = true }
    }

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun log(source: String, level: LogLevel, tag: String, message: String) {
        val src = clean(source, 32).lowercase().ifEmpty { "luar" }
        val tg = clean(tag, 64)
        val msg = clean(message, 2000)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val last = buffer.lastOrNull()
            if (last != null && last.source == src && last.level == level &&
                last.tag == tg && last.message == msg
            ) {
                buffer[buffer.lastIndex] = last.copy(count = last.count + 1, time = now)
            } else {
                buffer.addLast(LogEntry(nextId++, now, src, level, tg, msg))
                while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            }
        }
        schedulePublish()
    }

    override fun emit(event: LogEvent) =
        log(event.source, event.level, event.tag, event.message)

    fun clear() {
        synchronized(lock) { buffer.clear() }
        schedulePublish()
    }

    private fun clean(s: String, max: Int): String =
        s.map { if (it < ' ') ' ' else it }.joinToString("").take(max)

    private fun schedulePublish() {
        if (pending.compareAndSet(false, true)) {
            publisher.schedule(Runnable {
                pending.set(false)
                val snapshot = synchronized(lock) { buffer.toList() }
                _entries.value = snapshot
            }, PUBLISH_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }
}

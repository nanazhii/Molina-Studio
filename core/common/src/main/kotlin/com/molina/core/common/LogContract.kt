package com.molina.core.common

/** Tingkat log lintas modul. */
enum class LogLevel(val code: Char) {
    Debug('D'), Info('I'), Warn('W'), Error('E');

    companion object {
        fun fromCode(c: Char): LogLevel =
            entries.firstOrNull { it.code == c.uppercaseChar() } ?: Info
    }
}

/** Satu event log dari producer (belum digabung dan belum diberi id). */
data class LogEvent(
    val source: String,
    val level: LogLevel,
    val tag: String,
    val message: String
)

/** Penerima event log. :app menyediakan implementasi (LogBus). Aman dipanggil dari thread mana pun. */
fun interface LogSink {
    fun emit(event: LogEvent)
}

/**
 * Pintu log untuk semua modul tanpa melihat :app. :app memasang sink sekali saat start
 * (AppLog.install); sebelum terpasang, event dibuang.
 */
object AppLog {
    @Volatile private var sink: LogSink? = null

    fun install(sink: LogSink) {
        this.sink = sink
    }

    fun log(source: String, level: LogLevel, tag: String, message: String) {
        sink?.emit(LogEvent(source, level, tag, message))
    }
}

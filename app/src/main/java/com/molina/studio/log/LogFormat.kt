package com.molina.studio.log

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * [LOGCAT-FORMAT] Format keluaran persis `adb logcat -v <format>` untuk baris logcat mentah.
 * Kotlin murni agar mudah diuji. Satu format aktif ([current]) dipakai tampilan mentah, salin, bagikan, dan ekspor.
 */
enum class LogFormat(val label: String) {
    Threadtime("threadtime"), Time("time"), Brief("brief"), Tag("tag"),
    Process("process"), Raw("raw"), LongText("long");

    fun render(timeMs: Long, pid: Int, tid: Int, prio: Char, tag: String, msg: String): String {
        val ts = STAMP.format(Instant.ofEpochMilli(timeMs))
        val p = pid.toString().padStart(5)
        val t = tid.toString().padStart(5)
        val tg = tag.padEnd(8)
        return when (this) {
            Threadtime -> "$ts $p $t $prio $tg: $msg"
            Time -> "$ts $prio/$tg($p): $msg"
            Brief -> "$prio/$tg($p): $msg"
            Tag -> "$prio/$tg: $msg"
            Process -> "$prio($p) $msg  ($tag)"
            Raw -> msg
            LongText -> "[ $ts $p:$t $prio/$tg ]\n$msg\n"
        }
    }

    fun next(): LogFormat = entries[(ordinal + 1) % entries.size]

    companion object {
        private val STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

        /** Format aktif untuk tampilan/salin/ekspor; default threadtime (sama dengan fase 1-2). */
        @Volatile var current: LogFormat = Threadtime
    }
}

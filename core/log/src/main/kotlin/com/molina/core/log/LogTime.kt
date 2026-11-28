package com.molina.core.log

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** [LOG-TIME] Satu pola jam untuk baris log aplikasi (HH:mm:ss.SSS): dipakai layar Log dan laporan bug. */
object LogTime {
    private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    fun format(timeMs: Long): String = FMT.format(Instant.ofEpochMilli(timeMs))
}

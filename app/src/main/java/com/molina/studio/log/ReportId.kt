package com.molina.studio.log

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/** [DIAG-ID] ID laporan MOL-yyyyMMdd-HHmmss-XXXX: sama di nama ZIP, ringkasan.txt, laporan-bug.txt, dan info debug. */
internal object ReportId {
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

    fun new(): String = "MOL-" + STAMP.format(Instant.now()) + "-" + "%04X".format(Random.nextInt(0x10000))
}

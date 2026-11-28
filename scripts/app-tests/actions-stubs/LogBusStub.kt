package com.molina.core.log

import com.molina.core.api.LogLevel

object LogBus {
    val entries = mutableListOf<String>()
    fun log(source: String, level: LogLevel, tag: String, message: String) { entries.add("$source/$level/$tag: $message") }
}

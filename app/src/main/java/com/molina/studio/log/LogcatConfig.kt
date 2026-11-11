package com.molina.studio.log

import android.content.Context

/**
 * [LOGCAT-CONFIG] Opsi baca logcat setara `adb logcat -b <buffer> -T <n>`; disimpan antar sesi.
 * buffer "" = bawaan logcat (main,system,crash); tail 0 = semua isi buffer.
 */
object LogcatConfig {
    private val BUFFERS = listOf("", "main", "main,crash", "all")
    private val TAILS = listOf(50, 200, 1000, 5000, 0)

    @Volatile var buffer: String = ""
    @Volatile var tail: Int = 200
    private var prefs: android.content.SharedPreferences? = null

    fun attach(c: Context) {
        val p = c.applicationContext.getSharedPreferences("molina_logcat", Context.MODE_PRIVATE)
        prefs = p
        p.getString("buffer", "")?.takeIf { it in BUFFERS }?.let { buffer = it }
        p.getInt("tail", 200).takeIf { it in TAILS }?.let { tail = it }
    }

    fun bufferLabel(): String = if (buffer.isEmpty()) "default" else buffer
    fun tailLabel(): String = if (tail == 0) "semua" else tail.toString()

    fun cycleBuffer() {
        buffer = BUFFERS[(BUFFERS.indexOf(buffer) + 1) % BUFFERS.size]
        prefs?.edit()?.putString("buffer", buffer)?.apply()
    }

    fun cycleTail() {
        tail = TAILS[(TAILS.indexOf(tail) + 1) % TAILS.size]
        prefs?.edit()?.putInt("tail", tail)?.apply()
    }

    /** replay = true: putar ulang ekor sesuai [tail]; false: hanya baris baru (-T 1). */
    fun command(replay: Boolean, pid: Int): List<String> {
        val c = ArrayList<String>()
        c.add("logcat"); c.add("-v"); c.add("threadtime")
        if (buffer.isNotEmpty()) { c.add("-b"); c.add(buffer) }
        if (!replay) { c.add("-T"); c.add("1") }
        else if (tail > 0) { c.add("-T"); c.add(tail.toString()) }
        c.add("--pid=$pid")
        return c
    }
}

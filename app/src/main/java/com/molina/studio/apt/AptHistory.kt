package com.molina.studio.apt

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Waktu terakhir "perbarui dan tingkatkan" berhasil (SharedPreferences), ditampilkan di menu Debian. */
internal object AptHistory {
    private const val PREFS = "molina_apt"
    private const val KEY = "last_ok"
    private val _last = MutableStateFlow(0L)
    val last: StateFlow<Long> = _last.asStateFlow()
    private const val KEY_PENDING = "pending"
    private val _pending = MutableStateFlow(-1)
    /** Jumlah paket yang bisa ditingkatkan dari pemeriksaan terakhir; -1 = belum pernah diperiksa. */
    val pending: StateFlow<Int> = _pending.asStateFlow()
    private var loaded = false

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _last.value = sp.getLong(KEY, 0L)
        _pending.value = sp.getInt(KEY_PENDING, -1)
    }

    @Synchronized
    fun mark(ctx: Context) {
        val now = System.currentTimeMillis()
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, now).apply()
        _last.value = now
    }

    @Synchronized
    fun setPending(ctx: Context, n: Int) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_PENDING, n).apply()
        _pending.value = n
    }

    fun checkSubtitle(n: Int): String = when {
        n < 0 -> "Cek apakah ada program Debian yang bisa diperbarui"
        n == 0 -> "Sistem terbaru"
        else -> "$n pembaruan menunggu dipasang"
    }

    fun subtitle(at: Long): String =
        if (at <= 0L) "Belum pernah diperbarui di sini"
        else "Terakhir berhasil: " + SimpleDateFormat("d MMM HH.mm", Locale("id", "ID")).format(Date(at))
}

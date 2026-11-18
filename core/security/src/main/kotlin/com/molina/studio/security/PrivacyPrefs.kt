package com.molina.studio.security

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Privasi layar jendela utama (Terminal, Pengaturan): FLAG_SECURE (tangkapan layar, perekam layar,
 * dan thumbnail Recents jadi hitam) serta penyaringan sentuhan saat tertutup overlay (tapjacking).
 * Default NYALA. Pemutar mpv sengaja tidak ikut agar fitur screenshot video tetap jalan.
 */
object PrivacyPrefs {
    private const val FILE = "molina_privacy"
    private const val KEY_SECURE = "secure_screen"

    private var sp: SharedPreferences? = null
    private val _secureScreen = MutableStateFlow(true)

    val secureScreen: StateFlow<Boolean> = _secureScreen.asStateFlow()

    /** Diisi MainActivity agar perubahan langsung diterapkan ke jendela yang sedang tampil. */
    @Volatile
    var onChange: (() -> Unit)? = null

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = p
        _secureScreen.value = p.getBoolean(KEY_SECURE, true)
    }

    fun setSecureScreen(v: Boolean) {
        _secureScreen.value = v
        sp?.edit()?.putBoolean(KEY_SECURE, v)?.apply()
        onChange?.invoke()
    }
}

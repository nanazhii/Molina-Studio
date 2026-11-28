package com.molina.studio.code

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preferensi editor Code. [htmlWebView]: pratinjau HTML memakai WebView alih-alih renderer native.
 * Default mati; WebView selalu dibuka tanpa JavaScript, tanpa akses jaringan, dan tanpa akses berkas.
 */
object CodePrefs {
    private const val FILE = "molina_code"
    private const val KEY_WEBVIEW = "html_webview"

    private var sp: SharedPreferences? = null
    private val _htmlWebView = MutableStateFlow(false)

    val htmlWebView: StateFlow<Boolean> = _htmlWebView.asStateFlow()

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = p
        _htmlWebView.value = p.getBoolean(KEY_WEBVIEW, false)
    }

    fun setHtmlWebView(v: Boolean) {
        _htmlWebView.value = v
        sp?.edit()?.putBoolean(KEY_WEBVIEW, v)?.apply()
    }
}

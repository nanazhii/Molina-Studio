package com.molina.studio.security

import android.content.Context
import android.content.SharedPreferences
import com.molina.core.common.ShieldProxy
import com.molina.core.common.TrackerList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pengaturan Molina Shield. Default: Shield NYALA, blokir pelacak NYALA, Referer diteruskan
 * (sebagian server IPTV mewajibkannya). Nilainya dicerminkan ke [ShieldProxy] dan [TrackerList].
 * Opsi `http_proxy` global mpv dibaca saat mpv diinisialisasi, jadi mematikan/menyalakan Shield
 * berlaku penuh setelah Refresh Pemutar atau membuka ulang pemutar.
 */
object ShieldPrefs {
    private const val FILE = "molina_shield"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TRACKERS = "block_trackers"
    private const val KEY_REFERER = "strip_referer"

    private var sp: SharedPreferences? = null
    private val _enabled = MutableStateFlow(true)
    private val _trackers = MutableStateFlow(true)
    private val _referer = MutableStateFlow(false)

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val blockTrackers: StateFlow<Boolean> = _trackers.asStateFlow()
    val stripReferer: StateFlow<Boolean> = _referer.asStateFlow()

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = p
        _enabled.value = p.getBoolean(KEY_ENABLED, true)
        _trackers.value = p.getBoolean(KEY_TRACKERS, true)
        _referer.value = p.getBoolean(KEY_REFERER, false)
        apply()
    }

    fun setEnabled(v: Boolean) {
        _enabled.value = v
        sp?.edit()?.putBoolean(KEY_ENABLED, v)?.apply()
        apply()
    }

    fun setBlockTrackers(v: Boolean) {
        _trackers.value = v
        sp?.edit()?.putBoolean(KEY_TRACKERS, v)?.apply()
        apply()
    }

    fun setStripReferer(v: Boolean) {
        _referer.value = v
        sp?.edit()?.putBoolean(KEY_REFERER, v)?.apply()
        apply()
    }

    private fun apply() {
        ShieldProxy.enabled = _enabled.value
        ShieldProxy.stripReferer = _referer.value
        TrackerList.enabled = _trackers.value
    }
}

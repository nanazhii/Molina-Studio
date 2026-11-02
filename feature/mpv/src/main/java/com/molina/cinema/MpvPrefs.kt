package com.molina.cinema

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pintu tunggal setelan pemutar Molina Cinema.
 *
 * Sumber data tetap SharedPreferences bawaan karena layar setelan XML (PreferenceActivity)
 * menulis ke berkas yang sama; menyalinnya ke penyimpanan lain akan membuat dua sumber
 * kebenaran. Kunci TIDAK berubah, jadi setelan pengguna yang sudah ada tetap berlaku.
 *
 * - [current] membaca ulang (peta dalam memori, murah) dan selalu mengembalikan snapshot terbaru.
 * - [settings] adalah StateFlow untuk collector non-UI; diperbarui lewat listener perubahan.
 * - Pembacaan tahan tipe salah (ClassCastException) dan kembali ke nilai bawaan.
 */
object MpvPrefs {
    /** (kunci setelan, opsi mpv) yang diteruskan apa adanya bila nilainya tidak kosong. */
    private val OPTION_PREFS = listOf(
        "default_audio_language" to "alang",
        "default_subtitle_language" to "slang",
        "video_scale" to "scale",
        "video_scale_param1" to "scale-param1",
        "video_scale_param2" to "scale-param2",
        "video_downscale" to "dscale",
        "video_downscale_param1" to "dscale-param1",
        "video_downscale_param2" to "dscale-param2",
        "video_tscale" to "tscale",
        "video_tscale_param1" to "tscale-param1",
        "video_tscale_param2" to "tscale-param2"
    )

    private const val KEY_USE_TIME_REMAINING = "use_time_remaining"
    private const val KEY_LAST_BRIGHTNESS = "last_screen_brightness"

    private val flow = MutableStateFlow(MpvSettings())

    /** Aliran reaktif seluruh setelan (untuk service, Settings Hub, dsb.). */
    val settings: StateFlow<MpvSettings> = flow.asStateFlow()

    private lateinit var app: Context
    private var prefs: SharedPreferences? = null

    // Referensi kuat: SharedPreferences hanya menyimpan listener secara lemah.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, _ ->
        flow.value = read(sp)
    }

    @Synchronized
    private fun ensure(ctx: Context): SharedPreferences {
        prefs?.let { return it }
        app = ctx.applicationContext
        val sp = PreferenceManager.getDefaultSharedPreferences(app)
        // [NO-UI-PAUSE] Bawaan lama "audio-only" (video terjeda saat popup) berubah ke "always"; sekali saja.
        // Pilihan sadar "never" tidak disentuh.
        if (!sp.getBoolean("no_ui_pause_migrated_v1", false)) {
            val e = sp.edit()
            if (try { sp.getString("no_ui_pause", null) } catch (ex: ClassCastException) { null } == "audio-only")
                e.putString("no_ui_pause", "always")
            e.putBoolean("no_ui_pause_migrated_v1", true).apply()
        }
        prefs = sp
        sp.registerOnSharedPreferenceChangeListener(listener)
        return sp
    }

    /** Snapshot terbaru. Aman dipanggil berulang dan dari thread mana pun. */
    fun current(ctx: Context): MpvSettings {
        val s = read(ensure(ctx))
        flow.value = s
        return s
    }

    /**
     * Simpan status antarmuka yang diubah pemutar sendiri. Memakai commit() (berkas kecil)
     * agar tidak hilang bila proses dihentikan tepat setelah Activity berhenti.
     */
    fun saveUiState(
        ctx: Context,
        useTimeRemaining: Boolean,
        rememberBrightness: Boolean,
        lastScreenBrightness: Int
    ) {
        with(ensure(ctx).edit()) {
            putBoolean(KEY_USE_TIME_REMAINING, useTimeRemaining)
            if (rememberBrightness)
                putInt(KEY_LAST_BRIGHTNESS, lastScreenBrightness)
            else
                remove(KEY_LAST_BRIGHTNESS)
            commit()
        }
    }

    private fun read(sp: SharedPreferences): MpvSettings {
        val r = app.resources
        fun str(key: String, def: String): String =
            try { sp.getString(key, def) ?: def } catch (e: ClassCastException) { def }
        fun bool(key: String, def: Boolean): Boolean =
            try { sp.getBoolean(key, def) } catch (e: ClassCastException) { def }
        fun int(key: String, def: Int): Int =
            try { sp.getInt(key, def) } catch (e: ClassCastException) { def }
        // Gestur: nilai kosong dianggap belum diatur, jadi memakai bawaan resource.
        fun gesture(key: String, defRes: Int): String =
            str(key, "").ifEmpty { r.getString(defRes) }

        val options = LinkedHashMap<String, String>()
        for ((key, option) in OPTION_PREFS) {
            val v = str(key, "")
            if (v.isNotBlank()) options[option] = v
        }

        return MpvSettings(
            gpuNext = bool("gpu_next", false),
            hardwareDecoding = bool("hardware_decoding", true),
            videoDebanding = str("video_debanding", ""),
            videoSync = str("video_sync", r.getString(R.string.pref_video_interpolation_sync_default)),
            videoInterpolation = bool("video_interpolation", false),
            gpuDebug = bool("gpudebug", false),
            videoFastDecode = bool("video_fastdecode", false),
            options = options,

            statsMode = str("stats_mode", ""),
            backgroundPlay = str("background_play", r.getString(R.string.pref_background_play_default)),
            noUiPause = str("no_ui_pause", r.getString(R.string.pref_no_ui_pause_default)),
            savePosition = bool("save_position", false),
            autoRotation = str("auto_rotation", r.getString(R.string.pref_auto_rotation_default)),
            bottomControls = bool("bottom_controls", true),
            displayMediaTitle = bool("display_media_title", false),
            useTimeRemaining = bool(KEY_USE_TIME_REMAINING, false),
            rememberBrightness = bool("remember_brightness", false),
            lastScreenBrightness = int(KEY_LAST_BRIGHTNESS, -1),
            ignoreAudioFocus = bool("ignore_audio_focus", false),
            playlistExitWarning = bool("playlist_exit_warning", true),
            playFolder = bool("play_folder", true),
            newIntentReplace = bool("new_intent_replace", false),
            seekGestureSmooth = bool("seek_gesture_smooth", false),

            gestureHoriz = gesture("gesture_horiz", R.string.pref_gesture_horiz_default),
            gestureVertLeft = gesture("gesture_vert_left", R.string.pref_gesture_vert_left_default),
            gestureVertRight = gesture("gesture_vert_right", R.string.pref_gesture_vert_right_default),
            gestureTapLeft = gesture("gesture_tap_left", R.string.pref_gesture_tap_left_default),
            gestureTapCenter = gesture("gesture_tap_center", R.string.pref_gesture_tap_center_default),
            gestureTapRight = gesture("gesture_tap_right", R.string.pref_gesture_tap_right_default)
        )
    }
}

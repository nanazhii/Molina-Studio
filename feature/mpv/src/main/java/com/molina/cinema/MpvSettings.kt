package com.molina.cinema

/**
 * Snapshot immutable seluruh setelan pemutar (aman dibaca dari thread mana pun).
 *
 * Nilai bawaan string di bawah HARUS sama dengan res/values/strings.xml (pref_*_default);
 * MpvPrefs membaca nilai asli dari resource, nilai di sini hanya dipakai sebelum dimuat.
 */
data class MpvSettings(
    // Video (dipakai MPVView.initOptions)
    val gpuNext: Boolean = false,
    val hardwareDecoding: Boolean = true,
    val videoDebanding: String = "",
    val videoSync: String = "audio",
    val videoInterpolation: Boolean = false,
    val gpuDebug: Boolean = false,
    val videoFastDecode: Boolean = false,
    /** Opsi mpv hasil pemetaan kunci setelan (alang, slang, scale, dscale, tscale, ...); hanya nilai tak kosong. */
    val options: Map<String, String> = emptyMap(),

    // Pemutaran dan antarmuka (dipakai MPVActivity)
    val statsMode: String = "",
    val backgroundPlay: String = "never",
    val noUiPause: String = "audio-only",
    val savePosition: Boolean = false,
    val autoRotation: String = "auto",
    val bottomControls: Boolean = true,
    val displayMediaTitle: Boolean = false,
    val useTimeRemaining: Boolean = false,
    val rememberBrightness: Boolean = false,
    val lastScreenBrightness: Int = -1,
    val ignoreAudioFocus: Boolean = false,
    val playlistExitWarning: Boolean = true,
    val playFolder: Boolean = true,
    val newIntentReplace: Boolean = false,
    val seekGestureSmooth: Boolean = false,

    // Gestur (dipakai TouchGestures.syncSettings)
    val gestureHoriz: String = "seek",
    val gestureVertLeft: String = "bright",
    val gestureVertRight: String = "volume",
    val gestureTapLeft: String = "none",
    val gestureTapCenter: String = "none",
    val gestureTapRight: String = "none"
)

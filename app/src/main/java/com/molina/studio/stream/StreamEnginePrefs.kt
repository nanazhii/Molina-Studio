package com.molina.studio.stream

import android.content.Context
import android.content.SharedPreferences
import com.molina.core.common.NetworkPolicy
import com.molina.core.common.SearchLang
import com.molina.core.common.StreamAudioLang
import com.molina.core.common.StreamQuality
import com.molina.core.common.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pilihan mesin stream. yt-dlp (cadangan untuk TikTok, Facebook, dll.) DEFAULT MATI karena satu
 * resolusi memulai proot + python.
 *
 * Satu sumber kebenaran: [ytdlpEnabled]. UI mengamatinya lewat `collectAsState()`, thread latar
 * cukup membaca `ytdlpEnabled.value` (aman lintas thread).
 */
object StreamEnginePrefs {
    private const val FILE = "molina_stream"
    private const val KEY_YTDLP = "ytdlp_enabled"
    private const val KEY_LAN = "allow_lan"
    private const val KEY_BLOCK_PL = "block_playlist_streams"
    private const val KEY_LOCK_ID = "lock_id"
    private const val KEY_ORIG_TITLE = "orig_title"
    private const val KEY_QUALITY = "video_quality" // [QUALITY]
    private const val KEY_AUDIO_LANG = "audio_lang" // [AUDIO-LANG]

    private var sp: SharedPreferences? = null
    private val _ytdlpEnabled = MutableStateFlow(false)

    val ytdlpEnabled: StateFlow<Boolean> = _ytdlpEnabled.asStateFlow()

    private val _allowLan = MutableStateFlow(false)

    /** Izinkan alamat jaringan lokal (default mati). Nilainya juga dicerminkan ke [NetworkPolicy]. */
    val allowLan: StateFlow<Boolean> = _allowLan.asStateFlow()

    private val _blockPlaylistStreams = MutableStateFlow(false)

    /** [PL-STREAM-GUARD] Blokir rtmp di dalam playlist (default mati). Dicerminkan ke [NetworkPolicy.blockPlaylistStreams]. */
    val blockPlaylistStreams: StateFlow<Boolean> = _blockPlaylistStreams.asStateFlow()

    private val _lockIndonesian = MutableStateFlow(true)

    /** Kunci hasil pencarian ke bahasa Indonesia (default nyala). Dicerminkan ke [SearchLang.enabled]. */
    val lockIndonesian: StateFlow<Boolean> = _lockIndonesian.asStateFlow()

    private val _originalTitles = MutableStateFlow(true)

    /** Pakai judul asli pengunggah di hasil pencarian (default nyala). Dicerminkan ke [SearchLang.originalTitles]. */
    val originalTitles: StateFlow<Boolean> = _originalTitles.asStateFlow()

    private val _quality = MutableStateFlow(VideoQuality.AUTO) // [QUALITY]

    /** Batas resolusi YouTube. Dicerminkan ke [StreamQuality.current]; berlaku pada video berikutnya. */
    val quality: StateFlow<VideoQuality> = _quality.asStateFlow()

    fun setQuality(v: VideoQuality) {
        _quality.value = v
        StreamQuality.current = v
        sp?.edit()?.putString(KEY_QUALITY, v.name)?.apply()
        StreamQuality.notifyChosen(v) // [QUALITY-SYNC3]
    }

    private val _audioLang = MutableStateFlow("") // [AUDIO-LANG]

    /** Bahasa audio YouTube pilihan ("" = ikuti trek asli). Dicerminkan ke [StreamAudioLang.preferred]; berlaku pada video berikutnya. */
    val audioLang: StateFlow<String> = _audioLang.asStateFlow()

    fun setAudioLang(code: String) {
        val v = StreamAudioLang.normalize(code)
        _audioLang.value = v
        StreamAudioLang.preferred = v
        sp?.edit()?.putString(KEY_AUDIO_LANG, v)?.apply()
    }

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = p
        _ytdlpEnabled.value = p.getBoolean(KEY_YTDLP, false)
        _allowLan.value = p.getBoolean(KEY_LAN, false)
        NetworkPolicy.allowLan = _allowLan.value
        _blockPlaylistStreams.value = p.getBoolean(KEY_BLOCK_PL, false)
        NetworkPolicy.blockPlaylistStreams = _blockPlaylistStreams.value
        _lockIndonesian.value = p.getBoolean(KEY_LOCK_ID, true)
        SearchLang.enabled = _lockIndonesian.value
        _originalTitles.value = p.getBoolean(KEY_ORIG_TITLE, true)
        SearchLang.originalTitles = _originalTitles.value
        // [DEFAULT-AUTO] sekali: kunci AUDIO lama (workaround bug pencarian YouTube) dikembalikan ke AUTO
        if (!p.getBoolean("quality_auto_migrated_v1", false)) {
            val e = p.edit()
            if (p.getString(KEY_QUALITY, null) == VideoQuality.AUDIO.name)
                e.putString(KEY_QUALITY, VideoQuality.AUTO.name)
            e.putBoolean("quality_auto_migrated_v1", true).apply()
        }
        val q = VideoQuality.fromName(p.getString(KEY_QUALITY, null)) // [QUALITY]
        _quality.value = q
        StreamQuality.current = q
        StreamQuality.onChange = { setQuality(it) } // [QUALITY-LOCK]
        // [AUDIO-LANG-ID] belum pernah dipilih -> Indonesia (tidak lagi ikut bahasa sistem); pilihan eksplisit (termasuk Asli = kosong) menang
        val al = if (p.contains(KEY_AUDIO_LANG)) StreamAudioLang.normalize(p.getString(KEY_AUDIO_LANG, ""))
            else "id"
        _audioLang.value = al
        StreamAudioLang.preferred = al
        StreamAudioLang.onChange = { setAudioLang(it) }
    }

    fun enableLan(v: Boolean) {
        _allowLan.value = v
        NetworkPolicy.allowLan = v
        sp?.edit()?.putBoolean(KEY_LAN, v)?.apply()
    }

    fun setBlockPlaylistStreams(v: Boolean) {
        _blockPlaylistStreams.value = v
        NetworkPolicy.blockPlaylistStreams = v
        sp?.edit()?.putBoolean(KEY_BLOCK_PL, v)?.apply()
    }

    fun enableYtdlp(v: Boolean) {
        _ytdlpEnabled.value = v
        sp?.edit()?.putBoolean(KEY_YTDLP, v)?.apply()
    }

    fun setLockIndonesian(v: Boolean) {
        _lockIndonesian.value = v
        SearchLang.enabled = v
        sp?.edit()?.putBoolean(KEY_LOCK_ID, v)?.apply()
    }

    fun setOriginalTitles(v: Boolean) {
        _originalTitles.value = v
        SearchLang.originalTitles = v
        sp?.edit()?.putBoolean(KEY_ORIG_TITLE, v)?.apply()
    }
}

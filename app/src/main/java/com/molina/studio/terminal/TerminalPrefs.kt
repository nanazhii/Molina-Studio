package com.molina.studio.terminal

import com.molina.studio.ui.MolinaTheme
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Tema warna prompt. SGR dipakai bashrc; Color dipakai pratinjau di Settings. */
enum class PromptTheme(
    val id: String,
    val label: String,
    val homeSgr: String,
    val otherSgr: String,
    val homeColor: Color,
    val otherColor: Color,
    val userSgr: String = "",   // warna "user" (kosong = format prompt lama)
    val branchSgr: String = ""  // warna cabang git (kosong = tanpa cabang)
) {
    Noir(
        "molina_noir", "Molina Noir",
        "1;${MolinaTheme.BRAND_SGR}", "1;38;2;255;138;128",
        MolinaTheme.Brand, MolinaTheme.Bad,
        userSgr = "1;38;2;237;237;237", branchSgr = "38;2;179;136;255"
    ),
    CinemaBrand(
        "cinema_gold", "Cinema Emerald",
        "1;${MolinaTheme.BRAND_SGR}", "1;38;2;196;69;54",
        MolinaTheme.Brand, Color(0xFFC44536)
    ),
    NeonGreen(
        "neon_green", "Neon Green",
        "1;38;2;57;255;20", "1;38;2;255;49;99",
        Color(0xFF39FF14), Color(0xFFFF3163)
    ),
    Classic(
        "classic", "Molina Classic",
        "1;32", "1;31",
        MolinaTheme.Good, MolinaTheme.Bad
    );

    companion object {
        fun fromId(id: String?): PromptTheme = entries.firstOrNull { it.id == id } ?: Noir
    }
}

/** Snapshot immutable seluruh preferensi terminal (aman dibaca dari thread mana pun). */
data class TerminalSettings(
    val fontSp: Int = TerminalPrefs.DEFAULT_FONT_SP,
    val promptTheme: PromptTheme = PromptTheme.Noir,
    val look: TerminalLook = TerminalLook.NoirBrand,
    val banner: BannerStyle = BannerStyle.Off,
    val logLevel: Int = TerminalPrefs.LOG_NORMAL,
    val keyLogging: Boolean = false,
    val pluginErrorNotifications: Boolean = true,
    val crashNotifications: Boolean = true,
    val softKeyboard: Boolean = true,
    val softKeyboardOnlyIfNoHardware: Boolean = false,
    val marginAdjustment: Boolean = true,
    val logcatReporter: Boolean = false,
    val fullStorageAccess: Boolean = false,
    val cliClipboard: Boolean = false,
    val redactTranscript: Boolean = false,
    val compactMonitor: Boolean = false, // [MC-FIELD]
    val pasteShape: Boolean = true, // [PS-FIELD]
    val nanoKeysOpen: Boolean = false // [NK-FIELD]
)

/**
 * Penyimpanan DataStore. Nama "molina_terminal" sama dengan file SharedPreferences lama,
 * sehingga [SharedPreferencesMigration] memindahkan nilai pengguna secara otomatis (sekali).
 */
private val Context.terminalStore: DataStore<Preferences> by preferencesDataStore(
    name = "molina_terminal",
    produceMigrations = { ctx -> listOf(SharedPreferencesMigration(ctx, "molina_terminal")) }
)

private object PrefKeys {
    val FONT_SP = intPreferencesKey("font_sp")
    val PROMPT_THEME = stringPreferencesKey("prompt_theme_v2")
    val TERMINAL_LOOK = stringPreferencesKey("terminal_look")
    val BANNER_STYLE = stringPreferencesKey("banner_style")
    val LOG_LEVEL = intPreferencesKey("log_level")
    val KEY_LOGGING = booleanPreferencesKey("key_logging")
    val PLUGIN_ERRORS = booleanPreferencesKey("plugin_error_notifications")
    val CRASH_NOTIF = booleanPreferencesKey("crash_notifications")
    val SOFT_KEYBOARD = booleanPreferencesKey("soft_keyboard")
    val SOFT_KEYBOARD_NO_HW = booleanPreferencesKey("soft_keyboard_only_if_no_hw")
    val MARGIN_ADJ = booleanPreferencesKey("margin_adjustment")
    val LOGCAT = booleanPreferencesKey("logcat_reporter")
    val FULL_STORAGE = booleanPreferencesKey("full_storage_access")
    val CLI_CLIPBOARD = booleanPreferencesKey("cli_clipboard")
    val REDACT_TRANSCRIPT = booleanPreferencesKey("redact_transcript")
    val COMPACT_MONITOR = booleanPreferencesKey("compact_monitor") // [MC-KEY]
    val PASTE_SHAPE = booleanPreferencesKey("paste_shape") // [PS-KEY]
    val NANO_KEYS_OPEN = booleanPreferencesKey("nano_keys_open") // [NK-KEY]
}

private fun Preferences.toSettings(): TerminalSettings {
    val d = TerminalSettings()
    return TerminalSettings(
        fontSp = (this[PrefKeys.FONT_SP] ?: d.fontSp)
            .coerceIn(TerminalPrefs.MIN_FONT_SP, TerminalPrefs.MAX_FONT_SP),
        promptTheme = PromptTheme.fromId(this[PrefKeys.PROMPT_THEME]),
        look = TerminalLook.fromId(this[PrefKeys.TERMINAL_LOOK]),
        banner = BannerStyle.fromId(this[PrefKeys.BANNER_STYLE]),
        logLevel = (this[PrefKeys.LOG_LEVEL] ?: d.logLevel)
            .coerceIn(TerminalPrefs.LOG_OFF, TerminalPrefs.LOG_VERBOSE),
        keyLogging = this[PrefKeys.KEY_LOGGING] ?: d.keyLogging,
        pluginErrorNotifications = this[PrefKeys.PLUGIN_ERRORS] ?: d.pluginErrorNotifications,
        crashNotifications = this[PrefKeys.CRASH_NOTIF] ?: d.crashNotifications,
        softKeyboard = this[PrefKeys.SOFT_KEYBOARD] ?: d.softKeyboard,
        softKeyboardOnlyIfNoHardware = this[PrefKeys.SOFT_KEYBOARD_NO_HW] ?: d.softKeyboardOnlyIfNoHardware,
        marginAdjustment = this[PrefKeys.MARGIN_ADJ] ?: d.marginAdjustment,
        logcatReporter = this[PrefKeys.LOGCAT] ?: d.logcatReporter,
        fullStorageAccess = this[PrefKeys.FULL_STORAGE] ?: d.fullStorageAccess,
        cliClipboard = this[PrefKeys.CLI_CLIPBOARD] ?: d.cliClipboard,
        redactTranscript = this[PrefKeys.REDACT_TRANSCRIPT] ?: d.redactTranscript,
        compactMonitor = this[PrefKeys.COMPACT_MONITOR] ?: d.compactMonitor, // [MC-READ]
        pasteShape = this[PrefKeys.PASTE_SHAPE] ?: d.pasteShape, // [PS-READ]
        nanoKeysOpen = this[PrefKeys.NANO_KEYS_OPEN] ?: d.nanoKeysOpen // [NK-READ]
    )
}

private fun MutablePreferences.write(s: TerminalSettings) {
    this[PrefKeys.FONT_SP] = s.fontSp
    this[PrefKeys.PROMPT_THEME] = s.promptTheme.id
    this[PrefKeys.TERMINAL_LOOK] = s.look.id
    this[PrefKeys.BANNER_STYLE] = s.banner.id
    this[PrefKeys.LOG_LEVEL] = s.logLevel
    this[PrefKeys.KEY_LOGGING] = s.keyLogging
    this[PrefKeys.PLUGIN_ERRORS] = s.pluginErrorNotifications
    this[PrefKeys.CRASH_NOTIF] = s.crashNotifications
    this[PrefKeys.SOFT_KEYBOARD] = s.softKeyboard
    this[PrefKeys.SOFT_KEYBOARD_NO_HW] = s.softKeyboardOnlyIfNoHardware
    this[PrefKeys.MARGIN_ADJ] = s.marginAdjustment
    this[PrefKeys.LOGCAT] = s.logcatReporter
    this[PrefKeys.FULL_STORAGE] = s.fullStorageAccess
    this[PrefKeys.CLI_CLIPBOARD] = s.cliClipboard
    this[PrefKeys.REDACT_TRANSCRIPT] = s.redactTranscript
    this[PrefKeys.COMPACT_MONITOR] = s.compactMonitor // [MC-WRITE]
    this[PrefKeys.PASTE_SHAPE] = s.pasteShape // [PS-WRITE]
    this[PrefKeys.NANO_KEYS_OPEN] = s.nanoKeysOpen // [NK-WRITE]
}

/**
 * Preferensi terminal Molina.
 *
 * - Sumber kebenaran: [settings] (StateFlow) untuk collector non-UI; properti di bawah
 *   (`fontSp`, `logLevel`, ...) membaca mirror state Compose sehingga UI tetap recompose
 *   tanpa perubahan di call site.
 * - Setter hanya memperbarui memori (tanpa I/O di thread pemanggil). Persistensi ke
 *   DataStore berjalan di Dispatchers.IO dan otomatis digabung (conflated), sehingga
 *   drag slider font tidak menghasilkan tulis berurutan yang saling menimpa.
 */
object TerminalPrefs {
    const val MIN_FONT_SP = 8
    const val MAX_FONT_SP = 14
    const val DEFAULT_FONT_SP = 9

    const val LOG_OFF = 0
    const val LOG_NORMAL = 1
    const val LOG_DEBUG = 2
    const val LOG_VERBOSE = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flow = MutableStateFlow(TerminalSettings())
    private var ui by mutableStateOf(TerminalSettings())
    private var store: DataStore<Preferences>? = null

    /** Aliran reaktif seluruh preferensi (untuk service, Molina Player/mpv, dsb.). */
    val settings: StateFlow<TerminalSettings> = flow.asStateFlow()

    val fontSp: Int get() = ui.fontSp
    val promptTheme: PromptTheme get() = ui.promptTheme
    val look: TerminalLook get() = ui.look
    val bannerStyle: BannerStyle get() = ui.banner
    val logLevel: Int get() = ui.logLevel
    val keyLogging: Boolean get() = ui.keyLogging
    val pluginErrorNotifications: Boolean get() = ui.pluginErrorNotifications
    val crashNotifications: Boolean get() = ui.crashNotifications
    val softKeyboard: Boolean get() = ui.softKeyboard
    val softKeyboardOnlyIfNoHardware: Boolean get() = ui.softKeyboardOnlyIfNoHardware
    val marginAdjustment: Boolean get() = ui.marginAdjustment
    val logcatReporter: Boolean get() = ui.logcatReporter
    val fullStorageAccess: Boolean get() = ui.fullStorageAccess
    val cliClipboard: Boolean get() = ui.cliClipboard
    val redactTranscript: Boolean get() = ui.redactTranscript
    val compactMonitor: Boolean get() = ui.compactMonitor // [MC-GET]
    val pasteShape: Boolean get() = ui.pasteShape // [PS-GET]
    val nanoKeysOpen: Boolean get() = ui.nanoKeysOpen // [NK-GET]

    /**
     * Aman dipanggil berulang; hanya memuat sekali. Pemuatan awal sengaja sinkron
     * (satu kali, berkas kecil) karena pemanggil langsung membaca nilai setelahnya.
     */
    @Synchronized
    fun init(ctx: Context) {
        if (store != null) return
        val ds = ctx.applicationContext.terminalStore
        val loaded = runCatching { runBlocking(Dispatchers.IO) { ds.data.first().toSettings() } }
            .getOrDefault(TerminalSettings())
        store = ds
        publish(loaded)
        scope.launch {
            var saved = loaded
            flow.collect { s ->
                if (s != saved) {
                    runCatching { ds.edit { it.write(s) } }
                    saved = s
                }
            }
        }
    }

    private fun publish(s: TerminalSettings) {
        flow.value = s
        ui = s
    }

    @Synchronized
    private fun update(transform: TerminalSettings.() -> TerminalSettings) {
        publish(flow.value.transform())
    }

    fun setFontSp(v: Int) = update { copy(fontSp = v.coerceIn(MIN_FONT_SP, MAX_FONT_SP)) }

    /** Simpan tema, lalu tulis ulang prompt.env agar prompt berikutnya langsung berwarna baru. */
    fun setPromptTheme(ctx: Context, t: PromptTheme) {
        update { copy(promptTheme = t) }
        val app = ctx.applicationContext
        Thread {
            runCatching {
                val dir: File = RootfsManager.rootfsDir(app)
                if (dir.isDirectory) MolinaShell.writePromptEnv(dir, t)
            }
        }.apply { isDaemon = true; name = "molina-prompt-theme" }.start()
    }

    /** Simpan skema warna terminal, lalu terapkan langsung ke sesi yang terbuka. */
    fun setLook(l: TerminalLook) {
        update { copy(look = l) }
        TerminalLooks.applyLive(l)
    }

    /** Gaya banner berlaku di sesi baru dan tombol CLEAR. */
    fun setBannerStyle(b: BannerStyle) = update { copy(banner = b) }

    fun setLogLevel(v: Int) = update { copy(logLevel = v.coerceIn(LOG_OFF, LOG_VERBOSE)) }
    fun setKeyLogging(v: Boolean) = update { copy(keyLogging = v) }
    fun setPluginErrorNotifications(v: Boolean) = update { copy(pluginErrorNotifications = v) }
    fun setCrashNotifications(v: Boolean) = update { copy(crashNotifications = v) }
    fun setSoftKeyboard(v: Boolean) = update { copy(softKeyboard = v) }
    fun setSoftKeyboardOnlyIfNoHardware(v: Boolean) = update { copy(softKeyboardOnlyIfNoHardware = v) }
    fun setMarginAdjustment(v: Boolean) = update { copy(marginAdjustment = v) }
    fun setLogcatReporter(v: Boolean) = update { copy(logcatReporter = v) }
    fun setFullStorageAccess(v: Boolean) = update { copy(fullStorageAccess = v) }
    fun setCliClipboard(v: Boolean) = update { copy(cliClipboard = v) }
    fun setRedactTranscript(v: Boolean) = update { copy(redactTranscript = v) }
    fun setCompactMonitor(v: Boolean) = update { copy(compactMonitor = v) } // [MC-SET]
    fun setPasteShape(v: Boolean) = update { copy(pasteShape = v) } // [PS-SET]
    fun setNanoKeysOpen(v: Boolean) = update { copy(nanoKeysOpen = v) } // [NK-SET]

    /** Keyboard layar boleh tampil? (Soft Keyboard Enabled + Only If No Hardware). */
    fun softKeyboardAllowed(ctx: Context): Boolean {
        if (!softKeyboard) return false
        if (softKeyboardOnlyIfNoHardware) {
            val hasHw = ctx.resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS
            if (hasHw) return false
        }
        return true
    }
}

/** Logger yang menghormati Log Level di Settings (Off / Normal / Debug / Verbose), seperti Termux. */
object MolinaLog {
    /** Tag logcat seragam untuk sisi terminal; tag asal dipertahankan sebagai awalan pesan. */
    const val LOGCAT_TAG = "Molina/Term"

    private fun on(min: Int) = TerminalPrefs.logLevel >= min
    private fun tagged(tag: String, msg: String) =
        if (tag.isEmpty() || tag == LOGCAT_TAG) msg else "[$tag] $msg"

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (!on(TerminalPrefs.LOG_NORMAL)) return
        if (tr != null) Log.e(LOGCAT_TAG, tagged(tag, msg), tr) else Log.e(LOGCAT_TAG, tagged(tag, msg))
    }
    fun w(tag: String, msg: String) { if (on(TerminalPrefs.LOG_NORMAL)) Log.w(LOGCAT_TAG, tagged(tag, msg)) }
    fun i(tag: String, msg: String) { if (on(TerminalPrefs.LOG_NORMAL)) Log.i(LOGCAT_TAG, tagged(tag, msg)) }
    fun d(tag: String, msg: String) { if (on(TerminalPrefs.LOG_DEBUG)) Log.d(LOGCAT_TAG, tagged(tag, msg)) }
    fun v(tag: String, msg: String) { if (on(TerminalPrefs.LOG_VERBOSE)) Log.v(LOGCAT_TAG, tagged(tag, msg)) }

    /** Log tombol; hanya bila "Terminal View Key Logging" aktif (dan Log Level bukan Off). */
    fun key(tag: String, msg: String) {
        if (TerminalPrefs.keyLogging && on(TerminalPrefs.LOG_NORMAL)) Log.d(LOGCAT_TAG, tagged(tag, msg))
    }
}

package com.molina.studio.settings

import android.content.Context
import com.molina.cinema.VideoBookmarks
import com.molina.core.common.MolinaDir
import com.molina.studio.AppPaths
import com.molina.studio.code.ScriptStore
import com.molina.studio.terminal.PromptTheme
import com.molina.studio.terminal.TerminalPrefs
import com.molina.studio.terminal.TerminalSnippets
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cadangan gabungan di /sdcard/Molina/Backup/molina-backup.json: penanda video, snippet Terminal, skrip Script Execution, setelan
 * (SharedPreferences yang diizinkan + setelan Terminal). Riwayat, cache, dan data alat tidak ikut.
 * Pulihkan: penanda, snippet, dan skrip digabung (tidak menimpa), setelan menimpa nilai saat ini.
 */
internal object BackupManager {
    const val FILE_NAME = "molina-backup.json"
    private const val VERSION = 1

    private class Spec(val file: String, val only: Set<String>? = null, val skip: Set<String> = emptySet()) {
        fun allowed(key: String) = (only == null || key in only) && key !in skip
    }

    private fun specs(ctx: Context) = listOf(
        Spec("molina_ui", only = setOf("rgb_anim", "term_keep_on")),
        Spec("molina_code"),
        Spec("molina_shield"),
        Spec("molina_privacy"),
        Spec("molina_stream"),
        Spec("molina_auto", only = setOf("auto_update", "auto_backup", "dl_to_cinema")),
        Spec("molina_download"),
        Spec("molina_explorer", skip = setOf("recent_v1")),
        Spec(ctx.packageName + "_preferences") // setelan pemutar mpv
    )

    private fun file(ctx: Context): File = File(AppPaths.get(ctx).dir(MolinaDir.Backup), FILE_NAME)

    fun hasBackup(ctx: Context): Boolean = file(ctx).isFile

    /** Waktu ubah berkas cadangan gabungan (ms); 0 = belum ada. */
    fun lastBackupMs(ctx: Context): Long = file(ctx).takeIf { it.isFile }?.lastModified() ?: 0L

    fun describe(ctx: Context): String {
        val f = file(ctx)
        if (!f.isFile) return "Belum ada cadangan gabungan."
        val t = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(f.lastModified()))
        return "Berkas: ${MolinaDir.Backup.folderName}/$FILE_NAME\nTerakhir: $t"
    }

    private fun dump(ctx: Context, s: Spec): JSONObject {
        val o = JSONObject()
        val all = ctx.applicationContext.getSharedPreferences(s.file, Context.MODE_PRIVATE).all
        for ((k, v) in all) {
            if (!s.allowed(k)) continue
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> e.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            o.put(k, e)
        }
        return o
    }

    private fun applyPrefs(ctx: Context, s: Spec, o: JSONObject): Int {
        val ed = ctx.applicationContext.getSharedPreferences(s.file, Context.MODE_PRIVATE).edit()
        var n = 0
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!s.allowed(k)) continue
            val e = o.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "s" -> ed.putString(k, e.getString("v"))
                "ss" -> {
                    val a = e.getJSONArray("v")
                    ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                }
                else -> continue
            }
            n++
        }
        ed.apply()
        return n
    }

    private fun terminalJson(ctx: Context): JSONObject {
        TerminalPrefs.init(ctx)
        val s = TerminalPrefs.settings.value
        return JSONObject()
            .put("font_sp", s.fontSp)
            .put("prompt_theme", s.promptTheme.id)
            .put("log_level", s.logLevel)
            .put("key_logging", s.keyLogging)
            .put("plugin_error_notifications", s.pluginErrorNotifications)
            .put("crash_notifications", s.crashNotifications)
            .put("soft_keyboard", s.softKeyboard)
            .put("soft_keyboard_only_if_no_hw", s.softKeyboardOnlyIfNoHardware)
            .put("margin_adjustment", s.marginAdjustment)
            .put("logcat_reporter", s.logcatReporter)
    }

    private fun applyTerminal(ctx: Context, o: JSONObject): Int {
        TerminalPrefs.init(ctx)
        var n = 0
        if (o.has("font_sp")) { TerminalPrefs.setFontSp(o.getInt("font_sp")); n++ }
        if (o.has("prompt_theme")) { TerminalPrefs.setPromptTheme(ctx, PromptTheme.fromId(o.getString("prompt_theme"))); n++ }
        if (o.has("log_level")) { TerminalPrefs.setLogLevel(o.getInt("log_level")); n++ }
        if (o.has("key_logging")) { TerminalPrefs.setKeyLogging(o.getBoolean("key_logging")); n++ }
        if (o.has("plugin_error_notifications")) { TerminalPrefs.setPluginErrorNotifications(o.getBoolean("plugin_error_notifications")); n++ }
        if (o.has("crash_notifications")) { TerminalPrefs.setCrashNotifications(o.getBoolean("crash_notifications")); n++ }
        if (o.has("soft_keyboard")) { TerminalPrefs.setSoftKeyboard(o.getBoolean("soft_keyboard")); n++ }
        if (o.has("soft_keyboard_only_if_no_hw")) { TerminalPrefs.setSoftKeyboardOnlyIfNoHardware(o.getBoolean("soft_keyboard_only_if_no_hw")); n++ }
        if (o.has("margin_adjustment")) { TerminalPrefs.setMarginAdjustment(o.getBoolean("margin_adjustment")); n++ }
        if (o.has("logcat_reporter")) { TerminalPrefs.setLogcatReporter(o.getBoolean("logcat_reporter")); n++ }
        return n
    }

    /** Cuplikan setelan saja (prefs yang diizinkan + setelan Terminal), tanpa penanda/snippet/skrip. Operasi disk. */
    internal fun settingsSnapshot(ctx: Context): JSONObject {
        val prefs = JSONObject()
        for (s in specs(ctx)) prefs.put(s.file, dump(ctx, s))
        return JSONObject().put("prefs", prefs).put("terminal", terminalJson(ctx))
    }

    /** Terapkan cuplikan setelan (menimpa nilai saat ini). Mengembalikan jumlah setelan yang diterapkan. */
    internal fun applySettingsSnapshot(ctx: Context, root: JSONObject): Int {
        var n = 0
        root.optJSONObject("prefs")?.let { prefs ->
            for (s in specs(ctx)) {
                val d = prefs.optJSONObject(s.file) ?: continue
                n += applyPrefs(ctx, s, d)
            }
        }
        root.optJSONObject("terminal")?.let { n += applyTerminal(ctx, it) }
        return n
    }

    /** Tulis cadangan gabungan. Operasi disk: panggil di luar main thread. Mengembalikan pesan untuk toast. */
    fun backupAll(ctx: Context): String {
        return try {
            val bm = VideoBookmarks.snapshot(ctx)
            val sn = TerminalSnippets.snapshot(ctx)
            val sc = ScriptStore.snapshot()
            val prefs = JSONObject()
            var settings = 0
            for (s in specs(ctx)) {
                val d = dump(ctx, s)
                settings += d.length()
                prefs.put(s.file, d)
            }
            val term = terminalJson(ctx)
            settings += term.length()
            val root = JSONObject()
                .put("app", "molina")
                .put("kind", "backup")
                .put("version", VERSION)
                .put("created", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
                .put("bookmarks", bm)
                .put("snippets", sn)
                .put("scripts", sc)
                .put("prefs", prefs)
                .put("terminal", term)
            val f = file(ctx)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            val text = root.toString(2) + "\n"
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
            "Dicadangkan: ${bm.length()} video, ${sn.length()} snippet, ${sc.length()} skrip, $settings setelan"
        } catch (e: Exception) {
            "Gagal mencadangkan (cek izin penyimpanan)"
        }
    }

    /** Pulihkan dari cadangan gabungan. Operasi disk: panggil di luar main thread. */
    fun restoreAll(ctx: Context): String {
        val f = file(ctx)
        if (!f.isFile) return "Cadangan gabungan belum ada"
        return try {
            val root = JSONObject(f.readText())
            if (root.optString("kind") != "backup") return "Berkas cadangan tidak valid"
            val marks = VideoBookmarks.mergeFrom(ctx, root.optJSONObject("bookmarks") ?: JSONObject())
            val snips = TerminalSnippets.mergeFrom(ctx, root.optJSONArray("snippets") ?: JSONArray())
            val scripts = ScriptStore.mergeFrom(root.optJSONArray("scripts") ?: JSONArray())
            var settings = 0
            val prefs = root.optJSONObject("prefs")
            if (prefs != null) {
                for (s in specs(ctx)) {
                    val d = prefs.optJSONObject(s.file) ?: continue
                    settings += applyPrefs(ctx, s, d)
                }
            }
            root.optJSONObject("terminal")?.let { settings += applyTerminal(ctx, it) }
            "Dipulihkan: +$marks penanda, +$snips snippet, +$scripts skrip, $settings setelan. Mulai ulang aplikasi agar semua setelan berlaku."
        } catch (e: Exception) {
            "Gagal memulihkan: berkas cadangan tidak valid"
        }
    }
}

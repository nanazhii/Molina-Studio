package com.molina.cinema

import android.app.Activity
import android.content.SharedPreferences
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [SUBTITLE-CONVERT] Menu "Konversi subtitle": SRT <-> ASS untuk subtitle aktif, hasil disimpan di
 * /storage/emulated/0/Molina/Subtitles/ lalu langsung dimuat ke pemutar (sub-add).
 *
 * Toggle "Subtitle tertanam: ekstrak dengan FFmpeg" (default mati) mengatur apakah trek yang tertanam di video
 * boleh diekstrak lewat [SubtitleExtractors.provider]. Selama provider belum terdaftar, hasilnya pesan penjelasan.
 */
internal object SubConvertUi {
    const val PREF_EMBEDDED = "sub_convert_embedded"
    const val OUT_DIR = "/storage/emulated/0/Molina/Subtitles"

    private class Outcome(val savedPath: String?, val error: String?)

    private fun prefs(a: Activity): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(a.applicationContext)

    private fun toast(a: Activity, text: String) = Toast.makeText(a, text, Toast.LENGTH_LONG).show()
    private fun toast(a: Activity, @StringRes id: Int) = toast(a, a.getString(id))

    /** Trek subtitle yang sedang aktif (sid) dari track-list mpv, atau null bila subtitle mati. */
    private fun activeTrack(): SubTrackInfo? {
        val sid = MPVLib.getPropertyInt("sid") ?: return null
        val count = MPVLib.getPropertyInt("track-list/count") ?: return null
        for (i in 0 until count) {
            if (MPVLib.getPropertyString("track-list/$i/type") != "sub") continue
            if (MPVLib.getPropertyInt("track-list/$i/id") != sid) continue
            return SubTrackInfo(
                id = sid,
                external = MPVLib.getPropertyBoolean("track-list/$i/external") == true,
                externalFilename = MPVLib.getPropertyString("track-list/$i/external-filename"),
                codec = MPVLib.getPropertyString("track-list/$i/codec"),
                ffIndex = MPVLib.getPropertyInt("track-list/$i/ff-index"),
                lang = MPVLib.getPropertyString("track-list/$i/lang"),
            )
        }
        return null
    }

    fun show(activity: Activity, scope: CoroutineScope, restore: StateRestoreCallback) {
        val sp = prefs(activity)
        val embedded = sp.getBoolean(PREF_EMBEDDED, false)
        val items = listOf(
            activity.getString(R.string.sub_convert_srt),
            activity.getString(R.string.sub_convert_ass),
            activity.getString(R.string.sub_convert_ass_style),
            activity.getString(if (embedded) R.string.sub_convert_embedded_on else R.string.sub_convert_embedded_off),
        )
        AlertDialog.Builder(activity, R.style.AppTheme_PlayerDialog).apply {
            setTitle(R.string.sub_convert_title)
            setItems(items.toTypedArray()) { _, i ->
                when (i) {
                    0 -> convert(activity, scope, SubFormat.SRT, AssStyle())
                    1 -> convert(activity, scope, SubFormat.ASS, AssStyle())
                    2 -> convert(activity, scope, SubFormat.ASS, currentStyle(sp))
                    else -> {
                        sp.edit().putBoolean(PREF_EMBEDDED, !embedded).apply()
                        toast(activity, if (!embedded) R.string.sub_convert_embedded_now_on else R.string.sub_convert_embedded_now_off)
                    }
                }
            }
            setOnDismissListener { restore() }
        }.create().show()
    }

    private fun currentStyle(sp: SharedPreferences): AssStyle {
        val preset = SubStyles.byId(sp.getString(SubStyles.PREF_PRESET, null)) ?: SubStyles.byId(SubStyles.DEFAULT_ID)!!
        return AssStyle.fromPreset(preset)
    }

    private fun convert(activity: Activity, scope: CoroutineScope, target: SubFormat, style: AssStyle) {
        val track = activeTrack() ?: return toast(activity, R.string.sub_convert_none)
        val rawMedia = MPVLib.getPropertyString("path")
        val media = SubConvertPlanner.localPath(rawMedia)
        when (val plan = SubConvertPlanner.plan(track, prefs(activity).getBoolean(PREF_EMBEDDED, false))) {
            is ConvertPlan.FromFile -> scope.launch {
                val r = withContext(Dispatchers.IO) { fromFile(activity, plan.path, target, style, media, track.lang) }
                finish(activity, r)
            }
            is ConvertPlan.NeedsExtraction -> scope.launch {
                finish(activity, extract(activity, plan.streamIndex, target, media, track.lang))
            }
            ConvertPlan.EmbeddedDisabled -> toast(activity, R.string.sub_convert_embedded_hint)
            ConvertPlan.Bitmap -> toast(activity, R.string.sub_convert_bitmap)
            ConvertPlan.Unsupported -> toast(activity, R.string.sub_convert_unsupported)
        }
    }

    private fun finish(activity: Activity, r: Outcome) {
        if (r.savedPath == null) return toast(activity, r.error ?: activity.getString(R.string.sub_convert_unsupported))
        MPVLib.command(arrayOf("sub-add", r.savedPath, "select")) // muat hasil langsung ke pemutar
        toast(activity, activity.getString(R.string.sub_convert_done, r.savedPath))
    }

    private fun fail(a: Activity, @StringRes id: Int) = Outcome(null, a.getString(id))

    private fun fromFile(a: Activity, src: String, target: SubFormat, style: AssStyle, media: String?, lang: String?): Outcome {
        val raw = LyricsFiles.readText(File(src)) ?: return fail(a, R.string.sub_convert_read_failed)
        if (SubtitleConvert.detect(raw) == target) return fail(a, R.string.sub_convert_same)
        val converted = SubtitleConvert.convert(raw, target, style) ?: return fail(a, R.string.sub_convert_unknown_format)
        val out = SubConvertPlanner.outputPath(OUT_DIR, media, lang, target) { File(it).exists() }
        return try {
            val dir = File(OUT_DIR)
            if (!dir.isDirectory && !dir.mkdirs()) return fail(a, R.string.sub_convert_write_failed)
            File(out).writeText(converted, Charsets.UTF_8)
            Outcome(out, null)
        } catch (e: Exception) {
            fail(a, R.string.sub_convert_write_failed)
        }
    }

    private suspend fun extract(a: Activity, streamIndex: Int, target: SubFormat, media: String?, lang: String?): Outcome {
        val provider = SubtitleExtractors.provider ?: return fail(a, R.string.sub_convert_extract_unavailable)
        if (media == null) return fail(a, R.string.sub_convert_unsupported)
        provider.unavailableReason(media)?.let { return Outcome(null, it) }
        val out = SubConvertPlanner.outputPath(OUT_DIR, media, lang, target) { File(it).exists() }
        if (!File(OUT_DIR).let { it.isDirectory || it.mkdirs() }) return fail(a, R.string.sub_convert_write_failed)
        val err = withContext(Dispatchers.IO) { provider.extract(media, streamIndex, target, out) }
        return if (err == null && File(out).isFile) Outcome(out, null) else Outcome(null, err ?: a.getString(R.string.sub_convert_write_failed))
    }
}

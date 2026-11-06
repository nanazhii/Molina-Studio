package com.molina.cinema

import android.app.Activity
import android.content.SharedPreferences
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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

    private class Outcome(val savedPath: String?, val error: String?, val handled: Boolean = false) // [SUBTITLE-EXTRACT] handled = pesan sudah ditampilkan

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
            activity.getString(R.string.sub_edit_menu), // [SUBTITLE-EDIT]
        )
        var handoff = false // [SUBTITLE-EDIT] editor mengambil alih restore() dan memanggilnya saat ditutup
        AlertDialog.Builder(activity, R.style.AppTheme_PlayerDialog).apply {
            setTitle(R.string.sub_convert_title)
            setItems(items.toTypedArray()) { _, i ->
                when (i) {
                    0 -> convert(activity, scope, SubFormat.SRT, AssStyle())
                    1 -> convert(activity, scope, SubFormat.ASS, AssStyle())
                    2 -> convert(activity, scope, SubFormat.ASS, currentStyle(sp))
                    3 -> {
                        sp.edit().putBoolean(PREF_EMBEDDED, !embedded).apply()
                        toast(activity, if (!embedded) R.string.sub_convert_embedded_now_on else R.string.sub_convert_embedded_now_off)
                    }
                    else -> {
                        handoff = true
                        edit(activity, scope, restore)
                    }
                }
            }
            setOnDismissListener { if (!handoff) restore() }
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
        if (r.handled) return
        if (r.savedPath == null) return toast(activity, r.error ?: activity.getString(R.string.sub_convert_unsupported))
        MPVLib.command(arrayOf("sub-add", r.savedPath, "select")) // muat hasil langsung ke pemutar
        toast(activity, activity.getString(R.string.sub_convert_done, r.savedPath))
    }

    private fun fail(a: Activity, @StringRes id: Int) = Outcome(null, a.getString(id))

    /**
     * [SUBTITLE-EDIT] Editor subtitle untuk subtitle aktif. Berkas eksternal dibaca langsung; subtitle tertanam diekstrak
     * dulu ke SRT (dengan dialog Batal). Aksi gagal selalu memanggil [restore]; bila editor terbuka, editor yang memanggilnya.
     */
    private fun edit(activity: Activity, scope: CoroutineScope, restore: StateRestoreCallback) {
        val track = activeTrack()
        if (track == null) { toast(activity, R.string.sub_convert_none); restore(); return }
        val media = SubConvertPlanner.localPath(MPVLib.getPropertyString("path"))
        when (val plan = SubConvertPlanner.plan(track, prefs(activity).getBoolean(PREF_EMBEDDED, false))) {
            is ConvertPlan.FromFile -> scope.launch {
                val raw = withContext(Dispatchers.IO) { LyricsFiles.readText(File(plan.path)) }
                openEditor(activity, raw, null, media, track.lang, restore)
            }
            is ConvertPlan.NeedsExtraction -> scope.launch {
                val o = try {
                    extract(activity, plan.streamIndex, SubFormat.SRT, media, track.lang)
                } catch (e: CancellationException) {
                    restore(); throw e
                }
                val path = o.savedPath
                if (path == null) {
                    if (!o.handled) toast(activity, o.error ?: activity.getString(R.string.sub_convert_unsupported))
                    restore(); return@launch
                }
                val raw = withContext(Dispatchers.IO) { LyricsFiles.readText(File(path)) }
                openEditor(activity, raw, path, media, track.lang, restore)
            }
            ConvertPlan.EmbeddedDisabled -> { toast(activity, R.string.sub_convert_embedded_hint); restore() }
            ConvertPlan.Bitmap -> { toast(activity, R.string.sub_convert_bitmap); restore() }
            ConvertPlan.Unsupported -> { toast(activity, R.string.sub_convert_unsupported); restore() }
        }
    }

    /**
     * [SUBTITLE-EDIT] [ownedPath] = berkas hasil ekstraksi kita sendiri (boleh ditimpa saat simpan berikutnya).
     * Berkas sumber milik pengguna tidak pernah ditimpa: simpan pertama membuat berkas baru di OUT_DIR, simpan
     * berikutnya menimpa berkas baru itu lalu memuat ulang trek (sub-reload).
     */
    private fun openEditor(activity: Activity, raw: String?, ownedPath: String?, media: String?, lang: String?, restore: StateRestoreCallback) {
        val cues = raw?.let { SubtitleConvert.parseAny(it) }.orEmpty()
        if (raw == null || cues.isEmpty() || activity.isFinishing) {
            if (!activity.isFinishing) toast(activity, if (raw == null) R.string.sub_convert_read_failed else R.string.sub_convert_unknown_format)
            restore(); return
        }
        val sp = prefs(activity)
        var out: String? = ownedPath
        var loaded = false
        val saver: (List<SubCue>, SubFormat) -> String? = lambda@{ list, fmt ->
            val text = if (fmt == SubFormat.ASS) AssCodec.write(list, currentStyle(sp)) else SrtCodec.write(list)
            val path = out ?: SubConvertPlanner.outputPath(OUT_DIR, media, lang, fmt) { File(it).exists() }
            try {
                val dir = File(OUT_DIR)
                if (!dir.isDirectory && !dir.mkdirs()) return@lambda null
                File(path).writeText(text, Charsets.UTF_8)
            } catch (e: Exception) {
                return@lambda null
            }
            if (!loaded) {
                MPVLib.command(arrayOf("sub-add", path, "select"))
                loaded = true
            } else {
                MPVLib.getPropertyInt("sid")?.let { MPVLib.command(arrayOf("sub-reload", it.toString())) }
            }
            out = path
            path
        }
        SubEditorDialog(activity, SubtitleConvert.detect(raw), cues, saver, restore).show()
    }

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

    /** [SUBTITLE-EXTRACT] Alasan ekstraksi belum bisa: dialog dengan tombol "Buka Paket > Alat" bila ada aksinya, selain itu toast. */
    private fun showBlocked(a: Activity, block: ExtractBlock) {
        val open = block.openTools ?: return toast(a, block.message)
        AlertDialog.Builder(a, R.style.AppTheme_PlayerDialog)
            .setMessage(block.message)
            .setPositiveButton(R.string.sub_convert_open_tools) { _, _ -> open() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** [SUBTITLE-CANCEL] Dialog progres tak-tentu; tombol Batal membatalkan [job]. Tidak bisa ditutup dengan sentuhan di luar. */
    private fun showExtractProgress(a: Activity, job: Job?): AlertDialog {
        val pad = (20 * a.resources.displayMetrics.density).toInt()
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return AlertDialog.Builder(a, R.style.AppTheme_PlayerDialog)
            .setMessage(R.string.sub_convert_extracting)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(R.string.sub_convert_cancel) { _, _ -> job?.cancel() }
            .create()
            .also { it.show() }
    }

    private suspend fun extract(a: Activity, streamIndex: Int, target: SubFormat, media: String?, lang: String?): Outcome {
        val provider = SubtitleExtractors.provider ?: return fail(a, R.string.sub_convert_extract_unavailable)
        if (media == null) return fail(a, R.string.sub_convert_unsupported)
        provider.unavailableReason(media)?.let { block ->
            showBlocked(a, block) // [SUBTITLE-EXTRACT]
            return Outcome(null, null, handled = true)
        }
        val out = SubConvertPlanner.outputPath(OUT_DIR, media, lang, target) { File(it).exists() }
        if (!File(OUT_DIR).let { it.isDirectory || it.mkdirs() }) return fail(a, R.string.sub_convert_write_failed)
        // [SUBTITLE-CANCEL] Dialog progres dengan tombol Batal; membatalkan job ini -> provider mematikan pohon proses FFmpeg.
        val self = currentCoroutineContext()[Job]
        val dialog = showExtractProgress(a, self)
        val err = try {
            coroutineScope {
                val ticker = launch {
                    var sec = 0
                    while (true) {
                        delay(1_000)
                        sec++
                        dialog.setMessage(a.getString(R.string.sub_convert_extracting_elapsed, sec))
                    }
                }
                try {
                    withContext(Dispatchers.IO) { provider.extract(media, streamIndex, target, out) }
                } finally {
                    ticker.cancel()
                }
            }
        } catch (e: CancellationException) {
            toast(a, R.string.sub_convert_cancelled)
            throw e
        } finally {
            runCatching { dialog.dismiss() }
        }
        return if (err == null && File(out).isFile) Outcome(out, null) else Outcome(null, err ?: a.getString(R.string.sub_convert_write_failed))
    }
}

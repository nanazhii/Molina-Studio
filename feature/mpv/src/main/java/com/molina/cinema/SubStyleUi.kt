package com.molina.cinema

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager

/**
 * [SUBTITLE-STYLE] Dialog Gaya Subtitle dan Kecepatan Subtitle di menu lanjutan pemutar.
 * Semua efek lewat properti mpv (sub-color, sub-scale, sub-pos, sub-speed, ...), tanpa kode native.
 * Pilihan gaya disimpan di SharedPreferences bawaan dan diterapkan lagi saat mpv dibuat ([applySaved]).
 */
internal object SubStyleUi {
    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)

    @StringRes
    private fun presetLabel(id: String): Int = when (id) {
        "classic" -> R.string.sub_preset_classic
        "anime" -> R.string.sub_preset_anime
        "contrast" -> R.string.sub_preset_contrast
        else -> R.string.sub_preset_default
    }

    /** Panggil dari MPVView.initOptions (SEBELUM mpv init): gaya tersimpan diterapkan sebagai opsi. */
    fun applySaved(ctx: Context) {
        val sp = prefs(ctx)
        runCatching {
            SubStyles.byId(sp.getString(SubStyles.PREF_PRESET, null))
                ?.props?.forEach { (k, v) -> MPVLib.setOptionString(k, v) }
            if (sp.contains(SubStyles.PREF_SCALE))
                MPVLib.setOptionString("sub-scale", sp.getFloat(SubStyles.PREF_SCALE, 1f).toString())
            if (sp.contains(SubStyles.PREF_POS))
                MPVLib.setOptionString("sub-pos", sp.getInt(SubStyles.PREF_POS, 100).toString())
            if (sp.getBoolean(SubStyles.PREF_ASS_FORCE, false))
                MPVLib.setOptionString("sub-ass-override", "force")
        } // tipe tersimpan rusak (ClassCastException): lewati, pakai bawaan mpv
    }

    private fun applyPreset(ctx: Context, preset: SubStylePreset) {
        preset.props.forEach { (k, v) -> MPVLib.setPropertyString(k, v) }
        prefs(ctx).edit().putString(SubStyles.PREF_PRESET, preset.id).apply()
    }

    fun showStyleDialog(activity: Activity, restore: StateRestoreCallback) {
        val n = SubStyles.PRESETS.size
        val assForce = MPVLib.getPropertyString("sub-ass-override") == "force"
        val items = SubStyles.PRESETS.map { activity.getString(presetLabel(it.id)) } + listOf(
            activity.getString(R.string.sub_style_size),
            activity.getString(R.string.sub_style_position),
            activity.getString(if (assForce) R.string.sub_style_ass_on else R.string.sub_style_ass_off),
        )
        var chained = false // dialog anak aktif: jangan pulihkan pemutaran dulu
        AlertDialog.Builder(activity, R.style.AppTheme_PlayerDialog).apply {
            setTitle(R.string.sub_style_title)
            setItems(items.toTypedArray()) { _, i ->
                when (i) {
                    in 0 until n -> applyPreset(activity, SubStyles.PRESETS[i])
                    n -> {
                        chained = true
                        showSlider(
                            activity, R.string.sub_style_size,
                            SliderPickerDialog(0.4, 1.6, 10, R.string.format_one_decimal),
                            MPVLib.getPropertyDouble("sub-scale") ?: 1.0, restore,
                        ) { v ->
                            val scale = Math.round(v * 100) / 100.0
                            MPVLib.setPropertyDouble("sub-scale", scale)
                            prefs(activity).edit().putFloat(SubStyles.PREF_SCALE, scale.toFloat()).apply()
                        }
                    }
                    n + 1 -> {
                        chained = true
                        showSlider(
                            activity, R.string.sub_style_position,
                            SliderPickerDialog(50.0, 150.0, 1, R.string.format_fixed_number),
                            MPVLib.getPropertyDouble("sub-pos") ?: 100.0, restore,
                        ) { v ->
                            MPVLib.setPropertyInt("sub-pos", v.toInt())
                            prefs(activity).edit().putInt(SubStyles.PREF_POS, v.toInt()).apply()
                        }
                    }
                    else -> { // n + 2: terapkan gaya pada subtitle ASS (default mpv: "scale")
                        val on = !assForce
                        MPVLib.setPropertyString("sub-ass-override", if (on) "force" else "scale")
                        prefs(activity).edit().putBoolean(SubStyles.PREF_ASS_FORCE, on).apply()
                    }
                }
            }
            setOnDismissListener { if (!chained) restore() }
        }.create().show()
    }

    internal fun showSlider(
        activity: Activity, @StringRes title: Int, picker: SliderPickerDialog, current: Double,
        restore: StateRestoreCallback, onOk: (Double) -> Unit,
    ) {
        val dialog = AlertDialog.Builder(activity, R.style.AppTheme_PlayerDialog).apply {
            setTitle(title)
            setView(picker.buildView(LayoutInflater.from(context)))
            setPositiveButton(R.string.dialog_ok) { _, _ -> picker.number?.let(onOk) }
            setNegativeButton(R.string.dialog_cancel) { d, _ -> d.cancel() }
            setOnDismissListener { restore() }
        }.create()
        picker.number = current // setelah buildView: binding sudah ada
        dialog.show()
    }

    fun showSpeedDialog(activity: Activity, restore: StateRestoreCallback) {
        val labels = SubSpeeds.PRESETS.map { SubSpeeds.label(it) ?: activity.getString(R.string.sub_speed_normal) }
        AlertDialog.Builder(activity, R.style.AppTheme_PlayerDialog).apply {
            setTitle(R.string.sub_speed_title)
            setSingleChoiceItems(
                labels.toTypedArray(),
                SubSpeeds.indexOf(MPVLib.getPropertyDouble(SubSpeeds.PROPERTY)),
            ) { d, i ->
                MPVLib.setPropertyDouble(SubSpeeds.PROPERTY, SubSpeeds.PRESETS[i].speed)
                d.dismiss()
            }
            setOnDismissListener { restore() }
        }.create().show()
    }
}

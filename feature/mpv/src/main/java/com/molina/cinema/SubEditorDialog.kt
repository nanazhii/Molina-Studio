package com.molina.cinema

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/**
 * [SUBTITLE-EDIT] Editor subtitle (teks + waktu per cue) untuk pemutar. Daftar cue di kiri-atas, ketuk baris untuk
 * mengubah mulai/akhir/teks (dengan tombol geser ±100 ms dan "Sekarang" = posisi putar), hapus, sisip cue baru di posisi
 * putar, cari teks, urungkan/ulangi, dan simpan. Logika ada di [SubEdit]/[SubEditSession] (diuji JVM); kelas ini hanya UI.
 *
 * Pemutaran dijeda selama editor terbuka. [saver] menulis berkas dan memuatnya ke mpv; mengembalikan path atau null bila gagal.
 * Berkas sumber tidak pernah ditimpa (hasil selalu berkas baru di Molina/Subtitles).
 */
internal class SubEditorDialog(
    private val a: Activity,
    private val format: SubFormat,
    initial: List<SubCue>,
    private val saver: (List<SubCue>, SubFormat) -> String?,
    private val onClosed: () -> Unit,
) {
    private val session = SubEditSession(initial)
    private val d = a.resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())
    private val wasPaused = MPVLib.getPropertyBoolean("pause") == true
    private val saveFormat = if (format == SubFormat.UNKNOWN) SubFormat.SRT else format

    private val cText = ContextCompat.getColor(a, R.color.player_dialog_text)
    private val cSub = ContextCompat.getColor(a, R.color.player_dialog_text_secondary)
    private val cAccent = ContextCompat.getColor(a, R.color.player_playlist_active)
    private val cWarn = Color.parseColor("#FFB300")

    private lateinit var dialog: AlertDialog
    private lateinit var list: RecyclerView
    private lateinit var title: TextView
    private lateinit var note: TextView
    private lateinit var undoBtn: Button
    private lateinit var redoBtn: Button
    private val adapter = Adapter()

    private var activeIdx = -1
    private var overlaps: Set<Int> = emptySet()
    private var lastQuery = ""

    private val poll = object : Runnable {
        override fun run() {
            val idx = SubEdit.cueAt(session.cues, positionMs())
            if (idx != activeIdx) {
                val old = activeIdx
                activeIdx = idx
                if (old in session.cues.indices) adapter.notifyItemChanged(old)
                if (idx in session.cues.indices) adapter.notifyItemChanged(idx)
            }
            ui.postDelayed(this, 400)
        }
    }

    private fun positionMs(): Long = ((MPVLib.getPropertyDouble("time-pos/full") ?: 0.0) * 1000).toLong().coerceAtLeast(0)

    private fun px(dp: Int) = (dp * d).toInt()

    private fun toast(@StringRes id: Int) = Toast.makeText(a, id, Toast.LENGTH_SHORT).show()

    private fun errorRes(e: EditError?): Int = when (e) {
        EditError.BAD_TIME -> R.string.sub_edit_err_time
        EditError.END_BEFORE_START -> R.string.sub_edit_err_order
        EditError.EMPTY_TEXT -> R.string.sub_edit_err_empty
        EditError.TOO_LONG -> R.string.sub_edit_err_long
        EditError.TOO_MANY -> R.string.sub_edit_err_many
        else -> R.string.sub_edit_err_time
    }

    private fun toolButton(@StringRes label: Int, onClick: () -> Unit) = Button(a, null, android.R.attr.borderlessButtonStyle).apply {
        setText(label)
        setAllCaps(false)
        textSize = 13f
        setTextColor(cText)
        minWidth = 0
        minimumWidth = 0
        setPadding(px(4), 0, px(4), 0)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { onClick() }
    }

    fun show() {
        MPVLib.setPropertyBoolean("pause", true)

        title = TextView(a).apply { setTextColor(cText); textSize = 17f; setTypeface(typeface, Typeface.BOLD) }
        note = TextView(a).apply { setTextColor(cSub); textSize = 12f }

        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        undoBtn = toolButton(R.string.sub_edit_undo) { if (session.undo()) refresh() }
        redoBtn = toolButton(R.string.sub_edit_redo) { if (session.redo()) refresh() }
        bar.addView(undoBtn)
        bar.addView(redoBtn)
        bar.addView(toolButton(R.string.sub_edit_add) { openCueEditor(-1) })
        bar.addView(toolButton(R.string.sub_edit_find) { findDialog() })
        bar.addView(toolButton(R.string.sub_edit_here) { scrollTo(activeIdx.coerceAtLeast(0)) })

        list = RecyclerView(a).apply {
            layoutManager = LinearLayoutManager(a)
            adapter = this@SubEditorDialog.adapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(12), px(20), 0)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (a.resources.displayMetrics.heightPixels * 0.68f).toInt())
            addView(title)
            addView(note)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        dialog = AlertDialog.Builder(a, R.style.AppTheme_PlayerDialog)
            .setView(root)
            .setPositiveButton(R.string.sub_edit_save, null)
            .setNegativeButton(R.string.sub_edit_close, null)
            .setCancelable(false)
            .create()
        dialog.setOnDismissListener {
            ui.removeCallbacksAndMessages(null)
            if (!wasPaused) MPVLib.setPropertyBoolean("pause", false)
            onClosed()
        }
        dialog.setOnKeyListener { _, keyCode, ev ->
            if (keyCode == KeyEvent.KEYCODE_BACK && ev?.action == KeyEvent.ACTION_UP) { requestClose(); true } else false
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save() }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { requestClose() }

        refresh()
        activeIdx = SubEdit.cueAt(session.cues, positionMs())
        scrollTo(activeIdx.coerceAtLeast(0))
        ui.postDelayed(poll, 400)
    }

    private fun requestClose() {
        if (!session.dirty) { dialog.dismiss(); return }
        AlertDialog.Builder(a, R.style.AppTheme_PlayerDialog)
            .setTitle(R.string.sub_edit_discard_title)
            .setMessage(R.string.sub_edit_discard_msg)
            .setPositiveButton(R.string.sub_edit_discard) { _, _ -> dialog.dismiss() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun save() {
        val path = saver(session.cues, saveFormat)
        if (path == null) { toast(R.string.sub_convert_write_failed); return }
        session.markSaved()
        refresh()
        Toast.makeText(a, a.getString(R.string.sub_edit_saved, path), Toast.LENGTH_LONG).show()
    }

    /** Segarkan daftar, judul, catatan, dan tombol undo/redo. [scrollTo] >= 0 menggulir ke cue itu. */
    private fun refresh(scrollTo: Int = -1) {
        overlaps = SubEdit.overlaps(session.cues)
        adapter.items = session.cues
        adapter.notifyDataSetChanged()
        title.text = a.getString(R.string.sub_edit_title) + " · " + a.getString(R.string.sub_edit_count, session.cues.size) + if (session.dirty) " •" else ""
        val parts = ArrayList<String>()
        if (format == SubFormat.ASS) parts.add(a.getString(R.string.sub_edit_ass_warn))
        if (overlaps.isNotEmpty()) parts.add(a.getString(R.string.sub_edit_overlaps, overlaps.size))
        note.text = parts.joinToString(" · ")
        note.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        undoBtn.alpha = if (session.canUndo) 1f else 0.35f
        redoBtn.alpha = if (session.canRedo) 1f else 0.35f
        if (scrollTo >= 0) scrollTo(scrollTo)
    }

    private fun scrollTo(index: Int) {
        if (index !in session.cues.indices) return
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, px(72))
    }

    private fun findDialog() {
        val input = EditText(a).apply {
            setText(lastQuery)
            setSelectAllOnFocus(true)
            setSingleLine()
            hint = a.getString(R.string.sub_edit_find_hint)
            setTextColor(cText)
            setHintTextColor(cSub)
        }
        val box = LinearLayout(a).apply { setPadding(px(20), px(8), px(20), 0); addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        AlertDialog.Builder(a, R.style.AppTheme_PlayerDialog)
            .setView(box)
            .setPositiveButton(R.string.sub_edit_find) { _, _ ->
                lastQuery = input.text.toString().trim()
                val first = (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition().coerceAtLeast(0)
                val hit = SubEdit.find(session.cues, lastQuery, first + 1)
                if (hit >= 0) scrollTo(hit) else toast(R.string.sub_edit_not_found)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Ubah cue [index], atau sisipkan cue baru di posisi putar bila [index] < 0. */
    private fun openCueEditor(index: Int) {
        val isNew = index < 0
        val cue = if (isNew) positionMs().let { SubCue(it, it + SubEdit.DEFAULT_DURATION_MS, "") } else session.cues[index]

        fun timeField(ms: Long) = EditText(a).apply {
            setText(TimeCode.format(ms))
            keyListener = DigitsKeyListener.getInstance("0123456789:.,")
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setTextColor(cText)
            typeface = Typeface.MONOSPACE
        }
        val start = timeField(cue.startMs)
        val end = timeField(cue.endMs)

        fun nudge(field: EditText, delta: Long) {
            val t = TimeCode.parse(field.text.toString()) ?: return toast(R.string.sub_edit_err_time)
            field.setText(TimeCode.format((t + delta).coerceIn(0, TimeCode.MAX_MS)))
        }
        fun small(label: String, onClick: () -> Unit) = Button(a, null, android.R.attr.borderlessButtonStyle).apply {
            text = label; setAllCaps(false); textSize = 12f; setTextColor(cAccent); minWidth = 0; minimumWidth = 0
            setPadding(px(6), 0, px(6), 0); setOnClickListener { onClick() }
        }
        fun row(@StringRes label: Int, field: EditText) = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(a).apply { setText(label); setTextColor(cSub); textSize = 13f; width = px(56) })
            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(small("-100") { nudge(field, -100) })
            addView(small("+100") { nudge(field, 100) })
            addView(small(a.getString(R.string.sub_edit_now)) { field.setText(TimeCode.format(positionMs())) })
        }

        val text = EditText(a).apply {
            setText(cue.text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            hint = a.getString(R.string.sub_edit_text)
            setTextColor(cText)
            setHintTextColor(cSub)
        }

        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
            addView(row(R.string.sub_edit_start, start))
            addView(row(R.string.sub_edit_end, end))
            addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })
            if (!isNew) addView(small(a.getString(R.string.sub_edit_seek)) {
                val s = TimeCode.parse(start.text.toString()) ?: return@small toast(R.string.sub_edit_err_time)
                MPVLib.command(arrayOf("seek", String.format(Locale.US, "%.3f", s / 1000.0), "absolute+exact"))
            })
        }

        val builder = AlertDialog.Builder(a, R.style.AppTheme_PlayerDialog)
            .setTitle(if (isNew) R.string.sub_edit_new else R.string.sub_edit_title)
            .setView(box)
            .setPositiveButton(R.string.sub_edit_apply, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (!isNew) builder.setNeutralButton(R.string.sub_edit_delete) { _, _ ->
            session.apply(SubEdit.delete(session.cues, index))
            refresh()
        }
        val dlg = builder.create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val s = TimeCode.parse(start.text.toString())
            val e = TimeCode.parse(end.text.toString())
            if (s == null || e == null) return@setOnClickListener toast(R.string.sub_edit_err_time)
            if (e < s) return@setOnClickListener toast(R.string.sub_edit_err_order)
            val result = if (isNew) SubEdit.insert(session.cues, s, text.text.toString(), e - s)
            else SubEdit.replace(session.cues, index, s, e, text.text.toString())
            val at = session.apply(result)
            if (at == null) return@setOnClickListener toast(errorRes(session.lastError))
            refresh(scrollTo = at)
            dlg.dismiss()
        }
    }

    private class VH(val root: LinearLayout, val meta: TextView, val body: TextView) : RecyclerView.ViewHolder(root)

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        var items: List<SubCue> = emptyList()

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val meta = TextView(a).apply { textSize = 12f; typeface = Typeface.MONOSPACE }
            val body = TextView(a).apply { textSize = 15f; setTextColor(cText); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(8), px(8), px(8), px(8))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                isClickable = true
                addView(meta)
                addView(body)
            }
            val vh = VH(row, meta, body)
            row.setOnClickListener {
                val p = vh.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) openCueEditor(p)
            }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = items[position]
            val active = position == activeIdx
            val overlap = position in overlaps
            h.meta.text = (if (active) "▶ " else "") + (position + 1) + "  " + TimeCode.format(c.startMs) + " → " + TimeCode.format(c.endMs) + if (overlap) "  ⚠" else ""
            h.meta.setTextColor(when { overlap -> cWarn; active -> cAccent; else -> cSub })
            h.body.text = c.text
            h.root.setBackgroundColor(if (active) Color.argb(28, Color.red(cAccent), Color.green(cAccent), Color.blue(cAccent)) else Color.TRANSPARENT)
        }
    }
}

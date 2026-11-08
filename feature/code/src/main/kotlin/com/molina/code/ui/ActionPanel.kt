package com.molina.code.ui

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import com.molina.code.R
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.util.WeakHashMap

// [CODE-ACTIONS] Aksi Molina di menu tekan-lama / sentuh-kursor Sora: Pergi ke definisi, Pergi ke simbol, Kembali.
// Memakai struktur panel bawaan Sora 0.23.6 (root > HorizontalScrollView > LinearLayout); bila strukturnya berbeda,
// tombol tambahan dilewati dan panel bawaan tetap berfungsi.

internal interface ActionPanelHost {
    fun canDefinition(): Boolean
    fun onDefinition()
    fun onSymbols()
    fun canBack(): Boolean
    fun onBack()
}

internal class MolinaActionWindow(private val ed: CodeEditor, private val host: ActionPanelHost) : EditorTextActionWindow(ed) {
    private var defBtn: ImageButton? = null
    private var symBtn: ImageButton? = null
    private var backBtn: ImageButton? = null

    init { addExtras() }

    private fun addExtras() {
        val root = view as? ViewGroup ?: return
        val scroll = root.getChildAt(0) as? HorizontalScrollView ?: return
        val row = scroll.getChildAt(0) as? LinearLayout ?: return
        defBtn = extra(row, R.drawable.molina_ic_definition, "Pergi ke definisi") { host.onDefinition() }
        symBtn = extra(row, R.drawable.molina_ic_symbol, "Pergi ke simbol") { host.onSymbols() }
        backBtn = extra(row, R.drawable.molina_ic_back, "Kembali") { host.onBack() }.also { it.visibility = View.GONE }
        applyColorScheme()
    }

    private fun extra(row: LinearLayout, res: Int, desc: String, click: () -> Unit): ImageButton {
        val ctx = ed.context
        val side = (45 * ed.dpUnit).toInt()
        val b = ImageButton(ctx)
        b.setImageResource(res)
        b.contentDescription = desc
        b.tooltipText = desc
        val tv = TypedValue()
        if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) b.setBackgroundResource(tv.resourceId)
        else b.background = null
        b.layoutParams = LinearLayout.LayoutParams(side, side)
        b.setOnClickListener { dismiss(); click() }
        row.addView(b)
        return b
    }

    /** Dipanggil juga dari konstruktor induk (sebelum tombol ada): jangan menyentuh [ed] sebelum tombol dibuat. */
    override fun applyColorScheme() {
        super.applyColorScheme()
        if (defBtn == null) return
        val color = ed.colorScheme.getColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR)
        for (b in listOf(defBtn, symBtn, backBtn)) b?.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
    }

    override fun displayWindow() {
        val selected = ed.cursor.isSelected
        defBtn?.visibility = if (host.canDefinition()) View.VISIBLE else View.GONE
        symBtn?.visibility = if (selected) View.GONE else View.VISIBLE
        backBtn?.visibility = if (host.canBack()) View.VISIBLE else View.GONE
        super.displayWindow() // mengukur ulang lebar panel dengan tombol tambahan
        // Sora membatasi lebar 230dp; panel kita boleh lebih lebar (selama muat di editor) agar tombol tidak tersembunyi.
        val w = minOf(view.measuredWidth, ed.width - (16 * ed.dpUnit).toInt())
        if (w > 0) setSize(w, height)
    }
}

private val panelInstalled = WeakHashMap<CodeEditor, Boolean>()

/** Pasang panel aksi Molina pada [editor] (sekali per editor). Dipanggil dari EditorHost. */
internal fun installActionPanel(editor: CodeEditor, session: CodeSession) {
    if (panelInstalled.put(editor, true) != null) return
    val host = object : ActionPanelHost {
        override fun canDefinition(): Boolean = session.active?.let { it.file != null && !it.loading } == true
        override fun onDefinition() = session.goToDefinition()
        override fun onSymbols() { session.symbolOpen = true }
        override fun canBack(): Boolean = session.backDepth > 0
        override fun onBack() = session.jumpBack()
    }
    try {
        editor.replaceComponent(EditorTextActionWindow::class.java, MolinaActionWindow(editor, host))
    } catch (e: Exception) {
        // panel bawaan Sora tetap dipakai
    }
}

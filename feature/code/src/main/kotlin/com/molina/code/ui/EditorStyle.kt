package com.molina.code.ui

import android.content.Context
import android.graphics.Typeface
import io.github.rosemoe.sora.widget.CodeEditor
import java.util.WeakHashMap

/** [CODE-STYLE] Pilihan font editor. Berkas font ada di assets/fonts (lisensi SIL OFL 1.1). */
internal enum class EditorFont(val id: String, val label: String, val asset: String?) {
    JETBRAINS("jetbrains", "JetBrains Mono", "fonts/JetBrainsMono-Regular.ttf"),
    FIRA("fira", "Fira Code", "fonts/FiraCode-Regular.ttf"),
    SYSTEM("system", "Monospace sistem", null);

    companion object {
        fun fromId(id: String?): EditorFont = entries.firstOrNull { it.id == id } ?: JETBRAINS
    }
}

internal const val STICKY_MAX_LINES = 3

private val typefaceCache = HashMap<EditorFont, Typeface>()

/** Muat typeface dari assets sekali saja; gagal muat jatuh ke monospace sistem. */
internal fun EditorFont.typeface(context: Context): Typeface = synchronized(typefaceCache) {
    typefaceCache.getOrPut(this) {
        val a = asset ?: return@getOrPut Typeface.MONOSPACE
        runCatching { Typeface.createFromAsset(context.applicationContext.assets, a) }
            .getOrDefault(Typeface.MONOSPACE)
    }
}

private data class AppliedStyle(val font: EditorFont, val ligature: Boolean, val sticky: Boolean)

private val appliedStyle = WeakHashMap<CodeEditor, AppliedStyle>()

/** [CODE-STYLE] Pasang font, ligature, dan sticky scroll ke editor; hanya bila berubah. */
internal fun syncEditorStyle(ed: CodeEditor, font: EditorFont, ligature: Boolean, sticky: Boolean) {
    val want = AppliedStyle(font, ligature, sticky)
    val cur = appliedStyle[ed]
    if (cur == want) return
    appliedStyle[ed] = want
    if (cur?.font != font) ed.typefaceText = font.typeface(ed.context)
    if (cur?.ligature != ligature) ed.setLigatureEnabled(ligature)
    if (cur?.sticky != sticky) {
        ed.props.stickyScroll = sticky
        ed.props.stickyScrollMaxLines = STICKY_MAX_LINES
    }
    ed.invalidate()
}

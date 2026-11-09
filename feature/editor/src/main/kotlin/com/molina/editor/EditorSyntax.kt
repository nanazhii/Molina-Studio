package com.molina.editor

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.tm4e.core.registry.IThemeSource
import java.util.concurrent.Executors

/**
 * [EDITOR-TM] Highlight TextMate (grammar + tema dari assets/textmate), satu kali per proses.
 * Bila gagal, [ensure] mengembalikan false dan editor tetap berjalan sebagai teks polos.
 * Tema Aktif: hanya molina_night (tema lain dari modul lama sengaja tidak dibawa).
 */
internal object EditorSyntax {
    private const val THEME_ID = "molina"
    private const val THEME_ASSET = "textmate/molina_night.json"

    /** Berkas lebih besar dari ini tidak di-highlight agar ketikan tetap lancar. */
    const val MAX_HIGHLIGHT_CHARS = 400_000

    @Volatile var ready = false
        private set
    private var failed = false
    private var model: ThemeModel? = null
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "molina-editor-tm").apply { isDaemon = true } }

    @Synchronized
    fun ensure(context: Context): Boolean {
        if (ready) return true
        if (failed) return false
        val ok = runCatching {
            val files = FileProviderRegistry.getInstance()
            files.addFileProvider(AssetsFileResolver(context.applicationContext.assets))
            val src = IThemeSource.fromInputStream(files.tryGetInputStream(THEME_ASSET), THEME_ASSET, null)
            val m = ThemeModel(src, THEME_ID).apply { isDark = true }
            ThemeRegistry.getInstance().loadTheme(m, true)
            model = m
            // loadGrammars membaca dan mengurai semua grammar: di thread latar.
            exec.execute { runCatching { GrammarRegistry.getInstance().loadGrammars("textmate/languages.json") } }
        }.isSuccess
        if (ok) ready = true else failed = true
        return ok
    }

    fun colorScheme(): EditorColorScheme? {
        val m = model ?: return null
        return if (!ready) null else MolinaTmColorScheme(ThemeRegistry.getInstance(), m)
    }

    /** Nama berkas -> scope TextMate; null bila tidak ada grammar (editor memakai teks polos). */
    fun scopeFor(fileName: String?): String? {
        if (fileName.isNullOrEmpty()) return null
        return when (fileName.lowercase().substringAfterLast('.', "")) {
            "kt", "kts" -> "source.kotlin"
            "java" -> "source.java"
            "py" -> "source.python"
            "js", "mjs", "cjs", "jsx" -> "source.js"
            "xml", "svg", "xsd", "xsl", "xhtml" -> "text.xml"
            "html", "htm" -> "text.html.basic"
            "lua" -> "source.lua"
            "md", "markdown" -> "text.html.markdown"
            "json", "jsonc" -> "source.json"
            "sh", "bash", "zsh" -> "source.shell"
            "css" -> "source.css"
            else -> null
        }
    }

    /** Buat bahasa di thread latar (parse grammar bisa ratusan ms); null bila gagal. */
    fun createAsync(scope: String, onDone: (TextMateLanguage?) -> Unit) {
        exec.execute { onDone(runCatching { TextMateLanguage.create(scope, false) }.getOrNull()) }
    }
}

/** Skema TextMate yang menjaga warna chrome Molina (latar, gutter, seleksi) di atas warna tema. */
internal class MolinaTmColorScheme(registry: ThemeRegistry, model: ThemeModel) : TextMateColorScheme(registry, model) {
    override fun applyDefault() {
        super.applyDefault()
        applyMolinaColors(this)
    }
}

internal fun applyMolinaColors(s: EditorColorScheme) {
    s.setColor(EditorColorScheme.WHOLE_BACKGROUND, EditorColors.EditorBg.toArgb())
    s.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, EditorColors.GutterBg.toArgb())
    s.setColor(EditorColorScheme.LINE_NUMBER, EditorColors.LineNumber.toArgb())
    s.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, EditorColors.LineNumberCurrent.toArgb())
    s.setColor(EditorColorScheme.LINE_DIVIDER, EditorColors.Hairline.toArgb())
    s.setColor(EditorColorScheme.CURRENT_LINE, EditorColors.ActiveLine.toArgb())
    s.setColor(EditorColorScheme.TEXT_NORMAL, EditorColors.TextPrimary.toArgb())
    s.setColor(EditorColorScheme.SELECTION_INSERT, EditorColors.Caret.toArgb())
    s.setColor(EditorColorScheme.SELECTION_HANDLE, EditorColors.Brand.toArgb())
    s.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, EditorColors.Selection.toArgb())
    s.setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, EditorColors.SearchMatch.toArgb())
}

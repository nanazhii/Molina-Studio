package com.molina.code.ui

import android.content.Context
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

/** [CODE-TM] Tema token TextMate yang bisa dipilih. Warna latar/gutter tetap milik Molina. */
internal class TmTheme(val id: String, val label: String, val asset: String, val palette: List<Int>)

/**
 * [CODE-TM] Penyiapan highlight TextMate (grammar + tema dari assets/textmate). Satu kali per proses.
 * Bila gagal (aset rusak, dsb.) [ensure] mengembalikan false dan editor memakai lexer Molina.
 */
internal object MolinaTextMate {
    val THEMES = listOf(
        TmTheme("molina", "Molina Night", "textmate/molina_night.json", // [CODE-THEME] bawaan: hijau lembut, kuning, teal, oren
            listOf(0xFF8FBF7A.toInt(), 0xFFF2C94C.toInt(), 0xFF7FB5A8.toInt(), 0xFFE08A47.toInt())),
        TmTheme("obsidian", "Obsidian Claude", "textmate/obsidian_claude.json",
            listOf(0xFFD97757.toInt(), 0xFF9DBE8A.toInt(), 0xFFE5C07B.toInt(), 0xFF7D766A.toInt())),
        TmTheme("abyss", "Abyss", "textmate/abyss.json",
            listOf(0xFF225588.toInt(), 0xFF22AA44.toInt(), 0xFFF280D0.toInt(), 0xFF384887.toInt())),
        TmTheme("solarized", "Solarized Dark", "textmate/solarized_dark.json",
            listOf(0xFF859900.toInt(), 0xFF2AA198.toInt(), 0xFFD33682.toInt(), 0xFF586E75.toInt()))
    )
    const val DEFAULT_THEME = "molina"

    @Volatile var ready = false
        private set
    private var failed = false
    private var appliedTheme = DEFAULT_THEME
    private val models = HashMap<String, ThemeModel>()
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "molina-tm").apply { isDaemon = true } }

    fun themeOf(id: String?): TmTheme = THEMES.firstOrNull { it.id == id } ?: THEMES.first()

    @Synchronized
    fun ensure(context: Context): Boolean {
        if (ready) return true
        if (failed) return false
        val ok = runCatching {
            val files = FileProviderRegistry.getInstance()
            files.addFileProvider(AssetsFileResolver(context.applicationContext.assets))
            val themes = ThemeRegistry.getInstance()
            // tema bawaan dimuat terakhir agar jadi tema aktif; yang lain tidak mengubah tema aktif
            for (t in THEMES.sortedBy { it.id == DEFAULT_THEME }) {
                val src = IThemeSource.fromInputStream(files.tryGetInputStream(t.asset), t.asset, null)
                val model = ThemeModel(src, t.id).apply { isDark = true }
                themes.loadTheme(model, t.id == DEFAULT_THEME)
                models[t.id] = model
            }
            // loadGrammars membaca dan mengurai semua grammar: di thread latar. createAsync memakai executor
            // yang sama sehingga selalu berjalan setelah ini; bila gagal, bahasa tetap lexer Molina.
            exec.execute { runCatching { GrammarRegistry.getInstance().loadGrammars("textmate/languages.json") } }
        }.isSuccess
        if (ok) ready = true else failed = true
        return ok
    }

    /** Ganti tema aktif; ThemeRegistry menyiarkan ke semua skema warna dan analyzer yang terpasang. */
    fun syncTheme(id: String) {
        if (!ready || appliedTheme == id) return
        val m = models[id] ?: return
        runCatching { ThemeRegistry.getInstance().setTheme(m) }.onSuccess { appliedTheme = id }
    }

    fun currentModel(): ThemeModel = models[appliedTheme] ?: ThemeRegistry.getInstance().currentThemeModel

    /** Nama berkas -> scope TextMate; null bila tidak ada grammar (editor memakai lexer Molina). */
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

    /** Buat bahasa di thread latar (membaca dan mengurai grammar bisa ratusan ms); null bila gagal. */
    fun createAsync(scope: String, onDone: (TextMateLanguage?) -> Unit) {
        exec.execute { onDone(runCatching { TextMateLanguage.create(scope, false) }.getOrNull()) }
    }
}

/** Skema warna TextMate yang menjaga warna chrome Molina (latar, gutter, seleksi) di atas warna tema. */
internal class MolinaTmColorScheme(registry: ThemeRegistry, model: ThemeModel) : TextMateColorScheme(registry, model) {
    override fun applyDefault() {
        super.applyDefault()
        applyMolinaColors(this)
    }
}

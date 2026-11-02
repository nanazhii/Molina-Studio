package com.molina.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** [CODE-TEMPLATE] Satu Live Template. [langs] = ekstensi yang cocok (kosong = semua jenis berkas). */
internal class LiveTemplate(
    val abbr: String,
    val desc: String,
    val langs: Set<String>,
    val body: String,
    val builtin: Boolean
)

/** Template buatan pengguna disimpan di folder draf privat aplikasi (live_templates.json). Best-effort. */
internal class TemplateStore(private val file: File?) {
    fun loadUser(): List<LiveTemplate> = try {
        if (file == null || !file.isFile) {
            emptyList()
        } else {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val l = o.optJSONArray("langs")
                val langs = HashSet<String>()
                if (l != null) for (k in 0 until l.length()) langs.add(l.getString(k))
                LiveTemplate(o.getString("abbr"), o.optString("desc", ""), langs, o.getString("body"), false)
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun saveUser(list: List<LiveTemplate>) {
        if (file == null) return
        try {
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            for (t in list) {
                val o = JSONObject()
                o.put("abbr", t.abbr)
                o.put("desc", t.desc)
                o.put("langs", JSONArray(t.langs.toList()))
                o.put("body", t.body)
                arr.put(o)
            }
            FileStore.writeUtf8Atomic(file, arr.toString())
        } catch (e: Exception) {
            // best-effort
        }
    }
}

internal object LiveTemplates {
    private fun t(abbr: String, desc: String, langs: String, body: String) =
        LiveTemplate(abbr, desc, if (langs.isEmpty()) emptySet() else langs.split(',').toSet(), body, true)

    val DEFAULTS: List<LiveTemplate> = listOf(
        t("main", "fun main Kotlin", "kt,kts", "fun main() {\n    \$CURSOR\$\n}\n"),
        t("logd", "Log.d Android", "kt,java", "android.util.Log.d(\"\$FILE\$\", \"\$CURSOR\$\")"),
        t("main", "main Java", "java", "public static void main(String[] args) {\n    \$CURSOR\$\n}\n"),
        t("main", "main + guard Python", "py", "def main():\n    \$CURSOR\$\n\n\nif __name__ == \"__main__\":\n    main()\n"),
        t("shebang", "shebang Python", "py", "#!/usr/bin/env python3\n\$CURSOR\$\n"),
        t("for", "for range Python", "py", "for i in range(\$CURSOR\$):\n    pass\n"),
        t("shebang", "shebang bash + mode ketat", "sh,bash", "#!/usr/bin/env bash\nset -euo pipefail\n\n\$CURSOR\$\n"),
        t("if", "if [ ] bash", "sh,bash", "if [ \$CURSOR\$ ]; then\n  :\nfi\n"),
        t("log", "console.log", "js,jsx,ts,tsx,mjs", "console.log(\$CURSOR\$);"),
        t("code", "blok kode (bungkus seleksi)", "md,markdown", "```\n\$SELECTION\$\$CURSOR\$\n```\n"),
        t("table", "tabel Markdown", "md,markdown", "| \$CURSOR\$ |  |\n| --- | --- |\n|  |  |\n"),
        t("task", "daftar tugas", "md,markdown", "- [ ] \$CURSOR\$"),
        t("todo", "TODO bertanggal", "", "TODO \$DATE\$: \$CURSOR\$"),
        t("date", "tanggal hari ini", "", "\$DATE\$"),
        t("time", "jam sekarang", "", "\$TIME\$"),
        t("now", "tanggal dan jam", "", "\$DATETIME\$")
    )
}

internal class Expanded(val text: String, val cursor: Int)

/** Variabel: $DATE$ yyyy-MM-dd, $TIME$ HH:mm, $DATETIME$, $FILE$ (nama tanpa ekstensi), $SELECTION$ (teks terpilih), $CURSOR$ (posisi kursor akhir). */
internal object TemplateExpander {
    fun expand(body: String, selection: String, fileName: String, indent: String, now: Date = Date()): Expanded {
        val loc = Locale.US
        var s = body
            .replace("\$DATETIME\$", SimpleDateFormat("yyyy-MM-dd HH:mm", loc).format(now))
            .replace("\$DATE\$", SimpleDateFormat("yyyy-MM-dd", loc).format(now))
            .replace("\$TIME\$", SimpleDateFormat("HH:mm", loc).format(now))
            .replace("\$FILE\$", fileName)
        val trailing = s.endsWith("\n")
        val core = if (trailing) s.dropLast(1) else s
        s = core.replace("\n", "\n" + indent) + (if (trailing) "\n" else "")
        s = s.replace("\$SELECTION\$", selection)
        val ci = s.indexOf("\$CURSOR\$")
        val clean = s.replace("\$CURSOR\$", "")
        return Expanded(clean, if (ci < 0) clean.length else ci)
    }
}

/** Sisipkan [t] di kursor (mengganti seleksi), indent mengikuti baris kursor. Mengembalikan pesan galat atau null. */
internal fun CodeSession.insertTemplate(t: LiveTemplate): String? {
    val tab = active ?: return "Tidak ada tab editor"
    if (tab.loading) return "Editor sedang memuat"
    if (tab.readOnly) return "Tab ini hanya-baca"
    val ed = tab.editor ?: return "Editor belum siap"
    return try {
        val c = ed.cursor
        val start = c.left
        val end = c.right
        val sel = if (c.isSelected) ed.text.substring(start, end) else ""
        val indent = ed.text.getLineString(c.leftLine).takeWhile { it == ' ' || it == '\t' }
        val name = (tab.file?.name ?: tab.title).substringBeforeLast('.')
        val ex = TemplateExpander.expand(t.body, sel, name, indent)
        ed.text.replace(start, end, ex.text)
        val pos = ed.text.indexer.getCharPosition(start + ex.cursor)
        ed.setSelection(pos.line, pos.column)
        null
    } catch (e: Exception) {
        "Gagal menyisipkan template"
    }
}

@Composable
private fun TplButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.38f)
            .clip(shape)
            .border(1.dp, CodeColors.IconDefault.copy(alpha = 0.4f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = TextStyle(color = CodeColors.IconDefault, fontSize = 13.sp))
    }
}

/** Dialog Live Template: cari dan sisipkan template yang cocok dengan jenis berkas; atau simpan seleksi sebagai template baru. */
@Composable
internal fun LiveTemplateDialog(session: CodeSession, onDismiss: () -> Unit) {
    val tab = session.active
    val ext = (tab?.file?.name ?: tab?.title ?: "").substringAfterLast('.', "").lowercase()
    var user by remember { mutableStateOf<List<LiveTemplate>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var abbrInput by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { user = withContext(Dispatchers.IO) { session.templates.loadUser() } }
    LaunchedEffect(saving) { runCatching { focus.requestFocus() } }

    val shown = remember(user, query, ext) {
        val q = query.trim()
        (LiveTemplates.DEFAULTS + user)
            .filter { (it.langs.isEmpty() || ext in it.langs) && (q.isEmpty() || it.abbr.contains(q, true) || it.desc.contains(q, true)) }
            .sortedBy { if (q.isNotEmpty() && it.abbr.startsWith(q, true)) 0 else 1 }
    }

    fun saveNew() {
        val abbr = abbrInput.trim()
        val body = session.selectionOrLine().trimEnd('\n', '\r')
        when {
            abbr.isEmpty() || !abbr.all { it.isLetterOrDigit() || it == '_' || it == '-' } ->
                session.toast("Singkatan hanya huruf, angka, _ atau -")
            body.isBlank() -> session.toast("Tidak ada teks untuk dijadikan template")
            body.length > 4000 -> session.toast("Teks terlalu panjang (maks. 4000 karakter)")
            else -> {
                val list = user.filter { it.abbr != abbr } +
                    LiveTemplate(abbr, "Template saya", if (ext.isEmpty()) emptySet() else setOf(ext), body, false)
                user = list
                session.runIo { session.templates.saveUser(list) }
                session.toast("Template \"" + abbr + "\" disimpan")
                saving = false
                abbrInput = ""
            }
        }
    }

    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).win7DialogFrame(shape)) {
            BasicText(
                text = if (saving) "Simpan seleksi sebagai template" else "Live Template",
                modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
            )
            BasicTextField(
                value = if (saving) abbrInput else query,
                onValueChange = { v -> if (saving) abbrInput = v.take(20) else query = v.take(40) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).padding(horizontal = 16.dp, vertical = 12.dp),
                singleLine = true,
                textStyle = TextStyle(color = CodeColors.TextPrimary, fontSize = CodeDimens.FindTextSize),
                cursorBrush = SolidColor(CodeColors.Gold),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if ((if (saving) abbrInput else query).isEmpty()) {
                            BasicText(
                                text = if (saving) "Singkatan (mis. mylog)" else "Cari template",
                                style = TextStyle(color = CodeColors.IconDefault.copy(alpha = 0.5f), fontSize = CodeDimens.FindTextSize)
                            )
                        }
                        inner()
                    }
                }
            )
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (saving) {
                    TplButton("Simpan") { saveNew() }
                    Spacer(Modifier.width(8.dp))
                    TplButton("Batal") { saving = false }
                } else {
                    TplButton("Simpan seleksi\u2026", enabled = tab != null && session.selectionOrLine().isNotBlank()) { saving = true }
                    Spacer(Modifier.width(8.dp))
                    TplButton("Tutup") { onDismiss() }
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(CodeDimens.Hairline).background(CodeColors.Hairline))
            if (!saving) {
                if (shown.isEmpty()) {
                    BasicText(
                        text = "Tidak ada template untuk jenis berkas ini.",
                        modifier = Modifier.padding(16.dp),
                        style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 13.sp)
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxH)) {
                    items(shown) { t ->
                        val preview = t.body.replace("\$CURSOR\$", "").lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    session.insertTemplate(t)?.let { session.toast(it) }
                                    onDismiss()
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(
                                text = t.abbr,
                                modifier = Modifier.width(76.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = CodeColors.Gold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            )
                            Column(Modifier.weight(1f)) {
                                BasicText(
                                    text = t.desc,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(color = CodeColors.IconDefault, fontSize = 14.sp)
                                )
                                BasicText(
                                    text = preview,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                )
                            }
                            if (!t.builtin) {
                                BasicText(
                                    text = "Hapus",
                                    modifier = Modifier
                                        .clickable {
                                            val list = user.filter { it !== t }
                                            user = list
                                            session.runIo { session.templates.saveUser(list) }
                                        }
                                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                                    style = TextStyle(color = CodeColors.Danger, fontSize = 12.sp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private class ScratchType(val label: String, val ext: String)

private val SCRATCH_TYPES = listOf(
    ScratchType("Kotlin", "kt"), ScratchType("Python", "py"), ScratchType("Shell (bash)", "sh"),
    ScratchType("JavaScript", "js"), ScratchType("Java", "java"), ScratchType("Markdown", "md"),
    ScratchType("JSON", "json"), ScratchType("HTML", "html"), ScratchType("Teks biasa", "txt")
)

/** Pilih jenis scratch: tab baru bernama Scratch.<ekstensi> (highlight, Struktur, dan Play .sh/.py/.md/.json/.html langsung aktif). */
@Composable
internal fun ScratchDialog(session: CodeSession, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().win7DialogFrame(shape)) {
            BasicText(
                text = "Scratch baru",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(SCRATCH_TYPES) { st ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                session.newScratch(st.ext)
                                onDismiss()
                            }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            text = st.label,
                            modifier = Modifier.weight(1f),
                            style = TextStyle(color = CodeColors.IconDefault, fontSize = 15.sp)
                        )
                        BasicText(
                            text = "." + st.ext,
                            style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [CODE-REPLACE] Satu berkas dalam rencana ganti: [original] dan [updated] = isi penuh; [sampleBefore]/[sampleAfter] = baris pertama yang berubah. */
internal class PlanItem(
    val file: File,
    val count: Int,
    val original: String,
    val updated: String,
    val sampleBefore: String,
    val sampleAfter: String
)

internal class ReplacePlan(val items: List<PlanItem>, val skipped: List<String>, val total: Int, val note: String?)

internal class UndoEntry(val file: File, val original: String, val updated: String)
internal class ReplaceUndo(val entries: List<UndoEntry>)

internal class ApplyResult(val done: List<PlanItem>, val failed: List<String>)

/** Mesin Ganti di berkas: murni Kotlin, dipanggil dari thread IO. Penggantian per baris (karakter baris baru tidak pernah disentuh). */
internal object ReplaceEngine {
    const val MAX_FILES = 200
    private const val MAX_BYTES = 1_000_000L
    private const val MAX_UNDO_CHARS = 10_000_000L

    /** Mode regex: $0..$9 = isi grup; mode biasa: teks apa adanya. Kecocokan kosong tidak pernah diganti. */
    private fun expand(rep: String, m: MatchResult): String {
        val sb = StringBuilder()
        var i = 0
        while (i < rep.length) {
            val c = rep[i]
            if (c == '$' && i + 1 < rep.length && rep[i + 1].isDigit()) {
                val g = rep[i + 1] - '0'
                if (g < m.groupValues.size) sb.append(m.groupValues[g])
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Susun rencana dari [files]. [dirtyKeys] = kunci berkas yang tab-nya belum disimpan (dilewati). */
    fun plan(files: List<File>, re: Regex, replacement: String, regexMode: Boolean, dirtyKeys: Set<String>): ReplacePlan {
        val items = ArrayList<PlanItem>()
        val skipped = ArrayList<String>()
        var total = 0
        for (f in files) {
            val len = f.length()
            if (len == 0L || len > MAX_BYTES) continue
            val bytes = try { f.readBytes() } catch (e: Exception) { continue }
            var binary = false
            for (i in 0 until minOf(bytes.size, 4096)) if (bytes[i] == 0.toByte()) { binary = true; break }
            if (binary) continue
            val text = String(bytes, Charsets.UTF_8)
            if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
                if (re.containsMatchIn(text)) skipped.add(f.name + " (bukan UTF-8 murni)")
                continue
            }
            if (!re.containsMatchIn(text)) continue
            if (FileStore.keyOf(f) in dirtyKeys) {
                skipped.add(f.name + " (tab belum disimpan)")
                continue
            }
            var count = 0
            var before = ""
            var after = ""
            val lines = text.split('\n')
            val out = ArrayList<String>(lines.size)
            for (line in lines) {
                var n = 0
                val nl = re.replace(line) { m ->
                    if (m.value.isEmpty()) {
                        m.value
                    } else {
                        n++
                        if (regexMode) expand(replacement, m) else replacement
                    }
                }
                if (n > 0 && nl != line) {
                    count += n
                    if (before.isEmpty()) {
                        before = line.trim().take(100)
                        after = nl.trim().take(100)
                    }
                }
                out.add(nl)
            }
            if (count == 0) continue
            val updated = out.joinToString("\n")
            if (updated == text) continue
            items.add(PlanItem(f, count, text, updated, before, after))
            total += count
            if (items.size > MAX_FILES) {
                return ReplacePlan(emptyList(), skipped, 0, "Terlalu banyak berkas (lebih dari " + MAX_FILES + "). Persempit pencarian.")
            }
        }
        return ReplacePlan(items, skipped, total, null)
    }

    /** Terapkan rencana: berkas yang berubah sejak pratinjau dilewati. Tulis atomik. */
    fun apply(plan: ReplacePlan): ApplyResult {
        val done = ArrayList<PlanItem>()
        val failed = ArrayList<String>()
        for (it in plan.items) {
            try {
                val cur = String(it.file.readBytes(), Charsets.UTF_8)
                if (cur != it.original) {
                    failed.add(it.file.name + " (berubah sejak pratinjau)")
                    continue
                }
                FileStore.writeUtf8Atomic(it.file, it.updated)
                done.add(it)
            } catch (e: Exception) {
                failed.add(it.file.name + " (gagal ditulis)")
            }
        }
        return ApplyResult(done, failed)
    }

    fun undoFor(done: List<PlanItem>): ReplaceUndo? {
        var chars = 0L
        for (d in done) chars += d.original.length.toLong() + d.updated.length.toLong()
        if (chars > MAX_UNDO_CHARS) return null
        return ReplaceUndo(done.map { UndoEntry(it.file, it.original, it.updated) })
    }

    /** Kembalikan isi lama; berkas yang sudah berubah lagi setelah penggantian dilewati. Mengembalikan (jumlah pulih, gagal). */
    fun undo(u: ReplaceUndo): Pair<List<File>, List<String>> {
        val ok = ArrayList<File>()
        val failed = ArrayList<String>()
        for (e in u.entries) {
            try {
                val cur = String(e.file.readBytes(), Charsets.UTF_8)
                if (cur != e.updated) {
                    failed.add(e.file.name + " (berubah setelah ganti)")
                    continue
                }
                FileStore.writeUtf8Atomic(e.file, e.original)
                ok.add(e.file)
            } catch (ex: Exception) {
                failed.add(e.file.name + " (gagal ditulis)")
            }
        }
        return Pair(ok, failed)
    }
}

/** Muat ulang tab bersih yang berkasnya baru diubah dari luar editor, lalu segarkan panel direktori. */
internal fun CodeSession.refreshAfterReplace(changed: List<File>) {
    for (f in changed) {
        val t = tabFor(f) ?: continue
        if (!t.dirty && !t.loading) doReload(t)
    }
    dir.refresh()
}

/** Menu "Urungkan ganti di berkas": pulihkan penggantian terakhir (hanya berkas yang belum berubah lagi). */
internal fun CodeSession.undoLastReplace() {
    val u = lastReplace ?: return
    lastReplace = null
    runIo {
        val r = ReplaceEngine.undo(u)
        mainHandler.post {
            refreshAfterReplace(r.first)
            toast(
                if (r.second.isEmpty()) "Dipulihkan: " + r.first.size + " berkas"
                else "Dipulihkan " + r.first.size + ", dilewati " + r.second.size + ": " + r.second.first()
            )
        }
    }
}

@Composable
private fun RpButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
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

/** Baris Ganti di dialog Cari di berkas: kolom pengganti dan tombol "Ganti semua..." (membuka pratinjau). */
@Composable
internal fun ReplaceBar(
    session: CodeSession,
    files: List<File>,
    query: String,
    regex: Boolean,
    caseSensitive: Boolean,
    hasHits: Boolean,
    replacement: String,
    onReplacement: (String) -> Unit,
    onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<ReplacePlan?>(null) }
    var busy by remember { mutableStateOf(false) }

    BasicTextField(
        value = replacement,
        onValueChange = { onReplacement(it.take(200)) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        textStyle = TextStyle(color = CodeColors.TextPrimary, fontSize = CodeDimens.FindTextSize),
        cursorBrush = SolidColor(CodeColors.Gold),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                if (replacement.isEmpty()) {
                    BasicText(
                        text = if (regex) "Ganti dengan (\$1 = grup)" else "Ganti dengan (kosong = hapus)",
                        style = TextStyle(color = CodeColors.IconDefault.copy(alpha = 0.5f), fontSize = CodeDimens.FindTextSize)
                    )
                }
                inner()
            }
        }
    )
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        RpButton(if (busy) "Menyiapkan\u2026" else "Ganti semua\u2026", enabled = hasHits && !busy) {
            val re = FindInFiles.compile(query, regex, caseSensitive)
            if (re != null) {
                val dirty = session.tabs.filter { it.dirty }.mapNotNull { t -> t.file?.let { FileStore.keyOf(it) } }.toSet()
                busy = true
                scope.launch {
                    val p = withContext(Dispatchers.IO) { ReplaceEngine.plan(files, re, replacement, regex, dirty) }
                    busy = false
                    if (p.items.isEmpty()) session.toast(p.note ?: "Tidak ada yang bisa diganti" + (if (p.skipped.isNotEmpty()) " (" + p.skipped.size + " dilewati)" else ""))
                    else plan = p
                }
            }
        }
        if (session.lastReplace != null) {
            Spacer(Modifier.width(8.dp))
            RpButton("Urungkan") { session.undoLastReplace(); onDone() }
        }
    }

    val p = plan
    if (p != null) {
        val shape = RoundedCornerShape(CodeDimens.DialogCorner)
        val maxH = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
        Dialog(onDismissRequest = { plan = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).win7DialogFrame(shape)) {
                BasicText(
                    text = "Ganti " + p.total + " kemunculan di " + p.items.size + " berkas?",
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                    style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
                )
                if (p.skipped.isNotEmpty()) {
                    BasicText(
                        text = "Dilewati " + p.skipped.size + ": " + p.skipped.take(3).joinToString(", ") + (if (p.skipped.size > 3) ", \u2026" else ""),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 12.sp)
                    )
                }
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(CodeDimens.Hairline).background(CodeColors.Hairline))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxH)) {
                    items(p.items) { item ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BasicText(
                                    text = item.file.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                    style = TextStyle(color = CodeColors.IconDefault, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                )
                                BasicText(
                                    text = item.count.toString(),
                                    style = TextStyle(color = CodeColors.Gold, fontSize = 12.sp)
                                )
                            }
                            BasicText(
                                text = "- " + item.sampleBefore,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = CodeColors.Danger, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            )
                            BasicText(
                                text = "+ " + item.sampleAfter,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = CodeColors.Clean, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RpButton(if (busy) "Menerapkan\u2026" else "Terapkan", enabled = !busy) {
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { ReplaceEngine.apply(p) }
                            busy = false
                            session.lastReplace = ReplaceEngine.undoFor(r.done)
                            session.refreshAfterReplace(r.done.map { it.file })
                            session.toast(
                                "Diganti di " + r.done.size + " berkas" +
                                    (if (r.failed.isNotEmpty()) ", dilewati " + r.failed.size + ": " + r.failed.first() else "")
                            )
                            plan = null
                            onDone()
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    RpButton("Batal", enabled = !busy) { plan = null }
                }
            }
        }
    }
}

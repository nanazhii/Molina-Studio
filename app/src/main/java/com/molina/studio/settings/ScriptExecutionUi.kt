package com.molina.studio.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.code.preview.CommandBlockSpec // [CODE-CMDBLOCK]
import com.molina.code.preview.ScriptLaunch
import com.molina.studio.code.CodeNav
import com.molina.studio.code.CodeOpenRequest
import com.molina.studio.code.ScriptItem
import com.molina.studio.ScriptShortcuts
import com.molina.studio.code.ScriptRunDialog
import com.molina.studio.code.ScriptRunner
import com.molina.studio.code.runIfTrusted
import com.molina.studio.code.ScriptStore
import com.molina.studio.terminal.TerminalSessions
import com.molina.studio.ui.MolinaTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Blue = Color(0xFF6CB4FF)
private val Red = Color(0xFFE8776B)
private const val PREVIEW_LINES = 30

/**
 * Halaman "Script execution": daftar ringkas (judul + segitiga jalankan). Ketuk judul untuk detail
 * (catatan, pratinjau 30 baris). Pengeditan isi dilakukan di editor Code (tombol EDIT membuka tab Code);
 * simpan balik lewat menu titik tiga > Kirim ke Script Execution.
 */
@Composable
internal fun ScriptsPage() {
    val ctx = LocalContext.current
    val items by ScriptStore.items.collectAsState()
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<ScriptItem?>(null) }
    var toRun by remember { mutableStateOf<ScriptItem?>(null) }
    var noteOf by remember { mutableStateOf<ScriptItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var optsOf by remember { mutableStateOf<ScriptItem?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(items, query) {
        val q = query.trim()
        items.filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) || it.note.contains(q, ignoreCase = true) }
            .sortedByDescending { it.favorite }
    }

    Text(
        "Ketuk segitiga untuk menjalankan di Molina Terminal, ketuk judul untuk detail. EDIT membuka skrip di " +
            "editor Code; simpan balik lewat menu titik tiga > Kirim ke Script Execution.",
        color = W7D.TextDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Skrip tersimpan: ${items.size} / ${ScriptStore.MAX_SCRIPTS}",
            color = W7D.NavHeader, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
        )
        ActionText("+ TAMBAH", Blue) { adding = true }
    }
    Spacer(Modifier.height(8.dp))
    if (items.isEmpty()) {
        Text(
            "Belum ada skrip. Tekan + TAMBAH atau kirim dari editor Code.",
            color = W7D.TextDim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp)
        )
    }
    if (items.size > 6) {
        W7Field(query, { query = it.take(60) }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) Text("Tidak ada skrip yang cocok.", color = W7D.TextDim, fontSize = 13.sp)
    }
    shown.forEach { item ->
        key(item.id) {
            ScriptAccordion(
                item = item,
                expanded = expandedId == item.id,
                ctx = ctx,
                onToggle = { expandedId = if (expandedId == item.id) null else item.id },
                onRun = { if (!runIfTrusted(ctx, item)) { toRun = item } },
                onFav = { ScriptStore.setFavorite(item.id, !item.favorite) },
                onOpts = { optsOf = item },
                onEdit = {
                    CodeNav.pending = CodeOpenRequest(item.title, item.sourceKey ?: "script:${item.id}", item.body)
                },
                onNote = { noteOf = item },
                onPin = { toast(ctx, ScriptShortcuts.pin(ctx, item)) },
                onDelete = { toDelete = item }
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    if (adding) {
        NewScriptDialog(onDismiss = { adding = false }) { raw ->
            adding = false
            val name = if (raw.substringAfterLast('.', "").lowercase() in setOf("sh", "bash", "py")) raw else "$raw.sh"
            val body = if (name.endsWith(".py", ignoreCase = true)) "#!/usr/bin/env python3\n\n" else "#!/bin/bash\n\n"
            CodeNav.pending = CodeOpenRequest(name, "script:new-${System.currentTimeMillis()}", body)
        }
    }
    toRun?.let { item -> ScriptRunDialog(item) { toRun = null } }
    optsOf?.let { item ->
        var args by remember(item.id) { mutableStateOf(item.args) }
        var trust by remember(item.id) { mutableStateOf(item.trusted) }
        AlertDialog(
            onDismissRequest = { optsOf = null },
            title = { Text("Opsi skrip") },
            text = {
                Column {
                    Text("Argumen, ditulis seperti di prompt shell (mis. --cepat \"a b\"):", fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    W7Field(args, { args = it.filter { c -> c >= ' ' }.take(ScriptStore.MAX_ARGS) }, singleLine = true)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Percaya skrip ini", fontSize = 14.sp)
                            Text(
                                "Ketuk segitiga di Settings atau Pemilih skrip langsung menjalankan tanpa konfirmasi. " +
                                    "Pintasan layar utama tetap meminta konfirmasi.",
                                fontSize = 11.sp
                            )
                        }
                        Switch(checked = trust, onCheckedChange = { trust = it })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    optsOf = null
                    ScriptStore.setOptions(item.id, args, trust)
                    toast(ctx, "Opsi \"${item.title}\" disimpan")
                }) { Text("SIMPAN") }
            },
            dismissButton = { TextButton(onClick = { optsOf = null }) { Text("BATAL") } }
        )
    }
    noteOf?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.note) }
        AlertDialog(
            onDismissRequest = { noteOf = null },
            title = { Text("Catatan skrip") },
            text = { W7Field(text, { text = it.take(ScriptLaunch.MAX_NOTE) }, Modifier.height(110.dp)) },
            confirmButton = {
                TextButton(onClick = {
                    noteOf = null
                    toast(ctx, ScriptStore.save(item.id, item.title, text, item.body).message)
                }) { Text("SIMPAN") }
            },
            dismissButton = { TextButton(onClick = { noteOf = null }) { Text("BATAL") } }
        )
    }
    toDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Hapus skrip?") },
            text = { Text("\"${item.title}\" akan dihapus dari Script Execution. Berkas di editor Code tidak berubah.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    if (expandedId == item.id) expandedId = null
                    ScriptStore.delete(item.id)
                    ScriptRunner.cleanup(ctx, item.id)
                    ScriptShortcuts.onDeleted(ctx, item.id)
                    toast(ctx, "\"${item.title}\" dihapus")
                }) { Text("HAPUS", color = Red) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("BATAL") } }
        )
    }
}

@Composable
private fun NewScriptDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Skrip baru") },
        text = {
            Column {
                Text("Nama skrip, mis. backup.sh atau tools.py. Isinya ditulis di editor Code.", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                W7Field(name, { name = it.take(ScriptLaunch.MAX_TITLE) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()) }) { Text("BUKA EDITOR") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("BATAL") } }
    )
}

@Composable
private fun ScriptAccordion(
    item: ScriptItem,
    expanded: Boolean,
    ctx: Context,
    onToggle: () -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onNote: () -> Unit,
    onPin: () -> Unit,
    onFav: () -> Unit,
    onOpts: () -> Unit,
    onDelete: () -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    val lines = remember(item.body) { ScriptLaunch.countLines(item.body) }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(W7D.Card, shape)
            .border(1.dp, if (expanded) W7D.SelLine else W7D.Hairline, shape)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (item.favorite) "\u2605" else "\u2606",
                color = if (item.favorite) MolinaTheme.Gold else W7D.TextDim, fontSize = 20.sp,
                modifier = Modifier.clickable(onClick = onFav).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
            )
            Text(
                item.title, color = W7D.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable(onClick = onToggle)
                    .padding(start = 4.dp, top = 14.dp, bottom = 14.dp, end = 8.dp)
            )
            Box(Modifier.size(48.dp).clickable(onClick = onRun), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(18.dp)) {
                    val w = size.width
                    val h = size.height
                    val p = Path()
                    p.moveTo(w * 0.15f, h * 0.05f)
                    p.lineTo(w * 0.95f, h * 0.5f)
                    p.lineTo(w * 0.15f, h * 0.95f)
                    p.close()
                    drawPath(p, MolinaTheme.Gold)
                }
            }
        }
        if (!expanded) return@Column
        Box(Modifier.fillMaxWidth().height(1.dp).background(W7D.Hairline))
        Column(Modifier.padding(12.dp)) {
            Text(item.note.ifBlank { firstLine(item.body) }, color = W7D.Ink, fontSize = 13.sp)
            Text(meta(item, lines), color = W7D.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            if (item.sourceKey != null) {
                val from = when {
                    item.sourceKey.startsWith("tab:") -> "tab editor Code"
                    item.sourceKey.startsWith("script:") -> "skrip baru (editor Code)"
                    else -> item.sourceKey
                }
                Text("Dari: $from", color = W7D.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
            }
            val code = remember(item.body) { codePreview(item.body) }
            CommandBlock(code)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionText("EDIT", Blue, onClick = onEdit)
                ActionText("CATATAN", W7D.Ink, onClick = onNote)
                ActionText("OPSI", W7D.Ink, onClick = onOpts)
                ActionText("PINTASAN", W7D.Ink, onClick = onPin)
                ActionText("SALIN", W7D.Ink) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText(item.title, item.body))
                    toast(ctx, "Isi skrip disalin")
                }
                Spacer(Modifier.weight(1f))
                ActionText("HAPUS", Red, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun W7Field(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false
) {
    val shape = RoundedCornerShape(5.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = W7D.Ink, fontSize = 14.sp, lineHeight = 20.sp),
        cursorBrush = SolidColor(W7D.NavHeader),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        modifier = modifier.fillMaxWidth().clip(shape).background(W7D.Field, shape).border(1.dp, W7D.Line, shape).padding(10.dp)
    )
}

@Composable
private fun ActionText(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.alpha(if (enabled) 1f else 0.35f)
            .clip(RoundedCornerShape(4.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

private fun firstLine(body: String): String =
    body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#!") }.orEmpty()

/** 30 baris pertama; sisanya diringkas supaya detail tetap ringan untuk skrip ribuan baris. */
private fun codePreview(body: String): String {
    val all = body.lines()
    val shown = all.take(PREVIEW_LINES).joinToString("\n") { if (it.length > 200) it.take(200) + "\u2026" else it }
    val more = all.size - PREVIEW_LINES
    return if (more > 0) "$shown\n\u2026 (+$more baris lagi, tekan EDIT untuk membuka semua di editor Code)" else shown
}

private fun meta(item: ScriptItem, lines: Int): String {
    val f = SimpleDateFormat("d MMM yyyy HH:mm", Locale("id", "ID"))
    val ran = if (item.lastRunAt > 0) " \u00B7 dijalankan ${f.format(Date(item.lastRunAt))}" else ""
    return "$lines baris \u00B7 diperbarui ${f.format(Date(item.updatedAt))}$ran"
}

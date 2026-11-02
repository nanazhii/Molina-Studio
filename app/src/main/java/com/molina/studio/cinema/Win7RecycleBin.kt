package com.molina.studio.cinema

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.core.common.TrashBin
import com.molina.core.common.TrashItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ikon Recycle Bin gaya Win7: tong kaca abu-abu dengan tutup dan celah. */
@Composable
internal fun Win7BinIcon(box: Dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        val h = size.height
        drawRoundRect(Color(0xFFB9C7D6), Offset(w * 0.22f, h * 0.30f), Size(w * 0.56f, h * 0.62f), CornerRadius(w * 0.06f))
        drawRoundRect(Color(0xFF7F93A8), Offset(w * 0.14f, h * 0.18f), Size(w * 0.72f, h * 0.12f), CornerRadius(w * 0.04f))
        drawRoundRect(Color(0xFF7F93A8), Offset(w * 0.40f, h * 0.08f), Size(w * 0.20f, h * 0.10f), CornerRadius(w * 0.03f))
        val s = w * 0.05f
        for (x in listOf(0.38f, 0.50f, 0.62f)) {
            drawLine(Color(0xFF5B6F84), Offset(w * x, h * 0.40f), Offset(w * x, h * 0.82f), s, StrokeCap.Round)
        }
    }
}

private fun daysText(n: Int): String = if (n <= 0) "dibersihkan hari ini" else "$n hari lagi"

/**
 * Jendela Recycle Bin: daftar item (nama, folder asal, ukuran, sisa hari), pilih banyak, Pulihkan,
 * Hapus permanen, dan Kosongkan. [onChanged] dipanggil setelah berkas dipulihkan agar daftar Penjelajah menyegarkan diri.
 */
@Composable
internal fun W7BinDialog(volumes: List<File>, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<TrashItem>?>(null) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var msg by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    val now = remember(tick) { System.currentTimeMillis() }

    LaunchedEffect(tick) {
        items = withContext(Dispatchers.IO) { runCatching { TrashBin.list(volumes) }.getOrDefault(emptyList()) }
    }

    fun act(changedFiles: Boolean, op: () -> String) {
        if (busy) return
        busy = true
        ok = true
        msg = "Memproses\u2026"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(op) }
            busy = false
            r.onSuccess { msg = it; ok = true; picked = emptySet(); if (changedFiles) onChanged() }
                .onFailure { msg = it.message ?: "Operasi gagal."; ok = false }
            tick++
        }
    }

    val list = items
    val chosen = list.orEmpty().filter { it.dir.path in picked }

    W7Panel("Recycle Bin", onDismiss) {
        when {
            list == null -> Text("Memuat\u2026", color = Win7.TextDim, fontSize = 12.sp)
            list.isEmpty() -> Text("Recycle Bin kosong.", color = Win7.TextDim, fontSize = 13.sp)
            else -> {
                val all = picked.size == list.size
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        list.size.toString() + " item \u00B7 " + FileOps.readable(list.sumOf { it.size }),
                        color = Win7.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (all) "Batal pilih" else "Pilih semua", color = Win7.NavHeader, fontSize = 12.sp,
                        modifier = Modifier.clickable { picked = if (all) emptySet() else list.map { it.dir.path }.toSet() }.padding(4.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                val shape = RoundedCornerShape(2.dp)
                Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    list.take(200).forEach { item ->
                        val on = item.dir.path in picked
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 1.dp)
                                .then(if (on) Modifier.background(Win7.SelFill, shape).border(1.dp, Win7.SelLine, shape) else Modifier)
                                .clickable { picked = if (on) picked - item.dir.path else picked + item.dir.path }
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (item.isDir) Win7Folder(20.dp)
                            else Box(Modifier.padding(horizontal = 3.dp).size(14.dp, 18.dp).border(1.dp, Win7.Line, shape))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = Win7.Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    (item.origin.parent ?: "") + " \u00B7 " + FileOps.readable(item.size) + " \u00B7 " + daysText(item.daysLeft(now)),
                                    color = Win7.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                if (list.size > 200) {
                    Text("\u2026 dan " + (list.size - 200) + " lainnya", color = Win7.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (msg.isNotEmpty()) {
            Text(msg, color = if (ok) Color(0xFF4CAF50) else Color(0xFFFF8A80), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Win7Button("Pulihkan", isDefault = true, enabled = chosen.isNotEmpty() && !busy) {
                act(true) { "Dipulihkan: " + TrashBin.restore(chosen, volumes).size + " item" }
            }
            Win7Button("Hapus permanen", enabled = chosen.isNotEmpty() && !busy) { confirm = "purge" }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Win7Button("Kosongkan", enabled = !list.isNullOrEmpty() && !busy) { confirm = "empty" }
            Win7Button("Tutup", onClick = onDismiss)
        }
    }

    confirm?.let { kind ->
        val isEmpty = kind == "empty"
        val targets = if (isEmpty) list.orEmpty() else chosen
        W7Panel(if (isEmpty) "Kosongkan Recycle Bin" else "Hapus permanen", { confirm = null }) {
            Text(
                if (isEmpty) "Hapus permanen semua " + targets.size + " item di Recycle Bin?"
                else "Hapus permanen " + targets.size + " item terpilih?",
                color = Win7.Ink, fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            Text("Tidak dapat dibatalkan.", color = Color(0xFFFF8A80), fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Win7Button("Hapus permanen", isDefault = true) {
                    confirm = null
                    if (isEmpty) act(false) { "Recycle Bin dikosongkan: " + TrashBin.empty(volumes) + " entri" }
                    else act(false) { "Dihapus permanen: " + TrashBin.purge(targets) + " item" }
                }
                Spacer(Modifier.width(8.dp))
                Win7Button("Batal") { confirm = null }
            }
        }
    }
}

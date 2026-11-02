package com.molina.studio.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.studio.cinema.Win7
import com.molina.studio.cinema.Win7Button
import com.molina.studio.settings.Win7GlassWindow
import com.molina.terminal.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Salinan baris layar (riwayat + layar) saat dialog dibuka. [firstRow] = indeks baris pertama (negatif). */
private class RowSnapshot(val firstRow: Int, val rows: List<String>)

/** Ambil teks tiap baris layar di main thread (buffer emulator tidak thread-safe). Null saat layar penuh aktif. */
private fun snapshotRows(): RowSnapshot? {
    val em = TerminalSessions.currentSession?.emulator ?: return null
    if (em.isAlternateBufferActive) return null
    val screen = em.screen ?: return null
    val cols = em.mColumns
    val first = -screen.activeTranscriptRows
    val rows = ArrayList<String>(-first + em.mRows)
    for (r in first until em.mRows) rows.add(screen.getSelectedText(0, r, cols, r))
    return RowSnapshot(first, rows)
}

/**
 * Dialog "Cari di scrollback": mencari per baris layar. "Lompat" menggulir Terminal ke baris itu dan menyorotnya
 * sampai pengguna mengetuk, mengetik, atau menggulir. "Salin"/"Sisip" mengambil baris cuplikan.
 */
@Composable
internal fun TerminalSearchDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val snap = remember { snapshotRows() }
    val view: TerminalView? = TerminalSessions.client.view
    var query by remember { mutableStateOf("") }
    val hits by produceState<List<TerminalSearch.Hit>>(emptyList(), query) {
        val s = snap ?: return@produceState
        val q = query
        value = withContext(Dispatchers.Default) { TerminalSearch.findRows(s.rows, s.firstRow, q) }
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Win7.Ink,
        unfocusedTextColor = Win7.Ink,
        focusedBorderColor = Win7.DefaultLine,
        unfocusedBorderColor = Win7.Line,
        cursorColor = Win7.DefaultLine,
        focusedPlaceholderColor = Win7.TextDim,
        unfocusedPlaceholderColor = Win7.TextDim,
        focusedContainerColor = Win7.Input,
        unfocusedContainerColor = Win7.Input
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Win7GlassWindow(
                title = "Cari di scrollback",
                icon = { Text("\u2315", color = Color.White, fontSize = 12.sp) }
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(TerminalSearch.MAX_QUERY) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(3.dp),
                    placeholder = { Text("Cari teks", fontSize = 13.sp) }, colors = fieldColors
                )
                when {
                    snap == null -> Text(
                        "Pencarian tidak tersedia saat program layar penuh (vim, htop) aktif.",
                        color = Win7.TextDim, fontSize = 12.sp
                    )
                    query.isBlank() -> Text(
                        "Ketik untuk mencari di riwayat dan layar. Tidak membedakan huruf besar-kecil.",
                        color = Win7.TextDim, fontSize = 12.sp
                    )
                    hits.isEmpty() -> Text("Tidak ada hasil.", color = Win7.TextDim, fontSize = 12.sp)
                    else -> {
                        val cap = if (hits.size >= TerminalSearch.MAX_HITS) " (dibatasi ${TerminalSearch.MAX_HITS})" else ""
                        Text("${hits.size} baris cocok$cap, terbaru di atas.", color = Win7.TextDim, fontSize = 12.sp)
                        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            hits.forEach { h -> HitRow(ctx, h, view, onDismiss) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Win7Button("Tutup", onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun HitRow(ctx: Context, h: TerminalSearch.Hit, view: TerminalView?, onDismiss: () -> Unit) {
    val line = h.snippet.trim()
    val where = if (h.row < 0) "riwayat ${-h.row} baris ke atas" else "layar baris ${h.row + 1}"
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(where, color = Win7.TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(
            h.snippet, color = Win7.Ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.End) {
            SearchBtn("Lompat") {
                if (view == null) Toast.makeText(ctx, "Terminal belum siap", Toast.LENGTH_SHORT).show()
                else { view.showSearchHit(h.row, h.col1, h.col2); onDismiss() }
            }
            SearchBtn("Salin") {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("molina-terminal-search", line))
                Toast.makeText(ctx, "Baris disalin", Toast.LENGTH_SHORT).show()
            }
            SearchBtn("Sisip") { TerminalActions.insert(line); onDismiss() }
        }
    }
}

@Composable
private fun SearchBtn(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier.padding(start = 4.dp).height(28.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(Win7.BtnTop, Win7.BtnBottom)), shape)
            .border(1.dp, Win7.BtnLine, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        Alignment.Center
    ) { Text(label, color = Win7.Ink, fontSize = 12.sp) }
}

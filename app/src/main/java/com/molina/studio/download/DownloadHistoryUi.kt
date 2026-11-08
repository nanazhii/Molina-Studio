package com.molina.studio.download

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.core.common.DownloadHistoryEntry
import com.molina.core.common.LogRedact
import com.molina.studio.ui.MolinaTheme
import com.molina.studio.settings.W7D
import com.molina.studio.settings.win7Card
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bagian Riwayat di bawah tab Unduhan: daftar sesi lama (terbaru di atas), ketuk kartu untuk membuka rincian,
 * "Ulangi yang gagal" memulai sesi baru dengan mode/kualitas ASLI sesi itu. Kosong -> tidak menampilkan apa pun.
 */
internal fun LazyListScope.historySection(
    entries: List<DownloadHistoryEntry>,
    running: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    openAt: Long?,
    onToggleEntry: (Long) -> Unit,
    onRetry: (DownloadHistoryEntry) -> Unit,
    onDelete: (DownloadHistoryEntry) -> Unit,
    onClearAll: () -> Unit,
) {
    if (entries.isEmpty()) return
    item(key = "history-header") {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onToggle) {
                Text(
                    "Riwayat (${entries.size}) " + if (open) "▲" else "▼",
                    color = W7D.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                )
            }
            if (open) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClearAll) { Text("Hapus semua", color = W7D.TextDim) }
                }
            }
        }
    }
    if (open) {
        items(entries, key = { "h-${it.at}" }) { e ->
            HistoryCard(
                e = e,
                expanded = openAt == e.at,
                running = running,
                onToggle = { onToggleEntry(e.at) },
                onRetry = { onRetry(e) },
                onDelete = { onDelete(e) },
            )
        }
    }
}

@Composable
private fun HistoryCard(
    e: DownloadHistoryEntry,
    expanded: Boolean,
    running: Boolean,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val retryN = e.retryUrls().size
    Column(
        Modifier.fillMaxWidth()
            .win7Card(if (e.failedCount > 0) MolinaTheme.Warn else MolinaTheme.Good)
            .clickable(onClick = onToggle)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            fmtDate(e.at) + " · " + e.modeLabel(),
            color = W7D.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        )
        val status = buildString {
            append("${e.okCount} berhasil, ${e.failedCount} gagal")
            if (e.cancelled) append(" · dibatalkan")
        }
        Text(status, color = if (e.failedCount > 0) MolinaTheme.Warn else MolinaTheme.Good, fontSize = 12.sp)
        if (expanded) {
            e.fatal?.let { Text(it, color = MolinaTheme.Bad, fontSize = 12.sp) }
            e.items.forEach { item ->
                val name = (item.title ?: LogRedact.target(item.url)).ifEmpty { "(tanpa judul)" }.take(70)
                Text(
                    (if (item.ok) "✓ " else "✗ ") + name,
                    color = if (item.ok) W7D.Ink else MolinaTheme.Bad,
                    fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (!item.ok) item.error?.let { msg ->
                    Text(msg, color = W7D.TextDim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (retryN > 0) {
                    OutlinedButton(
                        onClick = onRetry,
                        enabled = !running,
                        border = BorderStroke(1.dp, W7D.Accent),
                    ) { Text("Ulangi yang gagal ($retryN)", color = W7D.Accent) }
                }
                TextButton(onClick = onDelete) { Text("Hapus", color = W7D.TextDim) }
            }
        }
    }
}

@Composable
internal fun ClearHistoryDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hapus semua riwayat?") },
        text = { Text("Daftar sesi unduhan dan URL yang gagal akan dihapus dari perangkat. Berkas hasil unduhan tidak ikut terhapus.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Hapus", color = MolinaTheme.Bad) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal", color = W7D.TextDim) } },
    )
}

private fun fmtDate(ms: Long): String =
    SimpleDateFormat("d MMM HH:mm", Locale.forLanguageTag("id-ID")).format(Date(ms))

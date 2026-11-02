package com.molina.code.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val INFO_MAX_CHARS = 3_000_000
private val ID_LOCALE = Locale("id", "ID")

/** [CODE-INFO] Hasil hitung teks tab aktif. Karakter = satuan UTF-16 (sama dengan bilah status). */
internal class TextStats(val lines: Int, val words: Int, val chars: Int, val charsNoSpace: Int, val bytes: Long)

private class StatsResult(val stats: TextStats?, val selection: TextStats?)

/** Satu putaran: baris (jumlah LF + 1), kata (rangkaian non-spasi), karakter, tanpa spasi, dan byte UTF-8. */
internal fun computeTextStats(text: String): TextStats {
    var lines = 1
    var words = 0
    var noSpace = 0
    var bytes = 0L
    var inWord = false
    for (c in text) {
        if (c == '\n') lines++
        if (c.isWhitespace()) {
            inWord = false
        } else {
            noSpace++
            if (!inWord) { words++; inWord = true }
        }
        bytes += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() -> 4
            c.isLowSurrogate() -> 0
            else -> 3
        }
    }
    return TextStats(lines, words, text.length, noSpace, bytes)
}

private fun fmtNum(n: Number): String = NumberFormat.getIntegerInstance(ID_LOCALE).format(n)

/** [INFO-READ] Perkiraan waktu baca dengan kecepatan 200 kata per menit. */
private fun readTime(words: Int): String = when {
    words == 0 -> "-"
    words < 200 -> "kurang dari 1 menit"
    else -> "\u00B1" + ((words + 199) / 200) + " menit"
}

private fun fmtSize(b: Long): String = when {
    b < 1024L -> fmtNum(b) + " B"
    b < 1024L * 1024L -> String.format(ID_LOCALE, "%.1f KB", b / 1024.0)
    else -> String.format(ID_LOCALE, "%.2f MB", b / 1048576.0)
}

/**
 * [CODE-INFO] Layar penuh Informasi berkas, dibuka dari menu titik tiga: statistik teks (baris, kata,
 * karakter, byte), ekstensi dan jenis berkas, lokasi, status simpan, dan pengaturan editor tab aktif.
 */
@Composable
internal fun FileInfoScreen(tab: EditorTab, onDismiss: () -> Unit) {
    val name = tab.file?.name ?: tab.title
    val ext = name.substringAfterLast('.', "").lowercase()
    val result by produceState<StatsResult?>(null, tab.id, tab.revision) {
        val t = tab.currentTextOrNull(INFO_MAX_CHARS)
        // [INFO-READ] teks yang sedang diseleksi (bila ada dan tidak terlalu besar)
        val sel = runCatching {
            tab.editor?.let { e ->
                val c = e.cursor
                if (c.isSelected && c.right - c.left <= INFO_MAX_CHARS) e.text.substring(c.left, c.right) else null
            }
        }.getOrNull()
        value = withContext(Dispatchers.Default) {
            StatsResult(t?.let { computeTextStats(it) }, sel?.let { computeTextStats(it) })
        }
    }
    val cursor = runCatching { tab.editor?.cursor?.let { "Ln ${it.leftLine + 1}, Kol ${it.leftColumn + 1}" } }.getOrNull() ?: "-"
    val onDisk = remember(tab.file, tab.dirty, tab.loading) { tab.file?.takeIf { it.exists() } }
    val state = when {
        tab.loading -> "Memuat"
        tab.dirty -> "Diubah (belum disimpan)"
        tab.file != null -> "Tersimpan"
        else -> "Baru (belum disimpan)"
    }
    val st = result?.stats
    fun sv(f: (TextStats) -> String): String = when {
        result == null -> "Menghitung\u2026"
        st == null -> "Berkas terlalu besar untuk dihitung"
        else -> f(st)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(CodeColors.EditorBg)) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).win7Toolbar().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(48.dp).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(24.dp)) {
                        val p = Path().apply {
                            moveTo(size.width * 0.65f, size.height * 0.2f)
                            lineTo(size.width * 0.3f, size.height * 0.5f)
                            lineTo(size.width * 0.65f, size.height * 0.8f)
                        }
                        drawPath(p, CodeColors.MenuText, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                Column(Modifier.weight(1f)) {
                    BasicText("Informasi berkas", style = TextStyle(color = CodeColors.MenuText, fontSize = 17.sp))
                    BasicText(
                        name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 12.sp)
                    )
                }
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                InfoSection("Statistik teks")
                InfoRow("Baris", sv { fmtNum(it.lines) }, CodeColors.InfoBlue, true)
                InfoRow("Kata", sv { fmtNum(it.words) }, CodeColors.InfoBlue, true)
                InfoRow("Karakter", sv { fmtNum(it.chars) }, CodeColors.InfoBlue, true)
                InfoRow("Tanpa spasi", sv { fmtNum(it.charsNoSpace) }, CodeColors.InfoBlue, true)
                InfoRow("Ukuran teks", sv { fmtSize(it.bytes) + " (UTF-8)" }, CodeColors.InfoBlue, true)
                InfoRow("Waktu baca", sv { readTime(it.words) }, CodeColors.InfoBlue, true)
                InfoRow("Posisi kursor", cursor, CodeColors.InfoBlue, true)
                result?.selection?.let {
                    InfoRow("Seleksi", "${fmtNum(it.chars)} karakter, ${fmtNum(it.words)} kata", CodeColors.InfoBlue, true)
                }

                InfoSection("Jenis berkas")
                InfoRow("Nama", name)
                InfoRow("Ekstensi", if (ext.isEmpty()) "tanpa ekstensi" else ".$ext")
                InfoRow("Jenis", languageOf(name))
                InfoRow("Lokasi", tab.file?.parent ?: "belum disimpan")
                InfoRow("Ukuran di disk", onDisk?.let { fmtSize(it.length()) } ?: "-")
                InfoRow(
                    "Diubah terakhir",
                    onDisk?.let { SimpleDateFormat("dd MMM yyyy HH:mm", ID_LOCALE).format(Date(it.lastModified())) } ?: "-"
                )
                InfoRow("Status", state)

                InfoSection("Editor")
                InfoRow("Encoding", "UTF-8")
                InfoRow("Akhir baris", tab.eol.label)
                InfoRow("Pembungkus kata", if (tab.wordWrap) "Aktif" else "Nonaktif")
                InfoRow("Hanya baca", if (tab.readOnly) "Ya" else "Tidak")
            }
        }
    }
}

@Composable
private fun InfoSection(title: String) {
    BasicText(
        title, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        style = TextStyle(color = CodeColors.Gold, fontSize = 13.sp)
    )
    Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = CodeColors.MenuText, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        BasicText(
            label, modifier = Modifier.width(132.dp),
            style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 13.sp)
        )
        BasicText(
            value, modifier = Modifier.weight(1f),
            style = TextStyle(color = valueColor, fontSize = 14.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
        )
    }
}

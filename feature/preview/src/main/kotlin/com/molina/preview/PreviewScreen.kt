package com.molina.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.preview.engine.PreviewKind
import com.molina.preview.engine.PreviewKinds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Batas pratinjau; batas pratinjau yang sama seperti sebelumnya. */
object PreviewLimits {
    const val MAX_FILE_BYTES = 2_000_000L
    const val MAX_CHARS = 500_000
    const val POLL_MS = 1000L
}

/**
 * [NANO-PREVIEW] Pratinjau layar penuh untuk satu berkas Markdown/HTML/JSON. Mandiri: tidak butuh editor.
 * Dimuat ulang otomatis saat berkas berubah (mis. setelah Ctrl+S di nano). [htmlWebView]: HTML lewat WebView
 * (tanpa JavaScript/jaringan/akses berkas); bila mati dipakai renderer native.
 */
@Composable
fun PreviewScreen(file: File, htmlWebView: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var content by remember(file) { mutableStateOf<PreviewContent>(PreviewContent.Empty) }
    var label by remember(file) { mutableStateOf("") }
    LaunchedEffect(file, htmlWebView) {
        var last = Long.MIN_VALUE
        while (true) {
            val stamp = withContext(Dispatchers.IO) { if (file.isFile) file.lastModified() * 31 + file.length() else -1L }
            if (stamp != last) {
                last = stamp
                val r = loadPreview(file, htmlWebView)
                content = r.first
                label = r.second
            }
            delay(PreviewLimits.POLL_MS)
        }
    }
    Column(modifier.fillMaxSize().background(PreviewColors.PreviewBg)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).background(PreviewColors.Surface1).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = file.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = PreviewColors.IconDefault, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            )
            if (label.isNotEmpty()) {
                BasicText(
                    text = label,
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = TextStyle(color = PreviewColors.TextSecondary, fontSize = 12.sp)
                )
            }
            BasicText(
                text = "TUTUP",
                modifier = Modifier.clickable { onClose() }.padding(horizontal = 8.dp, vertical = 12.dp),
                style = TextStyle(color = PreviewColors.Brand, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            )
        }
        Box(Modifier.fillMaxWidth().height(PreviewDimens.Hairline).background(PreviewColors.Hairline))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PreviewBody(content, onJump = { _, _ -> })
        }
    }
}

/** Baca [file] lalu bangun isi pratinjaunya; mengembalikan (isi, label jenis). */
private suspend fun loadPreview(file: File, htmlWebView: Boolean): Pair<PreviewContent, String> {
    if (!file.isFile) return Pair(PreviewContent.Message("Berkas tidak ditemukan."), "")
    if (file.length() > PreviewLimits.MAX_FILE_BYTES) return Pair(PreviewContent.Message("Berkas terlalu besar untuk pratinjau."), "")
    val text = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrNull() }
        ?: return Pair(PreviewContent.Message("Berkas tidak bisa dibaca."), "")
    if (text.length > PreviewLimits.MAX_CHARS) return Pair(PreviewContent.Message("Berkas terlalu besar untuk pratinjau."), "")
    val first = text.take(200).substringBefore('\n').trimEnd('\r')
    val kind = PreviewKinds.of(file.name, first)
    val label = when (kind) {
        PreviewKind.MARKDOWN -> "Markdown"
        PreviewKind.JSON -> "JSON"
        PreviewKind.HTML -> "HTML"
        else -> ""
    }
    if (kind != PreviewKind.MARKDOWN && kind != PreviewKind.JSON && kind != PreviewKind.HTML) {
        return Pair(PreviewContent.Message("Tidak ada pratinjau untuk tipe berkas ini (hanya Markdown, HTML, dan JSON)."), "")
    }
    if (kind == PreviewKind.HTML && htmlWebView) return Pair(PreviewContent.Html(text), label)
    val built = withContext(Dispatchers.Default) { PreviewRender.build(kind, text) }
    return Pair(built, label)
}

package com.molina.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.sp

// [NANO-PREVIEW] Tampilan Markdown/JSON/HTML, disalin dari PreviewPane (tanpa tombol Run/keluaran).

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(PreviewDimens.PanePadding), contentAlignment = Alignment.Center) {
        BasicText(
            text = text,
            style = TextStyle(color = PreviewColors.TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        )
    }
}

// ------------------------------------------------------------------ Pratinjau

@Composable
internal fun PreviewBody(content: PreviewContent, onJump: (Int, Int) -> Unit, anchor: () -> Int = { -1 }) {
    when (content) {
        PreviewContent.Empty -> Unit
        is PreviewContent.Message -> CenterMessage(content.text)
        is PreviewContent.Markdown -> MarkdownBody(content.blocks, content.startLines, anchor)
        is PreviewContent.Html -> WebViewBody(content.source)
        is PreviewContent.Json -> MonoBody(content.text)
        is PreviewContent.JsonError -> JsonErrorBody(content, onJump)
    }
}

@Composable
private fun MarkdownBody(blocks: List<MdUi>, startLines: List<Int>, anchor: () -> Int) {
    val uri = LocalUriHandler.current
    val state = rememberLazyListState()
    // Sekali per pembukaan: gulir ke blok tempat kursor editor berada.
    LaunchedEffect(blocks) {
        if (blocks.isEmpty()) return@LaunchedEffect
        val line = anchor()
        if (line < 0) return@LaunchedEffect
        val idx = startLines.indexOfLast { it <= line }
        if (idx > 0) state.scrollToItem(idx)
    }
    if (blocks.isEmpty()) {
        CenterMessage("Dokumen kosong.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(PreviewDimens.PanePadding)
    ) {
        items(blocks.size) { i -> MdBlockView(blocks[i], uri) }
    }
}

private fun bodyStyle(bold: Boolean = false) = TextStyle(
    color = PreviewColors.PreviewText,
    fontSize = PreviewDimens.PaneBodySize,
    fontWeight = if (bold) FontWeight.Bold else null
)

@Composable
private fun MdBlockView(b: MdUi, uri: UriHandler) {
    when (b) {
        is MdUi.Heading -> {
            val size = when (b.level) {
                1 -> 24.sp
                2 -> 20.sp
                3 -> 17.sp
                else -> 15.sp
            }
            Column(Modifier.fillMaxWidth().padding(top = if (b.level <= 2) 14.dp else 10.dp, bottom = 6.dp)) {
                LinkText(
                    b.text,
                    TextStyle(color = PreviewColors.PreviewHeading, fontSize = size, fontWeight = FontWeight.Bold),
                    uri
                )
                if (b.level <= 2) {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 4.dp).height(PreviewDimens.Hairline)
                            .background(PreviewColors.PreviewHairline)
                    )
                }
            }
        }
        is MdUi.Para -> LinkText(b.text, bodyStyle(), uri, Modifier.padding(vertical = 5.dp))
        is MdUi.Code -> {
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .background(PreviewColors.PreviewCodeBg, shape)
                    .border(PreviewDimens.Hairline, PreviewColors.PreviewHairline, shape)
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                BasicText(
                    text = b.text,
                    softWrap = false,
                    style = TextStyle(
                        color = PreviewColors.PreviewText,
                        fontSize = PreviewDimens.PaneMonoSize,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
        is MdUi.Quote -> Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .background(PreviewColors.PreviewQuoteBg)
                .height(IntrinsicSize.Min)
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(PreviewColors.PreviewQuoteBar))
            LinkText(
                b.text,
                TextStyle(color = PreviewColors.PreviewQuoteText, fontSize = PreviewDimens.PaneBodySize),
                uri,
                Modifier.padding(10.dp)
            )
        }
        is MdUi.Item -> Row(
            Modifier.fillMaxWidth().padding(start = (b.depth * 16).dp, top = 2.dp, bottom = 2.dp)
        ) {
            val marker = when (b.checked) {
                true -> "\u2611"
                false -> "\u2610"
                null -> b.marker
            }
            BasicText(
                text = marker,
                modifier = Modifier.widthIn(min = 24.dp),
                style = TextStyle(color = PreviewColors.IconDefault, fontSize = PreviewDimens.PaneBodySize)
            )
            LinkText(b.text, bodyStyle(), uri, Modifier.weight(1f))
        }
        MdUi.Rule -> Box(
            Modifier.fillMaxWidth().padding(vertical = 10.dp).height(PreviewDimens.Hairline)
                .background(PreviewColors.PreviewHairline)
        )
        is MdUi.Table -> Column(
            Modifier
                .padding(vertical = 6.dp)
                .horizontalScroll(rememberScrollState())
                .border(PreviewDimens.Hairline, PreviewColors.PreviewHairline)
        ) {
            TableRowView(b.header, true, uri)
            b.rows.forEach { TableRowView(it, false, uri) }
        }
    }
}

@Composable
private fun TableRowView(cells: List<AnnotatedString>, header: Boolean, uri: UriHandler) {
    Row(
        Modifier
            .height(IntrinsicSize.Min)
            .background(if (header) PreviewColors.PreviewTableHeadBg else Color.Transparent)
    ) {
        cells.forEach { c ->
            Box(
                Modifier
                    .width(PreviewDimens.PaneTableCellWidth)
                    .fillMaxHeight()
                    .border(PreviewDimens.Hairline, PreviewColors.PreviewHairline)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                LinkText(c, bodyStyle(bold = header), uri)
            }
        }
    }
}

/** Teks dengan tautan yang bisa diketuk; teks tanpa tautan memakai BasicText biasa. */
@Composable
private fun LinkText(text: AnnotatedString, style: TextStyle, uri: UriHandler, modifier: Modifier = Modifier) {
    val hasLink = remember(text) { text.getStringAnnotations(LINK_TAG, 0, text.length).isNotEmpty() }
    if (!hasLink) {
        BasicText(text, modifier, style)
        return
    }
    ClickableText(text, modifier, style) { offset ->
        text.getStringAnnotations(LINK_TAG, offset, offset).firstOrNull()?.let { openLink(uri, it.item) }
    }
}

/** Hanya skema web/email yang dibuka; tautan relatif atau skema lain diabaikan. */
private fun openLink(uri: UriHandler, url: String) {
    val ok = url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")
    if (ok) runCatching { uri.openUri(url) }
}

@Composable
private fun MonoBody(text: AnnotatedString) {
    val v = rememberScrollState()
    val h = rememberScrollState()
    Box(
        Modifier.fillMaxSize().verticalScroll(v).horizontalScroll(h).padding(PreviewDimens.PanePadding)
    ) {
        BasicText(
            text = text,
            softWrap = false,
            style = TextStyle(
                color = PreviewColors.PreviewText,
                fontSize = PreviewDimens.PaneMonoSize,
                fontFamily = FontFamily.Monospace
            )
        )
    }
}

@Composable
private fun JsonErrorBody(e: PreviewContent.JsonError, onJump: (Int, Int) -> Unit) {
    val caretAt = (e.column - 1).coerceIn(0, e.snippet.length)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PreviewDimens.PanePadding)) {
        BasicText(
            text = "JSON tidak valid",
            style = TextStyle(color = PreviewColors.Danger, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.height(6.dp))
        BasicText(text = e.message, style = bodyStyle())
        Spacer(Modifier.height(10.dp))
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            BasicText(
                text = e.snippet + "\n" + " ".repeat(caretAt) + "^",
                softWrap = false,
                style = TextStyle(
                    color = PreviewColors.PreviewText,
                    fontSize = PreviewDimens.PaneMonoSize,
                    fontFamily = FontFamily.Monospace
                )
            )
        }
        Spacer(Modifier.height(10.dp))
        BasicText(
            text = "Lompat ke baris ${e.line}, kolom ${e.column}",
            modifier = Modifier.clickable { onJump(e.line, e.column) }.padding(vertical = 8.dp),
            style = TextStyle(color = PreviewColors.Brand, fontSize = 13.sp)
        )
    }
}

/**
 * Pratinjau HTML penuh lewat WebView, hanya bila opsi di Settings menyala. Dikunci rapat:
 * tanpa JavaScript, tanpa jaringan, tanpa akses berkas/konten, tanpa DOM storage. Setiap perpindahan
 * halaman dicegat; hanya tautan web/email yang diteruskan ke peramban lewat pengendali URI.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewBody(html: String) {
    val uri = LocalUriHandler.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.blockNetworkLoads = true
                setBackgroundColor(android.graphics.Color.WHITE)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        openLink(uri, request.url.toString())
                        return true
                    }
                }
            }
        },
        update = { wv ->
            if (wv.tag != html) {
                wv.tag = html
                wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { wv -> wv.destroy() }
    )
}


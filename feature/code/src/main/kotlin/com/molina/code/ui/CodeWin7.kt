package com.molina.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Token + helper tampilan Windows 7 Aero untuk Code Editor, nilainya sama dengan header Terminal
 * (TerminalChrome) dan Win7Glass di :app agar serasi. Area editor tetap hitam pekat.
 */
internal object Win7Code {
    val AeroTop = Color(0xFF4A4A4A)
    val AeroMid = Color(0xFF262626)
    val AeroBottom = Color(0xFF111111)
    val Ink = Color(0xFFEEEEEE)
    val InkDim = Color(0xFFC8C8C8)
    val RailBlue = Color(0x998FC4EE)
    val TabLine = Color(0xFF8FC4EE)
    val StatusHeight = 22.dp
    val TabBarBrush = Brush.verticalGradient(listOf(Color(0xFF1B1B1B), Color(0xFF0A0A0A)))
    val TabActiveBrush = Brush.verticalGradient(listOf(Color(0xFF3A3A3A), Color(0xFF111111), Color.Black))
    val ToolbarBrush = Brush.verticalGradient(listOf(Color(0xFF202020), Color(0xFF111111)))
    val StatusBrush = Brush.verticalGradient(listOf(Color(0xFF1B1B1B), Color(0xFF0A0A0A)))
}

/** Kaca Aero: gradasi biru, kilap 50% atas, garis terang di tepi bawah. */
internal fun Modifier.win7Aero(): Modifier = this
    .background(Brush.verticalGradient(listOf(Win7Code.AeroTop, Win7Code.AeroMid, Win7Code.AeroBottom)))
    .drawBehind {
        drawRect(Color.White.copy(alpha = 0.10f), Offset.Zero, Size(size.width, size.height * 0.5f))
        drawLine(Color(0x99D0D0D0), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
    }

internal fun Modifier.win7Toolbar(): Modifier = this.background(Win7Code.ToolbarBrush)

internal fun Modifier.win7Tab(active: Boolean): Modifier =
    this.background(if (active) Win7Code.TabActiveBrush else Win7Code.TabBarBrush)

internal fun languageOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> "Kotlin"
    "java" -> "Java"
    "py" -> "Python"
    "sh", "bash" -> "Shell"
    "md" -> "Markdown"
    "json" -> "JSON"
    "xml" -> "XML"
    "html", "htm" -> "HTML"
    "css" -> "CSS"
    "js" -> "JavaScript"
    "ts" -> "TypeScript"
    "yml", "yaml" -> "YAML"
    "gradle" -> "Gradle"
    "c", "h" -> "C"
    "cpp", "cc", "hpp" -> "C++"
    else -> "Teks biasa"
}

/** Bilah status bawah editor: Ln/Kol, jumlah baris, bahasa, UTF-8, dan status simpan. */
@Composable
internal fun CodeStatusBar(tab: EditorTab, live: LiveBadge? = null, library: Boolean = false) { // [CODE-LSP3] [CODE-DEBLIB]
    var pos by remember(tab.id) { mutableStateOf("Ln 1, Kol 1") }
    var lines by remember(tab.id) { mutableStateOf(1) }
    var chars by remember(tab.id) { mutableStateOf(0) } // [CODE-STATUS]
    LaunchedEffect(tab.id) {
        while (true) {
            val ed = tab.editor
            if (ed != null) {
                try {
                    val p = "Ln " + (ed.cursor.leftLine + 1) + ", Kol " + (ed.cursor.leftColumn + 1)
                    if (p != pos) pos = p
                    val n = ed.text.lineCount
                    if (n != lines) lines = n
                    val c = ed.text.length
                    if (c != chars) chars = c
                } catch (e: Exception) {
                    // editor sedang dibuat ulang; coba lagi pada putaran berikut
                }
            }
            delay(250)
        }
    }
    val state: String
    val stateColor: Color
    when {
        tab.loading -> { state = "Memuat"; stateColor = Win7Code.InkDim }
        library -> { state = "Debian - hanya baca"; stateColor = CodeColors.GoldSoft } // [CODE-DEBLIB]
        tab.dirty -> { state = "Diubah"; stateColor = CodeColors.Danger }
        tab.file != null -> { state = "Tersimpan"; stateColor = CodeColors.Clean }
        else -> { state = "Baru"; stateColor = Win7Code.InkDim }
    }
    val style = TextStyle(color = Win7Code.InkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    val infoStyle = style.copy(color = CodeColors.InfoBlue) // [STATUS-BLUE]
    Row(
        Modifier
            .fillMaxWidth()
            .height(Win7Code.StatusHeight)
            .background(Win7Code.StatusBrush)
            .drawBehind { drawLine(Color(0x663C7FB1), Offset(0f, 0f), Offset(size.width, 0f), 1f) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(pos, style = infoStyle, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        BasicText("$lines baris, $chars karakter", style = infoStyle, maxLines = 1)
        Spacer(Modifier.weight(1f))
        BasicText(languageOf(tab.file?.name ?: tab.title), style = style, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        if (live != null) { // [CODE-LSP3] menggantikan "UTF-8" agar muat di layar sempit
            Box(Modifier.size(6.dp).background(live.color, CircleShape))
            Spacer(Modifier.width(4.dp))
            BasicText(live.label, style = TextStyle(color = live.color, fontSize = 11.sp, fontFamily = FontFamily.Monospace), maxLines = 1)
        } else {
            BasicText("UTF-8", style = style, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(6.dp).background(stateColor, CircleShape))
        Spacer(Modifier.width(4.dp))
        BasicText(state, style = TextStyle(color = stateColor, fontSize = 11.sp), maxLines = 1)
    }
}

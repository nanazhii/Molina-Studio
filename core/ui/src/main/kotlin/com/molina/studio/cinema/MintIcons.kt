package com.molina.studio.cinema

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/** [MINT-ICONS] Palet ikon gaya Linux Mint (Mint-Y): folder hijau mint, halaman abu terang; kontras nyaman di dark mode. */
object MintPalette {
    val FolderBack = Color(0xFF5FA06F)
    val FolderTop = Color(0xFFA8E0B3)
    val FolderBottom = Color(0xFF7FC48F)
    val FolderShade = Color(0xFF4A8A5B)
    val PageTop = Color(0xFFF4F6F7)
    val PageBottom = Color(0xFFDDE3E6)
    val PageEdge = Color(0xFF8E9AA2)
    val Fold = Color(0xFFC3CCD1)
    val Hill = Color(0xFF5FA06F)
    val Sun = Color(0xFFFFC107)
    val Strip = Color(0xFF455A64)
    val Line = Color(0xFFB8C0C6)
}

/** [MINT-ICONS] Folder semi-flat: tab di kiri atas, badan bergradasi hijau mint, sorot tipis di atas, bayangan tipis di bawah. */
@Composable
fun MintFolder(box: Dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        val h = size.height
        drawRoundRect(MintPalette.FolderBack, Offset(w * 0.06f, h * 0.18f), Size(w * 0.38f, h * 0.20f), CornerRadius(w * 0.06f))
        drawRoundRect(MintPalette.FolderBack, Offset(w * 0.06f, h * 0.28f), Size(w * 0.88f, h * 0.58f), CornerRadius(w * 0.07f))
        val fo = Offset(w * 0.06f, h * 0.36f)
        val fs = Size(w * 0.88f, h * 0.50f)
        drawRoundRect(Brush.verticalGradient(listOf(MintPalette.FolderTop, MintPalette.FolderBottom)), fo, fs, CornerRadius(w * 0.07f))
        drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(w * 0.10f, h * 0.385f), Size(w * 0.80f, h * 0.035f), CornerRadius(w * 0.02f))
        drawRoundRect(MintPalette.FolderShade.copy(alpha = 0.55f), Offset(w * 0.06f, h * 0.82f), Size(w * 0.88f, h * 0.04f), CornerRadius(w * 0.02f))
    }
}

/** [MINT-ICONS] Berkas gaya Mint-Y: halaman terang bersudut lipat, simbol per jenis, pita warna + label untuk dokumen/kode. */
@Composable
fun MintFileIcon(ext: String, box: Dp) {
    val g = remember(ext) { W7Glyph.of(ext) }
    val ink = if (g.color.luminance() > 0.6f) Color(0xFF263238) else Color.White
    Box(Modifier.size(box)) {
        Box(Modifier.fillMaxSize().drawWithCache {
            val w = size.width
            val h = size.height
            val edge = Stroke(width = (w * 0.04f).coerceAtLeast(1f))
            val page = Path().apply {
                moveTo(w * 0.20f, h * 0.08f); lineTo(w * 0.60f, h * 0.08f); lineTo(w * 0.82f, h * 0.30f)
                lineTo(w * 0.82f, h * 0.92f); lineTo(w * 0.20f, h * 0.92f); close()
            }
            val fold = Path().apply {
                moveTo(w * 0.60f, h * 0.08f); lineTo(w * 0.60f, h * 0.30f); lineTo(w * 0.82f, h * 0.30f); close()
            }
            val hills = Path().apply {
                moveTo(w * 0.20f, h * 0.92f); lineTo(w * 0.20f, h * 0.74f); lineTo(w * 0.40f, h * 0.52f)
                lineTo(w * 0.58f, h * 0.74f); lineTo(w * 0.68f, h * 0.64f); lineTo(w * 0.82f, h * 0.78f)
                lineTo(w * 0.82f, h * 0.92f); close()
            }
            val tri = Path().apply {
                moveTo(w * 0.44f, h * 0.47f); lineTo(w * 0.44f, h * 0.71f); lineTo(w * 0.64f, h * 0.59f); close()
            }
            val body = Brush.verticalGradient(listOf(MintPalette.PageTop, MintPalette.PageBottom))
            onDrawBehind {
                drawPath(page, body)
                drawPath(fold, MintPalette.Fold)
                when (g.shape) {
                    W7Shape.IMAGE -> {
                        drawCircle(MintPalette.Sun, w * 0.06f, Offset(w * 0.40f, h * 0.38f))
                        drawPath(hills, MintPalette.Hill)
                    }
                    W7Shape.VIDEO -> {
                        drawRect(MintPalette.Strip, Offset(w * 0.20f, h * 0.40f), Size(w * 0.62f, h * 0.38f))
                        drawPath(tri, g.color)
                    }
                    W7Shape.AUDIO -> {
                        drawCircle(g.color, w * 0.08f, Offset(w * 0.42f, h * 0.72f))
                        drawRect(g.color, Offset(w * 0.47f, h * 0.36f), Size(w * 0.045f, h * 0.36f))
                        drawRect(g.color, Offset(w * 0.47f, h * 0.36f), Size(w * 0.17f, h * 0.08f))
                    }
                    W7Shape.ARCHIVE -> {
                        for (i in 0 until 5) {
                            drawRect(MintPalette.Line, Offset(w * (if (i % 2 == 0) 0.45f else 0.51f), h * (0.32f + i * 0.045f)), Size(w * 0.05f, h * 0.03f))
                        }
                    }
                    W7Shape.NONE -> {
                        val rows = if (g.label == null) 3 else 2
                        for (k in 0 until rows) {
                            drawRect(MintPalette.Line, Offset(w * 0.28f, h * (0.38f + k * 0.10f)), Size(w * (0.46f - (k % 2) * 0.12f), h * 0.04f))
                        }
                    }
                }
                if (g.label != null) drawRoundRect(g.color, Offset(w * 0.20f, h * 0.58f), Size(w * 0.62f, h * 0.28f), CornerRadius(w * 0.04f))
                drawPath(page, MintPalette.PageEdge, style = edge)
                drawPath(fold, MintPalette.PageEdge, style = edge)
            }
        })
        val label = g.label
        if (label != null) {
            Box(
                Modifier.fillMaxSize().padding(top = box * 0.58f, bottom = box * 0.14f, start = box * 0.20f, end = box * 0.18f),
                Alignment.Center
            ) {
                Text(label, color = ink, fontSize = (box.value * 0.26f).coerceAtLeast(7f).sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
    }
}

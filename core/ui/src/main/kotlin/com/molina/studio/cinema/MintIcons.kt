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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.molina.core.files.FolderKind
import com.molina.core.files.FolderKinds
import com.molina.studio.ui.MolinaTheme

/** [MINT-ICONS] Palet ikon gaya Linux Mint (Mint-Y): halaman abu terang; kontras nyaman di dark mode. */
object MintPalette {
    val PageTop = Color(0xFFF4F6F7)
    val PageBottom = Color(0xFFDDE3E6)
    val PageEdge = Color(0xFF8E9AA2)
    val Fold = Color(0xFFC3CCD1)
    val Hill = Color(0xFF5FA06F)
    val Sun = Color(0xFFFFC107)
    val Strip = Color(0xFF455A64)
    val Line = Color(0xFFB8C0C6)
    val Star = Color(0xFF2F6B40)
}

/** [MINT-VARIANT] Warna folder per jenis (varian Mint-Y): hijau mint bawaan, aqua, ungu Cinema, merah muda, biru, oranye. */
class MintFolderTint(val top: Color, val bottom: Color, val back: Color, val shade: Color) {
    companion object {
        val Mint = MintFolderTint(Color(0xFFA8E0B3), Color(0xFF7FC48F), Color(0xFF5FA06F), Color(0xFF4A8A5B))
        val Aqua = MintFolderTint(Color(0xFF8FE3E0), Color(0xFF5FC9CF), Color(0xFF3FA7AE), Color(0xFF2F8A91))
        val Cinema = MintFolderTint(Color(0xFFD1B8FF), MolinaTheme.Cinema, Color(0xFF8E62D9), Color(0xFF7048B5))
        val Pink = MintFolderTint(Color(0xFFFFB3CF), Color(0xFFFF80AB), Color(0xFFD95F8A), Color(0xFFB54A72))
        val Blue = MintFolderTint(Color(0xFF9CCBFF), MolinaTheme.Link, Color(0xFF4A8FD6), Color(0xFF3A72AD))
        val Orange = MintFolderTint(Color(0xFFFFD08A), Color(0xFFFFB74D), Color(0xFFD9923A), Color(0xFFB27428))

        fun of(kind: FolderKind): MintFolderTint = when (kind) {
            FolderKind.DEFAULT -> Mint
            FolderKind.DOWNLOAD -> Aqua
            FolderKind.VIDEO -> Cinema
            FolderKind.MUSIC -> Pink
            FolderKind.DOCS -> Blue
            FolderKind.PICTURES -> Orange
        }

        /** Varian menurut nama folder (lihat [FolderKinds]). */
        fun of(name: String): MintFolderTint = of(FolderKinds.of(name))
    }
}

private fun mintPage(w: Float, h: Float) = Path().apply {
    moveTo(w * 0.20f, h * 0.08f); lineTo(w * 0.60f, h * 0.08f); lineTo(w * 0.82f, h * 0.30f)
    lineTo(w * 0.82f, h * 0.92f); lineTo(w * 0.20f, h * 0.92f); close()
}

private fun mintFold(w: Float, h: Float) = Path().apply {
    moveTo(w * 0.60f, h * 0.08f); lineTo(w * 0.60f, h * 0.30f); lineTo(w * 0.82f, h * 0.30f); close()
}

/**
 * [MINT-ICONS] Satu-satunya gambar folder Mint (semi-flat): tab kiri atas, badan bergradasi, sorot tipis.
 * [open] = muka depan miring (folder yang di-expand di tree). [star] = penanda folder akar Molina.
 * Dipakai komposabel [MintFolder] dan Canvas modul lain (mis. tree editor Code).
 */
fun DrawScope.drawMintFolder(open: Boolean = false, star: Boolean = false, tint: MintFolderTint = MintFolderTint.Mint) {
    val w = size.width
    val h = size.height
    drawRoundRect(tint.back, Offset(w * 0.06f, h * 0.18f), Size(w * 0.38f, h * 0.20f), CornerRadius(w * 0.06f))
    drawRoundRect(tint.back, Offset(w * 0.06f, h * 0.28f), Size(w * 0.88f, h * 0.58f), CornerRadius(w * 0.07f))
    val face = Brush.verticalGradient(listOf(tint.top, tint.bottom))
    if (open) {
        val front = Path().apply {
            moveTo(w * 0.14f, h * 0.40f); lineTo(w * 0.98f, h * 0.40f); lineTo(w * 0.88f, h * 0.86f); lineTo(w * 0.04f, h * 0.86f); close()
        }
        drawPath(front, face)
    } else {
        drawRoundRect(face, Offset(w * 0.06f, h * 0.36f), Size(w * 0.88f, h * 0.50f), CornerRadius(w * 0.07f))
        drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(w * 0.10f, h * 0.385f), Size(w * 0.80f, h * 0.035f), CornerRadius(w * 0.02f))
        drawRoundRect(tint.shade.copy(alpha = 0.55f), Offset(w * 0.06f, h * 0.82f), Size(w * 0.88f, h * 0.04f), CornerRadius(w * 0.02f))
    }
    if (star) {
        val sp = Path()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) w * 0.17f else w * 0.075f
            val ang = Math.toRadians(-90.0 + i * 36.0)
            val x = w * 0.5f + (rad * Math.cos(ang)).toFloat()
            val y = h * 0.62f + (rad * Math.sin(ang)).toFloat()
            if (i == 0) sp.moveTo(x, y) else sp.lineTo(x, y)
        }
        sp.close()
        drawPath(sp, MintPalette.Star)
    }
}

/** [MINT-ICONS] Berkas Mint tanpa label: halaman terang bersudut lipat + pita warna [stripe]. Untuk tree yang berwarna per ekstensi. */
fun DrawScope.drawMintFile(stripe: Color) {
    val w = size.width
    val h = size.height
    val page = mintPage(w, h)
    val fold = mintFold(w, h)
    val edge = Stroke(width = (w * 0.04f).coerceAtLeast(1f))
    drawPath(page, Brush.verticalGradient(listOf(MintPalette.PageTop, MintPalette.PageBottom)))
    drawPath(fold, MintPalette.Fold)
    drawRoundRect(stripe, Offset(w * 0.20f, h * 0.58f), Size(w * 0.62f, h * 0.22f), CornerRadius(w * 0.04f))
    drawPath(page, MintPalette.PageEdge, style = edge)
    drawPath(fold, MintPalette.PageEdge, style = edge)
}

@Composable
fun MintFolder(box: Dp, star: Boolean = false, open: Boolean = false, tint: MintFolderTint = MintFolderTint.Mint) {
    Canvas(Modifier.size(box)) { drawMintFolder(open, star, tint) }
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
            val page = mintPage(w, h)
            val fold = mintFold(w, h)
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

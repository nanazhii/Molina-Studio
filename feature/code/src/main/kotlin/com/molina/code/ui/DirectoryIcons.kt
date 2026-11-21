package com.molina.code.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.molina.core.ui.drawMintFolder

/** Ikon tombol header panel direktori dan header layar. Digambar di kisi 24 satuan. */
internal enum class DirIcon { Up, NewFile, NewFolder, Close, Folder }

// [TREE-ICON] Warna ikon berkas per ekstensi kini satu sumber: W7Glyph di :core:ui (lewat FileTreeIcon).

private fun DrawScope.u(): Float = size.minDimension / 24f

private fun DrawScope.line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, width: Float) {
    val k = u()
    drawLine(color, Offset(x1 * k, y1 * k), Offset(x2 * k, y2 * k), strokeWidth = width * k, cap = StrokeCap.Round)
}

internal fun DrawScope.drawDirIcon(kind: DirIcon, color: Color) {
    when (kind) {
        DirIcon.Up -> {
            line(12f, 19f, 12f, 5f, color, 2f)
            line(6f, 11f, 12f, 5f, color, 2f)
            line(18f, 11f, 12f, 5f, color, 2f)
        }
        DirIcon.Close -> {
            line(6f, 6f, 18f, 18f, color, 2f)
            line(18f, 6f, 6f, 18f, color, 2f)
        }
        DirIcon.NewFile -> {
            drawPageOutline(color)
            line(12f, 11f, 12f, 17f, color, 1.8f)
            line(9f, 14f, 15f, 14f, color, 1.8f)
        }
        DirIcon.NewFolder -> {
            drawFolderOutline(color)
            line(12f, 11.5f, 12f, 17f, color, 1.8f)
            line(9.2f, 14.2f, 14.8f, 14.2f, color, 1.8f)
        }
        DirIcon.Folder -> drawMintFolder() // [MINT-ICONS] ikon bersama dari :core:ui
    }
}

private fun DrawScope.pagePath(): Path {
    val k = u()
    return Path().apply {
        moveTo(6 * k, 3 * k)
        lineTo(14 * k, 3 * k)
        lineTo(19 * k, 8 * k)
        lineTo(19 * k, 21 * k)
        lineTo(6 * k, 21 * k)
        close()
    }
}

private fun DrawScope.drawPageOutline(color: Color) {
    val k = u()
    drawPath(pagePath(), color, style = Stroke(1.6f * k))
}

private fun DrawScope.drawFolderOutline(color: Color) {
    val k = u()
    val p = Path().apply {
        moveTo(2 * k, 6 * k)
        lineTo(9 * k, 6 * k)
        lineTo(11 * k, 8 * k)
        lineTo(21 * k, 8 * k)
        lineTo(21 * k, 19 * k)
        lineTo(2 * k, 19 * k)
        close()
    }
    drawPath(p, color, style = Stroke(1.6f * k))
}

/** Segitiga expander gaya Win7: kerangka abu saat tertutup, terisi saat terbuka. */
internal fun DrawScope.drawExpander(expanded: Boolean) {
    val k = size.minDimension / 10f
    if (expanded) {
        val p = Path().apply {
            moveTo(8f * k, 2f * k)
            lineTo(8f * k, 8f * k)
            lineTo(2f * k, 8f * k)
            close()
        }
        drawPath(p, CodeColors.IconDefault)
    } else {
        val p = Path().apply {
            moveTo(3f * k, 1f * k)
            lineTo(8f * k, 5f * k)
            lineTo(3f * k, 9f * k)
            close()
        }
        drawPath(p, CodeColors.IconDim, style = Stroke(1f * k))
    }
}

/** Titik tiga vertikal: tombol menu per baris tree (menggantikan tekan lama). */
internal fun DrawScope.drawMoreDots(color: Color) {
    val r = u() * 2f
    val cx = size.width / 2f
    drawCircle(color, r, Offset(cx, u() * 4f))
    drawCircle(color, r, Offset(cx, u() * 12f))
    drawCircle(color, r, Offset(cx, u() * 20f))
}

package com.molina.code.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** Ikon tombol header panel direktori dan header layar. Digambar di kisi 24 satuan. */
internal enum class DirIcon { Up, NewFile, NewFolder, Close, Folder }

/** Warna garis ikon berkas menurut ekstensi. */
internal fun extColor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> CodeColors.ExtKotlin
    "py" -> CodeColors.ExtPython
    "md", "markdown" -> CodeColors.ExtMarkdown
    "json" -> CodeColors.ExtJson
    "xml", "html" -> CodeColors.ExtXml
    "sh", "bash" -> CodeColors.ExtShell
    else -> CodeColors.ExtDefault
}

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
        DirIcon.Folder -> drawFolderIcon(false, color)
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

/** Folder kuning khas Win7: tertutup, atau terbuka (muka depan miring) untuk folder yang di-expand. */
internal fun DrawScope.drawFolderIcon(open: Boolean, color: Color) {
    val k = u()
    val back = Path().apply {
        moveTo(2 * k, 5 * k)
        lineTo(9 * k, 5 * k)
        lineTo(11 * k, 7 * k)
        lineTo(21 * k, 7 * k)
        lineTo(21 * k, 19 * k)
        lineTo(2 * k, 19 * k)
        close()
    }
    drawPath(back, color.copy(alpha = 0.70f))
    val front = Path().apply {
        if (open) {
            moveTo(5 * k, 10 * k)
            lineTo(23 * k, 10 * k)
            lineTo(20 * k, 19 * k)
            lineTo(2 * k, 19 * k)
        } else {
            moveTo(2 * k, 9 * k)
            lineTo(21 * k, 9 * k)
            lineTo(21 * k, 19 * k)
            lineTo(2 * k, 19 * k)
        }
        close()
    }
    drawPath(front, color)
}

/** Berkas: halaman bersudut lipat dengan garis warna ekstensi. */
internal fun DrawScope.drawFileIcon(stripe: Color) {
    val k = u()
    drawPath(pagePath(), CodeColors.IconDefault.copy(alpha = 0.14f))
    drawPath(pagePath(), CodeColors.IconDefault, style = Stroke(1.3f * k))
    line(14f, 3f, 14f, 8f, CodeColors.IconDefault, 1.3f)
    line(14f, 8f, 19f, 8f, CodeColors.IconDefault, 1.3f)
    drawRect(stripe, topLeft = Offset(8f * k, 14f * k), size = Size(9f * k, 3f * k))
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

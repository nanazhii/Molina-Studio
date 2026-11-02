package com.molina.studio.cinema

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import com.molina.studio.ui.MolinaTheme

// [SPLIT-MENUPARTS] Komponen menu Penjelajah yang dipakai bersama, dipecah dari Win7OpenDialog.kt:
// ikon (W7Ico, W7MenuIcon), item menu (W7MenuItem, W7ChoiceItem), bilah ikon (W7IconCmd),
// tombol kisi drop-up (W7DropButton), pemisah (W7MenuDivider), dan batas tinggi popup (w7PopupMaxHeight).

internal enum class W7Ico { PLAY, CODE, SCRIPT, COPY, CUT, PASTE, RENAME, ZIP, UNZIP, DELETE, NEWDIR, STAR, STAR_OUT, INFO, SHARE, OPEN_WITH,
    REFRESH, SORT, SELECT, SELECT_ALL, CLEAR, MORE, SEARCH, NAV } // [W7-COMPACT-PORTRAIT]

/** [W7-COMPACT-PORTRAIT] Perintah bilah berupa ikon (tanpa teks) agar hemat lebar di ponsel potret. */
@Composable
internal fun W7IconCmd(
    ico: W7Ico, label: String, enabled: Boolean, active: Boolean = false, badge: String? = null, onClick: () -> Unit
) {
    Row(
        Modifier.height(30.dp).widthIn(min = 40.dp)
            .then(if (active) Modifier.background(Win7.SelLine.copy(alpha = 0.35f)) else Modifier)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        W7MenuIcon(ico, enabled, 18.dp)
        if (badge != null) {
            Spacer(Modifier.width(3.dp))
            Text(badge, color = if (enabled) Win7.NavHeader else Win7.TextDim, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** [DROP-MENU] Tombol ikon kisi 4 kotak (senada Mode TV) di kanan bawah Penjelajah; membuka menu drop-up. */
@Composable
internal fun W7DropButton(active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.size(width = 44.dp, height = 36.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(Win7.BtnTop, Win7.BtnBottom)), shape)
            .border(1.dp, if (active) Win7.DefaultLine else Win7.BtnLine, shape)
            .semantics { contentDescription = "Menu" }
            .clickable(onClick = onClick),
        Alignment.Center
    ) {
        Canvas(Modifier.size(18.dp)) {
            val gap = 2.5.dp.toPx()
            val cell = (size.width - gap) / 2f
            for (r in 0..1) for (c in 0..1) {
                drawRoundRect(
                    color = MolinaTheme.Gold,
                    topLeft = Offset(c * (cell + gap), r * (cell + gap)),
                    size = Size(cell, cell),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
        }
    }
}

/** [POPUP-MAXH] Satu sumber batas tinggi popup Penjelajah: 70% tinggi layar (opsional dibatasi cap), agar tidak keluar layar pendek/landscape. */
internal const val W7_POPUP_MAX_FRACTION = 0.7f

@Composable
internal fun w7PopupMaxHeight(cap: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Infinity): androidx.compose.ui.unit.Dp =
    minOf(cap, (LocalConfiguration.current.screenHeightDp * W7_POPUP_MAX_FRACTION).dp)

/** [DROP-DIVIDER] Garis pemisah emas tipis antar kelompok item di menu drop-up Penjelajah. */
@Composable
internal fun W7MenuDivider() {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp).height(1.dp)
            .background(MolinaTheme.Gold.copy(alpha = 0.4f))
    )
}

@Composable
internal fun W7MenuItem(ico: W7Ico, label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        W7MenuIcon(ico, enabled)
        Spacer(Modifier.width(10.dp))
        Text(
            label, color = if (enabled) Win7.Ink else Win7.TextDim, fontSize = 13.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Ikon garis 16dp bergaya Aero untuk menu Alat (digambar di Canvas, tanpa aset). */
@Composable
internal fun W7MenuIcon(ico: W7Ico, enabled: Boolean, sz: androidx.compose.ui.unit.Dp = 16.dp) {
    val c = if (!enabled) Win7.Line else when (ico) {
        W7Ico.DELETE -> Color(0xFFE57373)
        W7Ico.STAR, W7Ico.STAR_OUT -> Color(0xFFE0A800)
        else -> Win7.NavHeader
    }
    Canvas(Modifier.size(sz)) {
        val u = size.width / 16f
        val sw = 1.4f * u
        val st = Stroke(width = sw, cap = StrokeCap.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(c, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), strokeWidth = sw, cap = StrokeCap.Round)
        fun box(x: Float, y: Float, w: Float, h: Float) =
            drawRect(c, Offset(x * u, y * u), Size(w * u, h * u), style = st)
        when (ico) {
            W7Ico.COPY -> { box(2f, 4f, 8f, 10f); box(6f, 2f, 8f, 10f) }
            W7Ico.CUT -> {
                line(4f, 2f, 12f, 11f); line(12f, 2f, 4f, 11f)
                drawCircle(c, radius = 2f * u, center = Offset(4f * u, 13f * u), style = st)
                drawCircle(c, radius = 2f * u, center = Offset(12f * u, 13f * u), style = st)
            }
            W7Ico.PASTE -> { box(3f, 3f, 10f, 11f); drawRect(c, Offset(6f * u, 1.5f * u), Size(4f * u, 3f * u)) }
            W7Ico.RENAME -> { line(8f, 3f, 8f, 13f); line(5f, 3f, 11f, 3f); line(5f, 13f, 11f, 13f) }
            W7Ico.ZIP -> {
                box(3f, 2f, 10f, 12f)
                line(8f, 2f, 8f, 5f); line(6.5f, 6f, 9.5f, 6f); line(6.5f, 8.5f, 9.5f, 8.5f); line(6.5f, 11f, 9.5f, 11f)
            }
            W7Ico.UNZIP -> { box(2f, 8f, 12f, 6f); line(8f, 1.5f, 8f, 9f); line(5.5f, 6f, 8f, 9f); line(10.5f, 6f, 8f, 9f) }
            W7Ico.DELETE -> {
                line(2.5f, 4f, 13.5f, 4f); line(6f, 2.2f, 10f, 2.2f)
                line(4f, 5f, 5f, 14f); line(12f, 5f, 11f, 14f); line(5f, 14f, 11f, 14f)
                line(7f, 6.5f, 7f, 12f); line(9f, 6.5f, 9f, 12f)
            }
            W7Ico.NEWDIR -> {
                box(2f, 5f, 12f, 8f)
                line(2f, 5f, 4f, 3f); line(4f, 3f, 7f, 3f); line(7f, 3f, 8f, 5f)
                line(8f, 7.5f, 8f, 11.5f); line(6f, 9.5f, 10f, 9.5f)
            }
            W7Ico.STAR, W7Ico.STAR_OUT -> {
                val p = Path()
                for (k in 0 until 10) {
                    val r = if (k % 2 == 0) 6.5f else 2.8f
                    val a = -PI / 2 + k * PI / 5
                    val x = (8f + r * cos(a).toFloat()) * u
                    val y = (8.7f + r * sin(a).toFloat()) * u
                    if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                drawPath(p, c, style = if (ico == W7Ico.STAR) Fill else st)
            }
            W7Ico.PLAY -> {
                val tri = Path()
                tri.moveTo(5f * u, 3f * u); tri.lineTo(13f * u, 8f * u); tri.lineTo(5f * u, 13f * u); tri.close()
                drawPath(tri, c, style = Fill)
            }
            W7Ico.CODE -> {
                line(5.5f, 4f, 2f, 8f); line(2f, 8f, 5.5f, 12f)
                line(10.5f, 4f, 14f, 8f); line(14f, 8f, 10.5f, 12f)
                line(9f, 3f, 7f, 13f)
            }
            W7Ico.SCRIPT -> {
                box(1.5f, 2.5f, 13f, 11f)
                line(4.5f, 6f, 7f, 8.2f); line(7f, 8.2f, 4.5f, 10.5f); line(8.5f, 10.5f, 11.5f, 10.5f)
            }
            W7Ico.OPEN_WITH -> {
                line(2f, 4f, 2f, 13f); line(2f, 13f, 11f, 13f); line(11f, 13f, 11f, 9f); line(2f, 4f, 7f, 4f)
                line(8f, 8f, 14f, 2f); line(10f, 2f, 14f, 2f); line(14f, 2f, 14f, 6f)
            }
            W7Ico.SHARE -> {
                drawCircle(c, radius = 1.8f * u, center = Offset(12f * u, 3.5f * u), style = st)
                drawCircle(c, radius = 1.8f * u, center = Offset(4f * u, 8f * u), style = st)
                drawCircle(c, radius = 1.8f * u, center = Offset(12f * u, 12.5f * u), style = st)
                line(5.6f, 7f, 10.4f, 4.5f); line(5.6f, 9f, 10.4f, 11.5f)
            }
            W7Ico.REFRESH -> {
                drawArc(c, startAngle = 40f, sweepAngle = 290f, useCenter = false,
                    topLeft = Offset(3f * u, 3f * u), size = Size(10f * u, 10f * u), style = st)
                line(12.3f, 5.5f, 12.3f, 2.5f); line(12.3f, 5.5f, 15.2f, 6f)
            }
            W7Ico.SORT -> {
                line(5f, 13f, 5f, 3f); line(5f, 3f, 3f, 5.5f); line(5f, 3f, 7f, 5.5f)
                line(11f, 3f, 11f, 13f); line(11f, 13f, 9f, 10.5f); line(11f, 13f, 13f, 10.5f)
            }
            W7Ico.SELECT -> { box(2.5f, 2.5f, 11f, 11f); line(5f, 8.2f, 7.3f, 10.5f); line(7.3f, 10.5f, 11.2f, 5.5f) }
            W7Ico.SELECT_ALL -> {
                for (y in listOf(4f, 8f, 12f)) {
                    line(2f, y, 3.3f, y + 1.3f); line(3.3f, y + 1.3f, 5.5f, y - 1.5f); line(8f, y, 14f, y)
                }
            }
            W7Ico.CLEAR -> {
                drawCircle(c, radius = 6.2f * u, center = Offset(8f * u, 8f * u), style = st)
                line(5.5f, 5.5f, 10.5f, 10.5f); line(10.5f, 5.5f, 5.5f, 10.5f)
            }
            W7Ico.SEARCH -> {
                drawCircle(c, radius = 4.6f * u, center = Offset(6.8f * u, 6.8f * u), style = st)
                line(10.2f, 10.2f, 14f, 14f)
            }
            W7Ico.MORE -> {
                drawCircle(c, radius = 1.2f * u, center = Offset(8f * u, 3.5f * u))
                drawCircle(c, radius = 1.2f * u, center = Offset(8f * u, 8f * u))
                drawCircle(c, radius = 1.2f * u, center = Offset(8f * u, 12.5f * u))
            }
            W7Ico.NAV -> { line(2.5f, 4f, 13.5f, 4f); line(2.5f, 8f, 13.5f, 8f); line(2.5f, 12f, 13.5f, 12f) } // [DROP-HAMBURGER]
            W7Ico.INFO -> {
                drawCircle(c, radius = 6.2f * u, center = Offset(8f * u, 8f * u), style = st)
                line(8f, 7f, 8f, 11.5f)
                drawCircle(c, radius = 0.9f * u, center = Offset(8f * u, 4.9f * u))
            }
        }
    }
}

@Composable
internal fun W7ChoiceItem(mark: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(mark, color = Win7.NavHeader, fontSize = 12.sp, modifier = Modifier.width(26.dp))
        Text(label, color = Win7.Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

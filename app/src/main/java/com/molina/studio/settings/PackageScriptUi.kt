package com.molina.studio.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun W7IconApt(label: String) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier.size(34.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFFE8A35F), Color(0xFFB5611E))), shape)
            .border(1.dp, Color(0x55000000), shape),
        Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** Ikon "perbarui + tingkatkan": dua panah melingkar putih di ubin oranye (selaras ikon refresh Settings). */
@Composable
internal fun W7IconAptSync() {
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier.size(34.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFFE8A35F), Color(0xFFB5611E))), shape)
            .border(1.dp, Color(0x55000000), shape),
        Alignment.Center
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            val sw = w * 0.13f
            val c = w / 2f
            val r = c - sw * 1.6f
            val topLeft = Offset(c - r, c - r)
            val arc = Size(r * 2, r * 2)
            fun head(deg: Float) {
                val a = Math.toRadians(deg.toDouble())
                val cx = Math.cos(a).toFloat()
                val sy = Math.sin(a).toFloat()
                val px = c + r * cx
                val py = c + r * sy
                val len = w * 0.2f
                val tip = Offset(px - sy * len, py + cx * len)
                drawLine(Color.White, tip, Offset(px + cx * len, py + sy * len), sw, StrokeCap.Round)
                drawLine(Color.White, tip, Offset(px - cx * len, py - sy * len), sw, StrokeCap.Round)
            }
            drawArc(Color.White, 20f, 130f, false, topLeft, arc, style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(Color.White, 200f, 130f, false, topLeft, arc, style = Stroke(sw, cap = StrokeCap.Round))
            head(150f)
            head(330f)
        }
    }
}

/** Ikon "daftar tool": tiga baris berbutir putih di ubin oranye. */
@Composable
internal fun W7IconToolList() {
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier.size(34.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFFE8A35F), Color(0xFFB5611E))), shape)
            .border(1.dp, Color(0x55000000), shape),
        Alignment.Center
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            val sw = w * 0.11f
            for (i in 0..2) {
                val y = w * (0.2f + 0.3f * i)
                drawCircle(Color.White, sw * 0.9f, Offset(w * 0.12f, y))
                drawLine(Color.White, Offset(w * 0.3f, y), Offset(w * 0.92f, y), sw, StrokeCap.Round)
            }
        }
    }
}

@Composable
internal fun W7IconScriptBig(box: Dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        val h = size.height
        drawRoundRect(Color(0xFF444444), Offset(w * 0.14f, h * 0.06f), Size(w * 0.72f, h * 0.88f), CornerRadius(w * 0.06f))
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF8FC4EE), Color(0xFF2D6DB5))),
            Offset(w * 0.19f, h * 0.12f), Size(w * 0.62f, h * 0.76f), CornerRadius(w * 0.03f)
        )
        val s = w * 0.05f
        drawLine(Color.White, Offset(w * 0.30f, h * 0.34f), Offset(w * 0.42f, h * 0.44f), s, StrokeCap.Round)
        drawLine(Color.White, Offset(w * 0.42f, h * 0.44f), Offset(w * 0.30f, h * 0.54f), s, StrokeCap.Round)
        drawLine(Color.White, Offset(w * 0.48f, h * 0.58f), Offset(w * 0.66f, h * 0.58f), s, StrokeCap.Round)
    }
}

@Composable
internal fun W7IconPackageBig(box: Dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFFE2B873), Color(0xFFA9712C))),
            Offset(w * 0.08f, h * 0.26f), Size(w * 0.84f, h * 0.62f), CornerRadius(w * 0.05f)
        )
        drawRoundRect(Color(0xFF7A4F1D), Offset(w * 0.04f, h * 0.14f), Size(w * 0.92f, h * 0.20f), CornerRadius(w * 0.04f))
        drawRect(Color(0xFFF2E3C2), Offset(w * 0.43f, h * 0.14f), Size(w * 0.14f, h * 0.74f))
    }
}

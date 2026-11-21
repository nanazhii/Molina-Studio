package com.molina.code.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * [CODE-OFFLINE] Lencana privasi "Offline" berupa ikon saja (sinyal nirkabel dicoret), menggantikan teks "Offline"
 * di header agar hemat ruang. Murni gambar Canvas, tanpa API jaringan. Label aksesibilitas tetap "Offline".
 * Dijaga scripts/offline_gate.py.
 */
@Composable
internal fun OfflineBadge(modifier: Modifier = Modifier) {
    val tone = CodeColors.Brand
    Canvas(modifier.size(18.dp).semantics { contentDescription = "Offline" }) {
        val k = size.minDimension / 24f
        val w = 2f * k
        val cx = 12f * k
        val cy = 19f * k
        for (r in floatArrayOf(4f, 8f, 12f)) {
            val rr = r * k
            drawArc(
                color = tone, startAngle = -135f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(cx - rr, cy - rr), size = Size(rr * 2, rr * 2),
                style = Stroke(width = w, cap = StrokeCap.Round)
            )
        }
        drawCircle(tone, radius = 1.6f * k, center = Offset(cx, cy))
        drawLine(tone, Offset(4f * k, 3.5f * k), Offset(20f * k, 21f * k), strokeWidth = 2.2f * k, cap = StrokeCap.Round)
    }
}

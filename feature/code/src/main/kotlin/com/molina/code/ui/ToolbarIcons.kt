package com.molina.code.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale

/** Ikon toolbar. Digambar dengan Canvas pada kisi 24x24 agar tanpa dependency ikon tambahan. */
internal enum class ToolbarIcon { Save, Paste, Undo, Redo, Find, Left, Right, SelectAll, Play, Stop, Close, More, Lock }

private val IconStroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)

private val DashStroke = Stroke(
    width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round,
    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 2.5f))
)

private fun icon(build: Path.() -> Unit): Path = Path().apply(build)

private fun DrawScope.drawUndoArrow(color: Color) {
    drawPath(
        icon {
            moveTo(9f, 4f); lineTo(5f, 8f); lineTo(9f, 12f)
        },
        color, style = IconStroke
    )
    drawPath(
        icon {
            moveTo(5f, 8f); lineTo(13f, 8f)
            cubicTo(17f, 8f, 19f, 10.5f, 19f, 13.5f)
            cubicTo(19f, 17f, 16.5f, 19f, 13f, 19f)
            lineTo(8f, 19f)
        },
        color, style = IconStroke
    )
}

/** Menggambar ikon pada area persegi apa pun; [dot] = titik emas kecil (Simpan saat dirty). */
internal fun DrawScope.drawToolbarIcon(kind: ToolbarIcon, color: Color, dot: Boolean) {
    val s = size.minDimension / 24f
    scale(s, s, pivot = Offset.Zero) {
        when (kind) {
            ToolbarIcon.Save -> {
                drawPath(
                    icon {
                        moveTo(4f, 3f); lineTo(17f, 3f); lineTo(21f, 7f)
                        lineTo(21f, 21f); lineTo(4f, 21f); close()
                    },
                    color, style = IconStroke
                )
                drawPath(
                    icon {
                        moveTo(8f, 3f); lineTo(8f, 8f); lineTo(15f, 8f); lineTo(15f, 3f)
                    },
                    color, style = IconStroke
                )
                drawPath(
                    icon {
                        moveTo(8f, 21f); lineTo(8f, 14f); lineTo(17f, 14f); lineTo(17f, 21f)
                    },
                    color, style = IconStroke
                )
                if (dot) drawCircle(CodeColors.Gold, radius = 3.4f, center = Offset(20f, 4f))
            }
            ToolbarIcon.Paste -> {
                drawPath(
                    icon {
                        moveTo(9f, 5f); lineTo(6f, 5f); lineTo(6f, 21f)
                        lineTo(18f, 21f); lineTo(18f, 5f); lineTo(15f, 5f)
                    },
                    color, style = IconStroke
                )
                drawPath(
                    icon {
                        moveTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 7f)
                        lineTo(9f, 7f); close()
                    },
                    color, style = IconStroke
                )
            }
            ToolbarIcon.Undo -> drawUndoArrow(color)
            ToolbarIcon.Redo -> scale(-1f, 1f, pivot = Offset(12f, 12f)) { drawUndoArrow(color) }
            ToolbarIcon.Find -> {
                drawCircle(color, radius = 6f, center = Offset(10f, 10f), style = IconStroke)
                drawLine(color, Offset(14.5f, 14.5f), Offset(20f, 20f), strokeWidth = 2f, cap = StrokeCap.Round)
            }
            ToolbarIcon.Left -> drawPath(
                icon { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) },
                color, style = IconStroke
            )
            ToolbarIcon.Right -> drawPath(
                icon { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) },
                color, style = IconStroke
            )
            ToolbarIcon.SelectAll -> {
                // kotak putus-putus (pilihan) + kotak penuh (salinan)
                drawPath(
                    icon {
                        moveTo(3f, 3f); lineTo(15f, 3f); lineTo(15f, 15f)
                        lineTo(3f, 15f); close()
                    },
                    color, style = DashStroke
                )
                drawPath(
                    icon {
                        moveTo(9f, 9f); lineTo(21f, 9f); lineTo(21f, 21f)
                        lineTo(9f, 21f); close()
                    },
                    color, style = IconStroke
                )
            }
            ToolbarIcon.Play -> drawPath(
                icon { moveTo(7f, 4f); lineTo(20f, 12f); lineTo(7f, 20f); close() },
                color
            )
            ToolbarIcon.Stop -> drawPath(
                icon { moveTo(6f, 6f); lineTo(18f, 6f); lineTo(18f, 18f); lineTo(6f, 18f); close() },
                color
            )
            ToolbarIcon.Close -> {
                drawLine(color, Offset(6f, 6f), Offset(18f, 18f), strokeWidth = 2f, cap = StrokeCap.Round)
                drawLine(color, Offset(18f, 6f), Offset(6f, 18f), strokeWidth = 2f, cap = StrokeCap.Round)
            }
            ToolbarIcon.Lock -> {
                drawPath(
                    icon {
                        moveTo(5f, 11f); lineTo(19f, 11f); lineTo(19f, 21f)
                        lineTo(5f, 21f); close()
                    },
                    color, style = IconStroke
                )
                drawPath(
                    icon {
                        moveTo(8f, 11f); lineTo(8f, 7f)
                        cubicTo(8f, 3.5f, 16f, 3.5f, 16f, 7f)
                        lineTo(16f, 11f)
                    },
                    color, style = IconStroke
                )
            }
            ToolbarIcon.More -> {
                drawCircle(color, radius = 1.9f, center = Offset(12f, 5f))
                drawCircle(color, radius = 1.9f, center = Offset(12f, 12f))
                drawCircle(color, radius = 1.9f, center = Offset(12f, 19f))
            }
        }
    }
}

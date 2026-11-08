package com.molina.code.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale

/** [CODE-MENUICON] Ikon menu titik tiga dan Aksi file. Kisi 24x24, gaya garis sama dengan ToolbarIcons. */
internal enum class MenuIcon {
    New, SaveAs, Open, Reload, Actions, Find, GoToLine, Share, Script, Insert, Run, Snippet,
    Export, Convert, Syntax, Encoding, Eol, Palette, Wrap, ReadOnly, Stats, Redo,
    FolderUp, NewFolder, Rename, Copy, Reveal, Delete, Command, File, FindFiles, Outline, FontSize, Format, Symbol, History, Check, Back
}

private val MStroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun mp(build: Path.() -> Unit): Path = Path().apply(build)

private fun DrawScope.ln(c: Color, x1: Float, y1: Float, x2: Float, y2: Float) =
    drawLine(c, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2f, cap = StrokeCap.Round)

internal fun DrawScope.drawMenuIcon(kind: MenuIcon, color: Color) {
    val shared = when (kind) {
        MenuIcon.SaveAs -> ToolbarIcon.Save
        MenuIcon.Find -> ToolbarIcon.Find
        MenuIcon.Insert -> ToolbarIcon.Paste
        MenuIcon.Run -> ToolbarIcon.Play
        MenuIcon.Encoding, MenuIcon.ReadOnly -> ToolbarIcon.Lock
        MenuIcon.Redo -> ToolbarIcon.Redo
        else -> null
    }
    if (shared != null) {
        drawToolbarIcon(shared, color, false)
        return
    }
    val s = size.minDimension / 24f
    scale(s, s, pivot = Offset.Zero) {
        when (kind) {
            MenuIcon.Back -> { // [CODE-BACK]
                ln(color, 5f, 12f, 19f, 12f)
                drawPath(mp { moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) }, color, style = MStroke)
            }
            MenuIcon.New -> {
                drawPath(mp { moveTo(6f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f); lineTo(6f, 21f); close() }, color, style = MStroke)
                ln(color, 12.5f, 11f, 12.5f, 17f)
                ln(color, 9.5f, 14f, 15.5f, 14f)
            }
            MenuIcon.Open -> drawPath(
                mp { moveTo(3f, 6f); lineTo(9f, 6f); lineTo(11f, 9f); lineTo(21f, 9f); lineTo(21f, 19f); lineTo(3f, 19f); close() },
                color, style = MStroke
            )
            MenuIcon.Reload -> {
                drawArc(color, startAngle = -60f, sweepAngle = 290f, useCenter = false, topLeft = Offset(5f, 5f), size = Size(14f, 14f), style = MStroke)
                ln(color, 15.5f, 5.9f, 19.5f, 6.2f)
                ln(color, 15.5f, 5.9f, 15.8f, 2.4f)
            }
            MenuIcon.Actions -> {
                ln(color, 9f, 7f, 20f, 7f)
                ln(color, 9f, 12f, 20f, 12f)
                ln(color, 9f, 17f, 20f, 17f)
                drawCircle(color, radius = 1.3f, center = Offset(5f, 7f))
                drawCircle(color, radius = 1.3f, center = Offset(5f, 12f))
                drawCircle(color, radius = 1.3f, center = Offset(5f, 17f))
            }
            MenuIcon.GoToLine -> {
                ln(color, 4f, 5f, 20f, 5f)
                ln(color, 4f, 19f, 20f, 19f)
                ln(color, 12f, 8f, 12f, 15f)
                drawPath(mp { moveTo(9f, 12f); lineTo(12f, 15f); lineTo(15f, 12f) }, color, style = MStroke)
            }
            MenuIcon.Share -> {
                drawCircle(color, radius = 2.5f, center = Offset(6f, 12f), style = MStroke)
                drawCircle(color, radius = 2.5f, center = Offset(18f, 6f), style = MStroke)
                drawCircle(color, radius = 2.5f, center = Offset(18f, 18f), style = MStroke)
                ln(color, 8.2f, 10.8f, 15.8f, 7.2f)
                ln(color, 8.2f, 13.2f, 15.8f, 16.8f)
            }
            MenuIcon.Script -> {
                drawPath(mp { moveTo(5f, 7f); lineTo(11f, 12f); lineTo(5f, 17f) }, color, style = MStroke)
                ln(color, 13f, 18f, 19f, 18f)
            }
            MenuIcon.Snippet -> {
                drawPath(mp { moveTo(9f, 4f); lineTo(5f, 4f); lineTo(5f, 20f); lineTo(9f, 20f) }, color, style = MStroke)
                drawPath(mp { moveTo(15f, 4f); lineTo(19f, 4f); lineTo(19f, 20f); lineTo(15f, 20f) }, color, style = MStroke)
            }
            MenuIcon.Export -> {
                drawPath(mp { moveTo(4f, 15f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 15f) }, color, style = MStroke)
                ln(color, 12f, 16f, 12f, 4f)
                drawPath(mp { moveTo(8f, 8f); lineTo(12f, 4f); lineTo(16f, 8f) }, color, style = MStroke)
            }
            MenuIcon.Convert -> {
                ln(color, 4f, 8f, 19f, 8f)
                drawPath(mp { moveTo(15f, 4f); lineTo(19f, 8f); lineTo(15f, 12f) }, color, style = MStroke)
                ln(color, 20f, 16f, 5f, 16f)
                drawPath(mp { moveTo(9f, 12f); lineTo(5f, 16f); lineTo(9f, 20f) }, color, style = MStroke)
            }
            MenuIcon.Syntax -> {
                drawPath(mp { moveTo(9f, 6f); lineTo(3f, 12f); lineTo(9f, 18f) }, color, style = MStroke)
                drawPath(mp { moveTo(15f, 6f); lineTo(21f, 12f); lineTo(15f, 18f) }, color, style = MStroke)
                ln(color, 13.5f, 5f, 10.5f, 19f)
            }
            MenuIcon.Eol -> {
                drawPath(mp { moveTo(19f, 5f); lineTo(19f, 13f); lineTo(7f, 13f) }, color, style = MStroke)
                drawPath(mp { moveTo(11f, 9f); lineTo(7f, 13f); lineTo(11f, 17f) }, color, style = MStroke)
            }
            MenuIcon.Palette -> {
                drawCircle(color, radius = 9f, center = Offset(12f, 12f), style = MStroke)
                drawCircle(color, radius = 1.3f, center = Offset(8f, 10f))
                drawCircle(color, radius = 1.3f, center = Offset(12f, 7.5f))
                drawCircle(color, radius = 1.3f, center = Offset(16f, 10f))
                drawCircle(color, radius = 1.3f, center = Offset(15.5f, 15f))
            }
            MenuIcon.Wrap -> {
                ln(color, 4f, 6f, 20f, 6f)
                ln(color, 4f, 18f, 10f, 18f)
                drawPath(mp { moveTo(4f, 12f); lineTo(16f, 12f); cubicTo(19f, 12f, 19f, 18f, 16f, 18f); lineTo(13f, 18f) }, color, style = MStroke)
                drawPath(mp { moveTo(15f, 16f); lineTo(13f, 18f); lineTo(15f, 20f) }, color, style = MStroke)
            }
            MenuIcon.Stats -> {
                drawLine(color, Offset(6f, 20f), Offset(6f, 13f), strokeWidth = 3f, cap = StrokeCap.Round)
                drawLine(color, Offset(12f, 20f), Offset(12f, 5f), strokeWidth = 3f, cap = StrokeCap.Round)
                drawLine(color, Offset(18f, 20f), Offset(18f, 9f), strokeWidth = 3f, cap = StrokeCap.Round)
            }
            MenuIcon.FolderUp -> {
                drawPath(mp { moveTo(3f, 6f); lineTo(9f, 6f); lineTo(11f, 9f); lineTo(21f, 9f); lineTo(21f, 19f); lineTo(3f, 19f); close() }, color, style = MStroke)
                ln(color, 12f, 17f, 12f, 12f)
                drawPath(mp { moveTo(9.5f, 14f); lineTo(12f, 11.5f); lineTo(14.5f, 14f) }, color, style = MStroke)
            }
            MenuIcon.NewFolder -> {
                drawPath(mp { moveTo(3f, 6f); lineTo(9f, 6f); lineTo(11f, 9f); lineTo(21f, 9f); lineTo(21f, 19f); lineTo(3f, 19f); close() }, color, style = MStroke)
                ln(color, 12f, 12f, 12f, 17f)
                ln(color, 9.5f, 14.5f, 14.5f, 14.5f)
            }
            MenuIcon.Rename -> {
                drawPath(mp { moveTo(4f, 20f); lineTo(4f, 16f); lineTo(16f, 4f); lineTo(20f, 8f); lineTo(8f, 20f); close() }, color, style = MStroke)
                ln(color, 13f, 7f, 17f, 11f)
            }
            MenuIcon.Copy -> {
                drawPath(mp { moveTo(8f, 8f); lineTo(20f, 8f); lineTo(20f, 21f); lineTo(8f, 21f); close() }, color, style = MStroke)
                drawPath(mp { moveTo(16f, 8f); lineTo(16f, 3f); lineTo(4f, 3f); lineTo(4f, 16f); lineTo(8f, 16f) }, color, style = MStroke)
            }
            MenuIcon.Reveal -> {
                drawPath(mp { moveTo(3f, 6f); lineTo(9f, 6f); lineTo(11f, 9f); lineTo(21f, 9f); lineTo(21f, 19f); lineTo(3f, 19f); close() }, color, style = MStroke)
                drawCircle(color, radius = 2f, center = Offset(12f, 14f), style = MStroke)
            }
            MenuIcon.Delete -> {
                ln(color, 4f, 7f, 20f, 7f)
                drawPath(mp { moveTo(9f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f) }, color, style = MStroke)
                drawPath(mp { moveTo(6f, 7f); lineTo(7f, 21f); lineTo(17f, 21f); lineTo(18f, 7f) }, color, style = MStroke)
                ln(color, 10f, 11f, 10f, 17f)
                ln(color, 14f, 11f, 14f, 17f)
            }
            MenuIcon.Command -> {
                drawPath(mp { moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close() }, color, style = MStroke)
                drawPath(mp { moveTo(7f, 9.5f); lineTo(10.5f, 12f); lineTo(7f, 14.5f) }, color, style = MStroke)
                ln(color, 12.5f, 15f, 17f, 15f)
            }
            MenuIcon.File -> {
                drawPath(mp { moveTo(6f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f); lineTo(6f, 21f); close() }, color, style = MStroke)
                ln(color, 9f, 13f, 16f, 13f)
                ln(color, 9f, 17f, 16f, 17f)
            }
            MenuIcon.FindFiles -> {
                ln(color, 4f, 6f, 14f, 6f)
                ln(color, 4f, 11f, 10f, 11f)
                ln(color, 4f, 16f, 8f, 16f)
                drawCircle(color, radius = 3.5f, center = Offset(15.5f, 14.5f), style = MStroke)
                ln(color, 18f, 17f, 21f, 20f)
            }
            MenuIcon.Outline -> {
                ln(color, 4f, 6f, 20f, 6f)
                ln(color, 8f, 12f, 20f, 12f)
                ln(color, 12f, 18f, 20f, 18f)
            }
            MenuIcon.FontSize -> { // [CODE-FONT] huruf A besar dan plus kecil
                ln(color, 3f, 20f, 9f, 5f)
                ln(color, 9f, 5f, 15f, 20f)
                ln(color, 5.5f, 14f, 12.5f, 14f)
                ln(color, 19f, 6f, 19f, 12f)
                ln(color, 16f, 9f, 22f, 9f)
            }
            MenuIcon.Format -> { // [CODE-FORMAT] rata kiri dengan panjang baris bertingkat
                ln(color, 4f, 6f, 20f, 6f)
                ln(color, 8f, 10f, 20f, 10f)
                ln(color, 8f, 14f, 16f, 14f)
                ln(color, 4f, 18f, 20f, 18f)
            }
            MenuIcon.Check -> { // [CODE-CHECK] tanda centang
                ln(color, 4f, 12.5f, 9.5f, 18f)
                ln(color, 9.5f, 18f, 20f, 6.5f)
            }
            MenuIcon.Symbol -> { // [CODE-SYMBOL] tanda pagar
                ln(color, 9f, 4f, 7f, 20f)
                ln(color, 17f, 4f, 15f, 20f)
                ln(color, 4f, 9f, 20f, 9f)
                ln(color, 3f, 15f, 19f, 15f)
            }
            MenuIcon.History -> { // [CODE-HISTORY] jam dengan panah balik
                drawCircle(color, radius = 7f, center = Offset(13f, 12f), style = MStroke)
                ln(color, 13f, 8f, 13f, 12f)
                ln(color, 13f, 12f, 16f, 14f)
                ln(color, 3f, 12f, 5.5f, 9.5f)
                ln(color, 3f, 12f, 1.5f, 9f)
            }
            else -> Unit
        }
    }
}

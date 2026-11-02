package com.molina.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** [CODE-FONT] Rentang dan bawaan ukuran font editor (sp). Bawaan sama dengan CodeDimens.EditorTextSize. */
internal const val FONT_MIN_SP = 8
internal const val FONT_MAX_SP = 24
internal const val FONT_DEFAULT_SP = 13

private val FONT_PRESETS = listOf(10, 12, 13, 14, 16, 18, 20)

/** Dialog Ukuran font editor dari menu titik tiga: tombol - dan +, pilihan cepat, pratinjau; berlaku untuk semua tab. */
@Composable
internal fun FontSizeDialog(session: CodeSession, onDismiss: () -> Unit) {
    val sp = session.editorFontSp
    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 340.dp).win7DialogFrame(shape).padding(20.dp)) {
            BasicText(
                text = "Font editor",
                style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
            )
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StepButton("\u2212", sp > FONT_MIN_SP) { session.applyEditorFontSp(sp - 1) }
                BasicText(
                    text = "${sp}sp",
                    style = TextStyle(color = CodeColors.MenuText, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
                )
                StepButton("+", sp < FONT_MAX_SP) { session.applyEditorFontSp(sp + 1) }
            }
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = "Rentang ${FONT_MIN_SP}\u2013${FONT_MAX_SP}sp, berlaku untuk semua tab",
                style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 12.sp)
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FONT_PRESETS.forEach { p ->
                    val on = p == sp
                    val chip = RoundedCornerShape(4.dp)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(32.dp)
                            .clip(chip)
                            .background(if (on) CodeColors.Capsule else CodeColors.Surface2, chip)
                            .border(CodeDimens.Hairline, if (on) CodeColors.Gold else CodeColors.Hairline, chip)
                            .clickable { session.applyEditorFontSp(p) },
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            text = p.toString(),
                            style = TextStyle(color = if (on) CodeColors.Gold else CodeColors.MenuText, fontSize = 13.sp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(CodeColors.EditorBg)
                    .border(CodeDimens.Hairline, CodeColors.Hairline)
                    .padding(10.dp)
            ) {
                BasicText(
                    text = "fun main() {\n    println(\"Molina\")\n}",
                    style = TextStyle(color = CodeColors.TextPrimary, fontSize = sp.sp, fontFamily = FontFamily.Monospace)
                )
            }
            Spacer(Modifier.height(12.dp)) // [CODE-STYLE]
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EditorFont.entries.forEach { f ->
                    ChoiceChip(f.label, session.editorFont == f, Modifier.weight(1f)) { session.applyEditorFont(f) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceChip(
                    "Ligature: ${if (session.editorLigature) "aktif" else "mati"}",
                    session.editorLigature, Modifier.weight(1f)
                ) { session.toggleEditorLigature() }
                ChoiceChip(
                    "Sticky scroll: ${if (session.editorSticky) "aktif" else "mati"}",
                    session.editorSticky, Modifier.weight(1f)
                ) { session.toggleEditorSticky() }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Win7DialogButton("Setel ulang (${FONT_DEFAULT_SP}sp)", { session.applyEditorFontSp(FONT_DEFAULT_SP) })
                Spacer(Modifier.weight(1f))
                Win7DialogButton("Selesai", onDismiss, isDefault = true)
            }
        }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .width(64.dp)
            .height(44.dp)
            .alpha(if (enabled) 1f else CodeColors.DisabledAlpha)
            .clip(shape)
            .background(CodeColors.Surface2, shape)
            .border(CodeDimens.Hairline, CodeColors.Hairline, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(text = label, style = TextStyle(color = CodeColors.MenuText, fontSize = 24.sp))
    }
}

/** [CODE-STYLE] Chip pilihan / saklar bergaya sama dengan chip ukuran cepat. */
@Composable
internal fun ChoiceChip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val chip = RoundedCornerShape(4.dp)
    Box(
        modifier
            .height(34.dp)
            .clip(chip)
            .background(if (on) CodeColors.Capsule else CodeColors.Surface2, chip)
            .border(CodeDimens.Hairline, if (on) CodeColors.Gold else CodeColors.Hairline, chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = if (on) CodeColors.Gold else CodeColors.MenuText, fontSize = 11.sp)
        )
    }
}

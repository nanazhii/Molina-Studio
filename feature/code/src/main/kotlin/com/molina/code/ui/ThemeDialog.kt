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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** [CODE-TM] Dialog Tema kode dari menu titik tiga: pilih tema token dan nyalakan/matikan highlight TextMate. */
@Composable
internal fun ThemeDialog(session: CodeSession, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 340.dp).win7DialogFrame(shape).padding(20.dp)) {
            BasicText(
                text = "Tema kode",
                style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                text = "Mengubah warna token saja; latar editor tetap hitam Molina",
                style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 12.sp)
            )
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MolinaTextMate.THEMES.forEach { t ->
                    val on = session.editorTheme == t.id
                    val chip = RoundedCornerShape(4.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(chip)
                            .background(if (on) CodeColors.Capsule else CodeColors.Surface2, chip)
                            .border(CodeDimens.Hairline, if (on) CodeColors.Gold else CodeColors.Hairline, chip)
                            .clickable(enabled = session.tmEnabled) { session.applyEditorTheme(t.id) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            text = t.label,
                            style = TextStyle(color = if (on) CodeColors.Gold else CodeColors.MenuText, fontSize = 14.sp),
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            t.palette.forEach { c -> Box(Modifier.size(10.dp).background(Color(c), CircleShape)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            ChoiceChip(
                "Highlight TextMate: ${if (session.tmEnabled) "aktif" else "mati (lexer Molina)"}",
                session.tmEnabled, Modifier.fillMaxWidth()
            ) { session.toggleTextMate() }
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = "Berkas di atas 400 ribu karakter dan bahasa tanpa grammar memakai lexer Molina",
                style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 11.sp)
            )
            Spacer(Modifier.height(12.dp))
            ChoiceChip(
                "Diff kontras tinggi: ${if (session.diffContrast) "aktif" else "mati"}",
                session.diffContrast, Modifier.fillMaxWidth()
            ) { session.toggleDiffContrast() }
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = "Riwayat versi: latar hijau/merah lebih pekat dan tanda +/\u2212 tebal",
                style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 11.sp)
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Win7DialogButton("Selesai", onDismiss, isDefault = true)
            }
        }
    }
}

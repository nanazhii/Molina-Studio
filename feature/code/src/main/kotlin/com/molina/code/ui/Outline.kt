package com.molina.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** Struktur berkas (pola panel Structure di Android Studio): daftar simbol tab aktif; ketuk untuk melompat ke barisnya. */
@Composable
internal fun OutlineDialog(tab: EditorTab, onDismiss: () -> Unit) {
    val label = tab.file?.name ?: tab.title
    val symbols = remember(tab.id) { Outline.extract(label, tab.currentTextOrNull(2_000_000) ?: "") }
    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().win7DialogFrame(shape)) {
            BasicText(
                text = "Struktur: " + label + (if (symbols != null) " (" + symbols.size + ")" else ""),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
            )
            when {
                symbols == null -> BasicText(
                    text = "Jenis berkas ini belum didukung. Didukung: kt, kts, java, py, js/ts, sh, md.",
                    modifier = Modifier.padding(16.dp),
                    style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 13.sp)
                )
                symbols.isEmpty() -> BasicText(
                    text = "Tidak ada simbol ditemukan.",
                    modifier = Modifier.padding(16.dp),
                    style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 13.sp)
                )
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxH)) {
                    items(symbols) { s ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    tab.editor?.jumpToLineHighlighted(s.line)
                                    onDismiss()
                                }
                                .padding(start = (16 + s.depth * 14).dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(
                                text = s.kind,
                                modifier = Modifier.width(44.dp),
                                maxLines = 1,
                                style = TextStyle(color = CodeColors.Gold.copy(alpha = 0.75f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            )
                            BasicText(
                                text = s.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = CodeColors.IconDefault, fontSize = 14.sp)
                            )
                            BasicText(
                                text = (s.line + 1).toString(),
                                style = TextStyle(color = CodeColors.SecondaryChampagne, fontSize = 11.sp)
                            )
                        }
                    }
                }
            }
        }
    }
}

package com.molina.studio.cinema

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.studio.ui.MolinaTheme

/** Pilihan tombol File di Cinema Home: satu berkas, satu folder, atau folder beserta subfolder. */
@Composable
internal fun CinemaOpenChooser(
    onFile: () -> Unit,
    onFolder: () -> Unit,
    onFolderDeep: () -> Unit,
    onDismiss: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 40.dp), Alignment.Center) {
            Column(
                Modifier.widthIn(max = 380.dp).fillMaxWidth()
                    .shadow(16.dp, shape)
                    .clip(shape)
                    .background(MolinaTheme.CardBg)
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(MolinaTheme.Gold.copy(alpha = 0.6f), MolinaTheme.CardLine)),
                        shape
                    )
                    .padding(horizontal = 24.dp, vertical = 22.dp)
            ) {
                Text("Buka dari perangkat", color = MolinaTheme.OnBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(32.dp).height(2.dp).background(MolinaTheme.Gold, RoundedCornerShape(1.dp)))
                Spacer(Modifier.height(14.dp))
                ChooserRow(ChooserIcon.FILM, "Buka berkas", "Putar satu video atau audio", onFile)
                Spacer(Modifier.height(8.dp))
                ChooserRow(ChooserIcon.FOLDER, "Buka folder", "Semua media di folder jadi playlist", onFolder)
                Spacer(Modifier.height(8.dp))
                ChooserRow(ChooserIcon.FOLDERS, "Buka folder dan subfolder", "Termasuk subfolder, maksimal 1000 berkas", onFolderDeep)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.End, Alignment.CenterVertically) {
                    TextButton(onDismiss) { Text("Batal", color = MolinaTheme.Muted) }
                }
            }
        }
    }
}

private enum class ChooserIcon { FILM, FOLDER, FOLDERS }

/** Ikon emas bergaya Home Theater, digambar vektor agar konsisten di semua perangkat. */
@Composable
private fun GoldIcon(kind: ChooserIcon, modifier: Modifier = Modifier.size(28.dp)) {
    val gold = MolinaTheme.Gold
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val line = Stroke(width = w * 0.075f)
        fun folder(dy: Float): Path = Path().apply {
            moveTo(w * 0.08f, h * 0.26f + dy)
            lineTo(w * 0.38f, h * 0.26f + dy)
            lineTo(w * 0.46f, h * 0.38f + dy)
            lineTo(w * 0.92f, h * 0.38f + dy)
            lineTo(w * 0.92f, h * 0.82f + dy)
            lineTo(w * 0.08f, h * 0.82f + dy)
            close()
        }
        when (kind) {
            ChooserIcon.FILM -> {
                drawRoundRect(
                    color = gold, topLeft = Offset(w * 0.12f, h * 0.1f), size = Size(w * 0.76f, h * 0.8f),
                    cornerRadius = CornerRadius(w * 0.1f, w * 0.1f), style = line
                )
                for (i in 0..2) {
                    val y = h * (0.22f + i * 0.24f)
                    drawRect(gold, Offset(w * 0.18f, y), Size(w * 0.09f, h * 0.1f))
                    drawRect(gold, Offset(w * 0.73f, y), Size(w * 0.09f, h * 0.1f))
                }
                val play = Path().apply {
                    moveTo(w * 0.4f, h * 0.34f)
                    lineTo(w * 0.62f, h * 0.5f)
                    lineTo(w * 0.4f, h * 0.66f)
                    close()
                }
                drawPath(play, gold)
            }
            ChooserIcon.FOLDER -> drawPath(folder(0f), gold, style = line)
            ChooserIcon.FOLDERS -> {
                drawPath(folder(-h * 0.14f), gold.copy(alpha = 0.55f), style = line)
                drawPath(folder(h * 0.04f), gold, style = line)
            }
        }
    }
}

@Composable
private fun ChooserRow(icon: ChooserIcon, title: String, sub: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .border(1.dp, MolinaTheme.CardLine, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GoldIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = MolinaTheme.OnBackground, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = MolinaTheme.Muted, fontSize = 12.sp)
        }
    }
}

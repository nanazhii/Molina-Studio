package com.molina.studio.cinema

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.studio.ui.MolinaTheme

/**
 * Dialog Molina berbingkai: lebar dibatasi dengan margin tepi lega, bingkai tipis bergradasi emas,
 * garis aksen di bawah judul, dan tombol aksi sejajar kanan.
 */
@Composable
internal fun MolinaFrameDialog(
    title: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
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
                Text(title, color = MolinaTheme.OnBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(32.dp).height(2.dp).background(MolinaTheme.Gold, RoundedCornerShape(1.dp)))
                Spacer(Modifier.height(18.dp))
                content()
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.End, Alignment.CenterVertically) {
                    TextButton(onDismiss) { Text(dismissLabel, color = MolinaTheme.Muted) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onConfirm) { Text(confirmLabel, color = MolinaTheme.Gold, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

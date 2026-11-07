package com.molina.studio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.studio.ui.MolinaTheme

/** [MOLINA-DIALOG] Ukuran dialog ringkas; ubah di sini untuk menyetel semua dialog sekaligus. */
private object MolinaDialogSpec {
    val OuterMargin = 24.dp      // jarak dialog ke tepi layar (kiri/kanan)
    val MaxWidth = 420.dp
    val Corner = 16.dp
    val Frame = 1.dp             // frame putih di tepi
    val PadH = 16.dp             // padding isi (dulu 24dp bawaan Material3)
    val PadTop = 16.dp
    val PadBottom = 6.dp
    val TitleGap = 10.dp
    val ButtonGap = 4.dp
}

/**
 * Pengganti ringkas `AlertDialog` Material3 (parameter sama): latar hitam pekat, frame putih 1dp,
 * padding isi diperkecil sehingga blok tidak terlalu luas. Tombol memakai warna primer tema (hijau).
 */
@Composable
internal fun MolinaAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(MolinaDialogSpec.Corner)
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = MolinaDialogSpec.OuterMargin), Alignment.Center) {
            Column(
                modifier.widthIn(max = MolinaDialogSpec.MaxWidth).fillMaxWidth()
                    .clip(shape)
                    .background(Color.Black)
                    .border(MolinaDialogSpec.Frame, Color.White, shape)
                    .padding(
                        start = MolinaDialogSpec.PadH, end = MolinaDialogSpec.PadH,
                        top = MolinaDialogSpec.PadTop, bottom = MolinaDialogSpec.PadBottom
                    )
            ) {
                CompositionLocalProvider(LocalContentColor provides MolinaTheme.OnBackground) {
                    if (title != null) {
                        CompositionLocalProvider(
                            LocalTextStyle provides MaterialTheme.typography.titleLarge.copy(
                                fontSize = 20.sp, fontWeight = FontWeight.Medium
                            )
                        ) { title() }
                        Spacer(Modifier.height(MolinaDialogSpec.TitleGap))
                    }
                    if (text != null) {
                        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium) {
                            Box(Modifier.weight(1f, fill = false)) { text() }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(MolinaDialogSpec.ButtonGap, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

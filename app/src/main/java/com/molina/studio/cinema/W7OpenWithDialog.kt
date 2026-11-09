package com.molina.studio.cinema

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** [NANO-OPENWITH] Pilihan editor untuk berkas teks di Penjelajah. */
internal enum class W7OpenWith { NANO, OTHER }

/**
 * [NANO-OPENWITH] Dialog "Buka dengan": nano di Terminal atau aplikasi lain (pemilih sistem Android).
 */
@Composable
internal fun W7OpenWithDialog(
    file: File, canNano: Boolean, canOther: Boolean,
    onCancel: () -> Unit, onPick: (W7OpenWith) -> Unit
) {
    W7Panel("Buka dengan", onCancel) {
        Text(file.name, color = Win7.Ink.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Win7Button("nano di Terminal", Modifier.fillMaxWidth(), isDefault = true, enabled = canNano) { onPick(W7OpenWith.NANO) }
        Spacer(Modifier.height(6.dp))
        Win7Button("Aplikasi lain...", Modifier.fillMaxWidth(), enabled = canOther) { onPick(W7OpenWith.OTHER) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Win7Button("Batal", onClick = onCancel)
        }
    }
}

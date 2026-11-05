package com.molina.code.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File

// [CODE-STORE] Aksi untuk satu berkas pada satu commit di Riwayat proyek: buka berkas yang sekarang, atau
// kembalikan berkas ke isinya pada commit itu (dari blob lokal `.molina/objects`; semua offline).

@Composable
internal fun ProjectFileDialog(
    session: CodeSession,
    root: File,
    time: Long,
    path: String,
    whenText: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    var confirm by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().win7DialogFrame(shape)) {
            BasicText(
                text = path,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
            )
            BasicText(
                text = "Commit $whenText",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = TextStyle(color = CodeColors.SecondaryNeutral, fontSize = 12.sp)
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = "Kembalikan memakai salinan lokal di .molina/objects. Tidak ada data yang keluar dari perangkat.",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = TextStyle(color = CodeColors.TextSecondary, fontSize = 12.sp)
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Win7DialogButton("Buka", { onDismiss(); onOpen() })
                Win7DialogButton("Kembalikan", { confirm = true }, isDefault = true)
                Win7DialogButton("Batal", onDismiss)
            }
        }
    }
    if (confirm) {
        ConfirmDialog(
            title = "Kembalikan berkas?",
            message = "$path akan dikembalikan ke isinya pada commit $whenText. Isi saat ini ditimpa; " +
                "perubahan yang belum di-commit pada berkas ini hilang.",
            confirmLabel = "KEMBALIKAN",
            danger = true,
            onConfirm = {
                confirm = false
                session.restoreProjectFile(root, time, path)
                onDismiss()
            },
            onCancel = { confirm = false }
        )
    }
}

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

// [CODE-STORE] Aksi untuk satu berkas pada satu commit di Riwayat proyek: lihat perubahannya, buka berkas yang
// sekarang, atau kembalikan berkas dari salinan lokal `.molina/objects` (semua offline). Berkas yang dihapus pada
// commit itu dikembalikan dari commit sebelumnya ([restoreTime], [restoreWhen]).

@Composable
internal fun ProjectFileDialog(
    session: CodeSession,
    root: File,
    time: Long,
    kind: Char,
    path: String,
    whenText: String,
    restoreTime: Long,
    restoreWhen: String,
    canOpen: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    var confirm by remember { mutableStateOf(false) }
    var showDiff by remember { mutableStateOf(false) }
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
                text = "Commit $whenText" + if (kind == 'D') " \u00B7 dihapus" else "",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = TextStyle(color = CodeColors.SecondaryNeutral, fontSize = 12.sp)
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = "Pengembalian memakai salinan lokal di .molina/objects. Tidak ada data yang keluar dari perangkat.",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = TextStyle(color = CodeColors.TextSecondary, fontSize = 12.sp)
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Win7DialogButton("Perubahan", { showDiff = true })
                if (canOpen) Win7DialogButton("Buka", { onDismiss(); onOpen() })
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Win7DialogButton("Kembalikan", { confirm = true }, isDefault = true)
                Win7DialogButton("Batal", onDismiss)
            }
        }
    }
    if (showDiff) ProjectDiffDialog(root, time, path, kind, whenText) { showDiff = false }
    if (confirm) {
        ConfirmDialog(
            title = if (kind == 'D') "Kembalikan berkas yang dihapus?" else "Kembalikan berkas?",
            message = if (kind == 'D') {
                "$path dihapus pada commit $whenText. Berkas akan dibuat lagi dari isinya pada commit $restoreWhen " +
                    "(versi terakhir sebelum dihapus). Berkas dengan nama sama ditimpa."
            } else {
                "$path akan dikembalikan ke isinya pada commit $whenText. Isi saat ini ditimpa; " +
                    "perubahan yang belum di-commit pada berkas ini hilang."
            },
            confirmLabel = "KEMBALIKAN",
            danger = true,
            onConfirm = {
                confirm = false
                session.restoreProjectFile(root, restoreTime, path)
                onDismiss()
            },
            onCancel = { confirm = false }
        )
    }
}

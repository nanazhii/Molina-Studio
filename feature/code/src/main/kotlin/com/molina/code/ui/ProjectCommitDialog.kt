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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File

// [CODE-COMMIT] Dialog "Commit baru": satu baris pesan (boleh kosong). Commit mencatat semua berkas yang berubah
// sejak commit terakhir di Riwayat proyek; commit pertama mencatat semua berkas proyek.

@Composable
internal fun CommitDialog(session: CodeSession, root: File, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    var msg by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val go = {
        session.commitProject(root, msg)
        onDismiss()
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().win7DialogFrame(shape)) {
            BasicText(
                text = "Commit baru",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
            )
            BasicText(
                text = root.name.ifEmpty { root.path },
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 16.dp),
                style = TextStyle(color = CodeColors.SecondaryNeutral, fontSize = 12.sp)
            )
            UnderlineField(
                value = msg,
                onChange = { msg = it.replace("\n", "") },
                hint = "pesan commit (boleh kosong)",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
                imeAction = ImeAction.Done,
                onAction = { go() },
                onFocus = {}
            )
            BasicText(
                text = "Mencatat semua berkas yang berubah sejak commit terakhir. Commit pertama mencatat semua berkas proyek.",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = TextStyle(color = CodeColors.TextSecondary, fontSize = 12.sp)
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Win7DialogButton("Commit", { go() }, isDefault = true)
                Win7DialogButton("Batal", onDismiss)
            }
        }
    }
}

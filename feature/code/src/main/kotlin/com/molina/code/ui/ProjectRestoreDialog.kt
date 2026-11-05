package com.molina.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.code.guard.ProjectRestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// [CODE-RESTORE] Pratinjau "Pulihkan seluruh proyek ke commit ini": daftar berkas yang akan ditimpa, dibuat lagi, atau
// dihapus, dan yang dilewati. Hanya baca sampai tombol Pulihkan ditekan. Semua offline (blob di `.molina/objects`).

private const val MAX_ROWS = 60

@Composable
internal fun ProjectRestoreDialog(session: CodeSession, root: File, time: Long, whenText: String, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(CodeDimens.DialogCorner)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    val loaded by produceState<Pair<Boolean, ProjectRestore.Plan?>>(false to null, root, time) {
        value = withContext(Dispatchers.IO) {
            try { true to ProjectRestore.plan(root, time) } catch (e: Exception) { true to null }
        }
    }
    val ready = loaded.first
    val plan = loaded.second
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).win7DialogFrame(shape)) {
            BasicText(
                text = "Pulihkan seluruh proyek",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                style = TextStyle(color = CodeColors.IconDefault, fontSize = CodeDimens.DialogTitleSize)
            )
            BasicText(
                text = "ke commit $whenText",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                style = TextStyle(color = CodeColors.SecondaryNeutral, fontSize = 12.sp)
            )
            Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
            when {
                !ready -> RNote("Menghitung\u2026")
                plan == null -> RNote("Versi ini tidak bisa dibaca (riwayat sudah dibuang atau proyek terlalu besar).")
                else -> {
                    RNote(
                        if (plan.isEmpty) "Proyek sudah sama dengan commit ini."
                        else "Ditimpa ${plan.overwritten} \u00B7 dibuat lagi ${plan.created} \u00B7 dihapus ${plan.deleted}" +
                            if (plan.skipped.isNotEmpty()) " \u00B7 dilewati ${plan.skipped.size}" else ""
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxH)) {
                        val shown = plan.items.take(MAX_ROWS)
                        items(shown.size) { i ->
                            val ent = shown[i]
                            val (sign, color) = when (ent.kind) {
                                'A' -> "+" to CodeColors.Clean
                                'D' -> "\u2212" to CodeColors.Danger
                                else -> "~" to CodeColors.Warning
                            }
                            PRow(sign, color, ent.path)
                        }
                        if (plan.items.size > MAX_ROWS) item {
                            RNote("dan ${plan.items.size - MAX_ROWS} berkas lain")
                        }
                        items(plan.skipped.take(MAX_ROWS).size) { i ->
                            val s = plan.skipped[i]
                            PRow("\u2022", CodeColors.SecondaryNeutral, s.path + " \u2014 " + when (s.reason) {
                                ProjectRestore.Skip.NO_BLOB -> "isi versi ini tidak tersimpan, dilewati"
                                ProjectRestore.Skip.NO_BACKUP -> "terlalu besar untuk dicadangkan, tidak disentuh"
                                ProjectRestore.Skip.BAD_PATH -> "path tidak aman, dilewati"
                            })
                        }
                    }
                    if (!plan.isEmpty) RNote(
                        "Sebelum memulihkan, dibuat commit otomatis \u201CSebelum pemulihan ke \u2026\u201D sehingga keadaan sekarang " +
                            "tetap bisa dikembalikan per berkas. Semua proses lokal; tidak ada data yang keluar dari perangkat."
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(CodeDimens.Hairline).background(CodeColors.Hairline))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (plan != null && !plan.isEmpty) {
                    Win7DialogButton("Pulihkan proyek", {
                        session.restoreProjectAll(root, time, plan.items.map { it.path })
                        onDismiss()
                    })
                }
                Win7DialogButton("Batal", onDismiss, isDefault = true)
            }
        }
    }
}

@Composable
private fun PRow(sign: String, color: androidx.compose.ui.graphics.Color, text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        BasicText(
            text = sign,
            modifier = Modifier.width(16.dp),
            style = TextStyle(color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        )
        BasicText(
            text = text,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = CodeColors.TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        )
    }
}

@Composable
private fun RNote(text: String) {
    BasicText(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = TextStyle(color = CodeColors.TextSecondary, fontSize = 12.sp)
    )
}

package com.molina.code.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.molina.code.guard.ScriptPrecheck
import java.io.File

/** [CODE-GUARD] Simpan ditahan karena [target] berubah di disk sejak tab dibuka atau terakhir disimpan. */
internal class StaleConflict(val tab: EditorTab, val target: File, val after: (() -> Unit)?)

/** [CODE-GUARD] Skrip yang tampak rusak; [proceed] menjalankannya juga bila pengguna memilih Tetap jalankan. */
internal class ScriptCheckPrompt(val title: String, val report: ScriptPrecheck.Report, val proceed: () -> Unit)

/** [CODE-GUARD] Pilihan saat berkas berubah dari luar editor: Batal, Bandingkan, Muat ulang, atau Timpa. */
@Composable
internal fun StaleDiskDialog(session: CodeSession, c: StaleConflict) {
    val shape = RoundedCornerShape(9.dp)
    Dialog(onDismissRequest = { session.staleCancel() }) {
        Column(Modifier.widthIn(max = 340.dp).win7DialogFrame(shape)) {
            Win7DialogTitleBar("Molina Code") { session.staleCancel() }
            Column(Modifier.padding(16.dp)) {
                BasicText(
                    text = "Berkas berubah di disk",
                    style = TextStyle(color = Win7Code.Ink, fontSize = 16.sp)
                )
                Spacer(Modifier.height(8.dp))
                BasicText(
                    text = "\"${c.target.name}\" diubah dari luar editor (skrip, Terminal, atau aplikasi lain) " +
                        "sejak dibuka atau terakhir disimpan. Menyimpan sekarang akan menimpa perubahan itu.",
                    style = TextStyle(color = CodeColors.TextPrimary, fontSize = 14.sp)
                )
                Spacer(Modifier.height(6.dp))
                BasicText(
                    text = "Bandingkan: isi disk dicatat di Riwayat lalu dibuka, merah = isi disk, hijau = isi editor. " +
                        "Muat ulang: isi editor dicatat di Riwayat lalu dibuang.",
                    style = TextStyle(color = CodeColors.TextSecondary, fontSize = 12.sp)
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    Win7DialogButton("Batal", { session.staleCancel() })
                    Win7DialogButton("Bandingkan", { session.staleCompare() }, isDefault = true)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    Win7DialogButton("Muat ulang", { session.staleReload() })
                    Win7DialogButton("Timpa", { session.staleOverwrite() }, accent = CodeColors.Danger)
                }
            }
        }
    }
}

/** [CODE-GUARD] Daftar masalah hasil [ScriptPrecheck]; Batal adalah pilihan bawaan (aman). */
@Composable
internal fun ScriptCheckDialog(session: CodeSession, p: ScriptCheckPrompt) {
    val shape = RoundedCornerShape(9.dp)
    val r = p.report
    Dialog(onDismissRequest = { session.scriptCheck = null }) {
        Column(Modifier.widthIn(max = 340.dp).win7DialogFrame(shape)) {
            Win7DialogTitleBar("Molina Code") { session.scriptCheck = null }
            Column(Modifier.padding(16.dp)) {
                BasicText(
                    text = "Skrip tampak bermasalah",
                    style = TextStyle(color = Win7Code.Ink, fontSize = 16.sp)
                )
                Spacer(Modifier.height(8.dp))
                BasicText(
                    text = "\"${p.title}\" \u00B7 ${r.lines} baris \u00B7 sha1 ${r.sha12}",
                    style = TextStyle(color = CodeColors.TextSecondary, fontSize = 12.sp)
                )
                Spacer(Modifier.height(8.dp))
                for (issue in r.issues) {
                    BasicText(
                        text = "\u2022 " + issue.label(),
                        style = TextStyle(color = CodeColors.TextPrimary, fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(3.dp))
                }
                if (r.moreIssues > 0) {
                    BasicText(
                        text = "\u2022 dan ${r.moreIssues} masalah lain",
                        style = TextStyle(color = CodeColors.TextSecondary, fontSize = 13.sp)
                    )
                }
                if (r.network.isNotEmpty()) { // [CODE-OFFLINE]
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        text = "Skrip ini tampak memakai internet: " + r.network.joinToString(", "),
                        style = TextStyle(color = CodeColors.Danger, fontSize = 13.sp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                BasicText(
                    text = "Cocokkan jumlah baris dan sha1 dengan pengirim. Tempelan yang terpotong tepat di batas " +
                        "pernyataan hanya bisa ketahuan dari angka itu.",
                    style = TextStyle(color = CodeColors.TextSecondary, fontSize = 12.sp)
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    Win7DialogButton("Tetap jalankan", {
                        session.scriptCheck = null
                        p.proceed()
                    }, accent = CodeColors.Danger)
                    Win7DialogButton("Batal", { session.scriptCheck = null }, isDefault = true)
                }
            }
        }
    }
}

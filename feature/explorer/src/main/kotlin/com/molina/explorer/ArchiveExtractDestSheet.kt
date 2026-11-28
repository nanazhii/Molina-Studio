package com.molina.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.molina.core.fileops.ExtractResult
import com.molina.core.fileops.ExtractTarget
import java.io.File

// [ARCHIVE-DEST] Lembar "Ekstrak ke mana?" ala Windows (di sini / tempat lain) dan dialog hasil ekstrak.
// Memakai kerangka lembar arsip bersama (ArchiveSheetKit/Parts); logika di ExtractDest.

/**
 * Pilihan tujuan ekstrak. "Ekstrak di sini" = folder arsip; "Ekstrak ke tempat lain" membuka pemilih folder Penjelajah
 * (Win7OpenDialog mode pickFolder). Kotak centang menentukan apakah hasil dibungkus folder bernama arsip.
 * [onConfirm] menerima folder tujuan dan [ExtractTarget]; pemanggil menjalankan ekstrak.
 */
@Composable
internal fun ArchiveExtractDestSheet(
    file: File, folderName: String, hereDir: File, selectedCount: Int, env: ExplorerEnv,
    onBack: () -> Unit, onConfirm: (File, ExtractTarget) -> Unit
) {
    val th = LocalExplorerTheme.current
    var elsewhere by remember(file) { mutableStateOf(false) }
    var custom by remember(file) { mutableStateOf<File?>(null) }
    var named by remember(file) { mutableStateOf(true) }
    var picking by remember(file) { mutableStateOf(false) }
    val base: File? = if (elsewhere) custom else hereDir
    val err = ExtractDest.error(base)

    Dialog(onDismissRequest = onBack, properties = archiveSheetProperties()) {
        ArchiveSheetGravity()
        Column(
            archiveSheetModifier(th)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
        ) {
            ArchiveSheetHeader(
                th, "Ekstrak ke mana?",
                file.name + " \u2022 " + (if (selectedCount > 0) selectedCount.toString() + " item terpilih" else "semua isi"),
                onBack = onBack
            )
            Spacer(Modifier.height(12.dp))
            DestOption(th, !elsewhere, "Ekstrak di sini", hereDir.path) { elsewhere = false }
            Spacer(Modifier.height(8.dp))
            DestOption(th, elsewhere, "Ekstrak ke tempat lain\u2026", custom?.path ?: "Ketuk untuk memilih folder tujuan") {
                elsewhere = true
                if (custom == null) picking = true
            }
            if (elsewhere && custom != null) Row(Modifier.padding(top = 8.dp)) {
                ArchiveSheetChip(th, "Ubah folder", false) { picking = true }
            }
            ArchiveSheetCaption(th, "Hasil")
            ArchiveSheetCheck(th, named, "Buat folder \"" + folderName + "\"") { named = !named }
            ArchiveSheetNote(th, "Lokasi hasil: " + (if (base != null) ExtractDest.preview(base, folderName, named) else "-"))
            ArchiveSheetNote(th, "Berkas yang sudah ada tidak ditimpa; hasil diberi nama baru.")
            if (err != null) ArchiveSheetNote(th, err, error = true)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ArchiveSheetTextButton(th, "Batal") { onBack() }
                ArchiveSheetButton(
                    th, if (selectedCount > 0) "Ekstrak (" + selectedCount + ")" else "Ekstrak semua", err == null, Modifier.weight(1f)
                ) { base?.let { onConfirm(it, ExtractDest.target(named)) } }
            }
        }
    }
    if (picking) Win7OpenDialog(
        onDismiss = { picking = false },
        pickFolder = true,
        startDir = custom ?: hereDir,
        onPickFolder = { custom = it; elsewhere = true; picking = false },
        env = env
    )
}

/** Satu baris pilihan bergaya radio: judul + jalur (maks. 2 baris). */
@Composable
private fun DestOption(th: ExplorerTheme, on: Boolean, title: String, path: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
            .background(if (on) th.accent.copy(alpha = 0.15f) else Color.Transparent, shape)
            .border(1.dp, if (on) th.accent else th.frame.copy(alpha = 0.67f), shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (on) "\u25C9" else "\u25CB", color = if (on) th.accent else th.dim, fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = th.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(path, color = th.dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Dialog hasil: keterangan sukses, jumlah berkas, lokasi lengkap, dan tombol "Tampilkan folder". Tidak hilang sendiri. */
@Composable
internal fun ArchiveExtractDoneDialog( // [ARCHIVE-DEST-2] onTerminal null = tombol tidak ditampilkan
    result: ExtractResult, onClose: () -> Unit, onTerminal: (() -> Unit)? = null, onShow: () -> Unit
) {
    val th = LocalExplorerTheme.current
    val box = RoundedCornerShape(8.dp)
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().clip(ArchiveSheetShape).background(th.bg, ArchiveSheetShape)
                .border(1.dp, th.frame.copy(alpha = 0.4f), ArchiveSheetShape).padding(16.dp)
        ) {
            Text("\u2713  Ekstrak berhasil", color = th.accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            for (line in ExtractDest.resultLines(result.files, result.renamed)) {
                Text(line, color = th.ink, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            }
            ArchiveSheetCaption(th, "Lokasi hasil")
            Text(
                result.dir.path, color = th.ink, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().background(th.surface, box).padding(10.dp)
            )
            if (onTerminal != null) ArchiveSheetChip(th, "Buka di Molina Terminal", false, Modifier.fillMaxWidth().padding(top = 12.dp)) { onTerminal() }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ArchiveSheetTextButton(th, "Tutup") { onClose() }
                ArchiveSheetButton(th, "Tampilkan folder", true, Modifier.weight(1f)) { onShow() }
            }
        }
    }
}

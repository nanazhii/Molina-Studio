package com.molina.explorer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File
import com.molina.core.fileops.CompressFormat
import com.molina.core.fileops.CompressNames
import com.molina.core.fileops.CompressOptions
import com.molina.core.fileops.PasswordPolicy

/**
 * [ARCHIVE-UNI] Dialog Kompres berupa lembar arsip yang sama dengan dialog buka arsip (ZIP, 7z/tar/dll): tanpa sandi maupun bersandi
 * tampil seragam. Pilih format (ZIP, 7z, tar.gz/bz2/xz, tar), level, nama arsip, dan (ZIP/7z) kata sandi AES-256.
 * Hasil dikirim lewat [onCompress]; pemanggil menjalankan [ArchiveOps.compress] dengan progres dan tombol Hentikan.
 * [ARCHIVE6] 7z juga bisa bersandi: pilih "Isi saja" atau "Isi + nama berkas" (-mhe=on).
 * [startWithPassword] = true untuk menu "Kompres ZIP + kata sandi": kotak sandi langsung aktif.
 * [parentName] = nama folder yang sedang dibuka (dipakai sebagai nama bawaan bila banyak item terpilih).
 */
@Composable
fun ArchiveCompressDialog(
    files: List<File>,
    parentName: String,
    onClose: () -> Unit,
    startWithPassword: Boolean = false,
    onCompress: (CompressOptions) -> Unit
) {
    val th = LocalExplorerTheme.current
    val one = files.singleOrNull()
    var fmt by remember(files) { mutableStateOf(CompressFormat.ZIP) }
    val levels = remember(files) { mutableStateMapOf<CompressFormat, Int>() } // level terakhir per format
    var name by remember(files) { mutableStateOf(CompressNames.defaultBaseName(one?.name, one?.isDirectory == true, parentName)) }
    var usePw by remember(files) { mutableStateOf(startWithPassword) }
    var pw by remember(files) { mutableStateOf("") }
    var pw2 by remember(files) { mutableStateOf("") }
    var show by remember(files) { mutableStateOf(false) }
    var encNames by remember(files) { mutableStateOf(true) } // [ARCHIVE6] 7z: true = isi + nama berkas, false = isi saja

    val level = fmt.clampLevel(levels[fmt] ?: fmt.defaultLevel)
    val nameErr = CompressNames.baseNameError(name, fmt)
    val pwOn = fmt.hasPassword && usePw
    val pwVerdict = PasswordPolicy.evaluate(pw) // [ENC-POLICY]
    val pwHint = when {
        !pwOn -> ""
        pw.isNotEmpty() && !pwVerdict.acceptable -> pwVerdict.message
        pw2.isNotEmpty() && pw != pw2 -> "Kata sandi tidak sama."
        else -> ""
    }
    val pwOk = !pwOn || (pwVerdict.acceptable && pw == pw2)
    val canGo = nameErr == null && pwOk
    fun go() { if (canGo) onCompress(CompressOptions(fmt, level, name, if (pwOn) pw else null, pwOn && fmt.hasNameEncryption && encNames)) }

    Dialog(onDismissRequest = onClose, properties = archiveSheetProperties()) {
        ArchiveSheetGravity()
        Column(archiveSheetModifier(th).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
            ArchiveSheetHeader(th, "Kompres", if (one != null) one.name else files.size.toString() + " item terpilih", onBack = onClose)
            Column(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp).verticalScroll(rememberScrollState())) {
                ArchiveSheetCaption(th, "Format")
                val all = CompressFormat.values().toList()
                for (rowItems in all.chunked(3)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        for ((i, f) in rowItems.withIndex()) {
                            if (i > 0) Spacer(Modifier.width(8.dp))
                            ArchiveSheetChip(th, f.label, fmt == f, Modifier.weight(1f)) { fmt = f }
                        }
                    }
                }
                ArchiveSheetNote(th, fmt.note)

                if (fmt.hasLevel) {
                    ArchiveSheetCaption(th, "Level kompresi")
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) { // [PRESET] Cepat / Seimbang / Maksimal
                        ArchiveSheetChip(th, "Cepat", level == fmt.minLevel, Modifier.weight(1f)) { levels[fmt] = fmt.minLevel }
                        Spacer(Modifier.width(8.dp))
                        ArchiveSheetChip(th, "Seimbang", level == fmt.defaultLevel, Modifier.weight(1f)) { levels[fmt] = fmt.defaultLevel }
                        Spacer(Modifier.width(8.dp))
                        ArchiveSheetChip(th, "Maksimal", level == fmt.maxLevel, Modifier.weight(1f)) { levels[fmt] = fmt.maxLevel }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ArchiveSheetChip(th, "\u2212", false, Modifier.width(56.dp), enabled = level > fmt.minLevel) { levels[fmt] = level - 1 }
                        Text(
                            CompressNames.levelLabel(fmt, level), color = th.ink, fontSize = 14.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        ArchiveSheetChip(th, "+", false, Modifier.width(56.dp), enabled = level < fmt.maxLevel) { levels[fmt] = level + 1 }
                    }
                    if (fmt == CompressFormat.SEVEN_Z || fmt == CompressFormat.TAR_XZ) {
                        ArchiveSheetNote(th, "Level 7\u20139 tidak ditawarkan: butuh RAM sangat besar di Android.")
                    }
                }

                ArchiveSheetCaption(th, "Nama arsip")
                ArchiveSheetField(th, name, { name = it }, suffix = fmt.ext, onDone = { go() })
                if (nameErr != null) ArchiveSheetNote(th, nameErr, error = true)
                else ArchiveSheetNote(th, "Bila nama sudah ada, hasil diberi nomor (1), (2), \u2026")

                if (fmt.hasPassword) {
                    Spacer(Modifier.height(8.dp))
                    ArchiveSheetCheck(th, usePw, "Lindungi dengan kata sandi (AES-256)") { usePw = !usePw }
                    if (usePw) {
                        ArchiveSheetNote(th, "Simpan kata sandinya: arsip tidak bisa dibuka tanpa itu.")
                        if (fmt == CompressFormat.ZIP) {
                            ArchiveSheetNote(th, "ZIP: nama berkas tetap terlihat. Untuk menyembunyikannya pilih format 7z.")
                        }
                        if (fmt.hasNameEncryption) { // [ARCHIVE6] dua pilihan enkripsi 7z
                            ArchiveSheetCaption(th, "Yang dienkripsi")
                            Row(Modifier.fillMaxWidth()) {
                                ArchiveSheetChip(th, "Isi saja", !encNames, Modifier.weight(1f)) { encNames = false }
                                Spacer(Modifier.width(8.dp))
                                ArchiveSheetChip(th, "Isi + nama berkas", encNames, Modifier.weight(1f)) { encNames = true }
                            }
                            ArchiveSheetNote(
                                th,
                                if (encNames) "Nama berkas ikut disembunyikan: daftar isi pun butuh kata sandi."
                                else "Hanya isi yang dienkripsi: nama berkas tetap terlihat tanpa kata sandi."
                            )
                            ArchiveSheetNote(th, "Dibuat dengan 7-Zip 26.05 bawaan Molina (AES-256).")
                        }
                        ArchiveSheetCaption(th, "Kata sandi")
                        ArchiveSheetField(th, pw, { pw = it }, secret = true, show = show, onDone = { go() })
                        ArchiveSheetCaption(th, "Ulangi kata sandi")
                        ArchiveSheetField(th, pw2, { pw2 = it }, secret = true, show = show, onDone = { go() })
                        if (pw.isNotEmpty()) ArchiveSheetNote(th, "Kekuatan: " + pwVerdict.strength.label, error = !pwVerdict.acceptable)
                        if (pwHint.isNotEmpty()) ArchiveSheetNote(th, pwHint, error = true)
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            ArchiveSheetChip(th, "Buat acak", false, Modifier.width(120.dp)) {
                                val g = PasswordPolicy.generate(); pw = g; pw2 = g; show = true
                            }
                        }
                        ArchiveSheetCheck(th, show, "Tampilkan kata sandi") { show = !show }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ArchiveSheetTextButton(th, "Batal", onClick = onClose)
                ArchiveSheetButton(th, "Kompres", canGo, Modifier.weight(1f)) { go() }
            }
        }
    }
}

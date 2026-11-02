package com.molina.studio.cinema

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** [ARCHIVE2] Tombol pilihan format: terpilih = warna seleksi Win7. */
@Composable
private fun ArcChip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    Row(
        modifier.height(32.dp).clip(shape)
            .background(if (on) Win7.SelFill else Win7.Input, shape)
            .border(1.dp, if (on) Win7.SelLine else Win7.Field, shape)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Win7.Ink, fontSize = 12.sp, maxLines = 1)
    }
}

/**
 * [ARCHIVE2] Dialog Kompres: pilih format (ZIP, 7z, tar.gz/bz2/xz, tar), level, nama arsip, dan (khusus ZIP) kata sandi AES-256.
 * Hasil dikirim lewat [onCompress]; pemanggil menjalankan [ArchiveOps.compress] dengan progres dan tombol Hentikan.
 * [ARCHIVE6] 7z juga bisa bersandi: pilih "Isi saja" atau "Isi + nama berkas" (-mhe=on).
 * [parentName] = nama folder yang sedang dibuka (dipakai sebagai nama bawaan bila banyak item terpilih).
 */
@Composable
internal fun ArchiveCompressDialog(
    files: List<File>,
    parentName: String,
    onClose: () -> Unit,
    onCompress: (CompressOptions) -> Unit
) {
    val one = files.singleOrNull()
    var fmt by remember(files) { mutableStateOf(CompressFormat.ZIP) }
    val levels = remember(files) { mutableStateMapOf<CompressFormat, Int>() } // level terakhir per format
    var name by remember(files) { mutableStateOf(CompressNames.defaultBaseName(one?.name, one?.isDirectory == true, parentName)) }
    var usePw by remember(files) { mutableStateOf(false) }
    var pw by remember(files) { mutableStateOf("") }
    var pw2 by remember(files) { mutableStateOf("") }
    var show by remember(files) { mutableStateOf(false) }
    var encNames by remember(files) { mutableStateOf(true) } // [ARCHIVE6] 7z: true = isi + nama berkas, false = isi saja

    val level = fmt.clampLevel(levels[fmt] ?: fmt.defaultLevel)
    val nameErr = CompressNames.baseNameError(name, fmt)
    val pwOn = fmt.hasPassword && usePw
    val pwHint = when {
        !pwOn -> ""
        pw.isNotEmpty() && pw.length < 4 -> "Kata sandi minimal 4 karakter."
        pw2.isNotEmpty() && pw != pw2 -> "Kata sandi tidak sama."
        else -> ""
    }
    val pwOk = !pwOn || (pw.length >= 4 && pw == pw2)
    val canGo = nameErr == null && pwOk
    fun go() { if (canGo) onCompress(CompressOptions(fmt, level, name, if (pwOn) pw else null, pwOn && fmt.hasNameEncryption && encNames)) }

    W7Panel("Kompres", onClose) {
        Text(
            if (one != null) one.name else files.size.toString() + " item terpilih",
            color = Win7.Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text("Format", color = Win7.Ink, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            val all = CompressFormat.values().toList()
            for (rowItems in all.chunked(3)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    for ((i, f) in rowItems.withIndex()) {
                        if (i > 0) Spacer(Modifier.width(6.dp))
                        ArcChip(f.label, fmt == f, Modifier.weight(1f)) { fmt = f }
                    }
                }
            }
            Text(fmt.note, color = Win7.TextDim, fontSize = 11.sp)

            if (fmt.hasLevel) {
                Spacer(Modifier.height(10.dp))
                Text("Level kompresi", color = Win7.Ink, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Win7Button("\u2212", Modifier.width(44.dp), enabled = level > fmt.minLevel, compact = true) { levels[fmt] = level - 1 }
                    Text(
                        CompressNames.levelLabel(fmt, level), color = Win7.Ink, fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Win7Button("+", Modifier.width(44.dp), enabled = level < fmt.maxLevel, compact = true) { levels[fmt] = level + 1 }
                }
                if (fmt == CompressFormat.SEVEN_Z || fmt == CompressFormat.TAR_XZ) {
                    Text(
                        "Level 7\u20139 tidak ditawarkan: butuh RAM sangat besar di Android.",
                        color = Win7.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("Nama arsip", color = Win7.Ink, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().height(32.dp).background(Win7.Input).border(1.dp, Win7.Field).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Win7.Ink, fontSize = 14.sp),
                    cursorBrush = SolidColor(Win7.Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { go() }),
                    modifier = Modifier.weight(1f)
                )
                Text(fmt.ext, color = Win7.TextDim, fontSize = 13.sp, maxLines = 1)
            }
            if (nameErr != null) {
                Text(nameErr, color = Color(0xFFFF8A80), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            } else {
                Text("Bila nama sudah ada, hasil diberi nomor (1), (2), \u2026", color = Win7.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }

            if (fmt.hasPassword) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { usePw = !usePw }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (usePw) "[x]" else "[  ]", color = Win7.Ink, fontSize = 12.sp, modifier = Modifier.width(30.dp))
                    Text("Lindungi dengan kata sandi (AES-256)", color = Win7.Ink, fontSize = 13.sp)
                }
                if (usePw) {
                    Text("Simpan kata sandinya: arsip tidak bisa dibuka tanpa itu.", color = Win7.TextDim, fontSize = 11.sp)
                    if (fmt.hasNameEncryption) { // [ARCHIVE6] dua pilihan enkripsi 7z
                        Spacer(Modifier.height(6.dp))
                        Text("Yang dienkripsi", color = Win7.Ink, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth()) {
                            ArcChip("Isi saja", !encNames, Modifier.weight(1f)) { encNames = false }
                            Spacer(Modifier.width(6.dp))
                            ArcChip("Isi + nama berkas", encNames, Modifier.weight(1f)) { encNames = true }
                        }
                        Text(
                            if (encNames) "Nama berkas ikut disembunyikan: daftar isi pun butuh kata sandi."
                            else "Hanya isi yang dienkripsi: nama berkas tetap terlihat tanpa kata sandi.",
                            color = Win7.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
                        )
                        Text(
                            "Dibuat lewat 7z di Debian (Molina Terminal); paket 7zip harus terpasang.",
                            color = Win7.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Kata sandi", color = Win7.Ink, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    W7SecretField(pw, { pw = it }, show, Modifier) { go() }
                    Spacer(Modifier.height(6.dp))
                    Text("Ulangi kata sandi", color = Win7.Ink, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    W7SecretField(pw2, { pw2 = it }, show, Modifier) { go() }
                    if (pwHint.isNotEmpty()) {
                        Text(pwHint, color = Color(0xFFFF8A80), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Row(Modifier.fillMaxWidth().clickable { show = !show }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (show) "[x]" else "[  ]", color = Win7.Ink, fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("Tampilkan kata sandi", color = Win7.TextDim, fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Win7Button("Kompres", isDefault = true, enabled = canGo) { go() }
            Spacer(Modifier.width(8.dp))
            Win7Button("Batal", onClick = onClose)
        }
    }
}

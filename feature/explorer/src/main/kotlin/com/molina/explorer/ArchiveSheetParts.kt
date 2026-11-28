package com.molina.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.ui.MolinaTheme

// [ARCHIVE-UNI] Komponen lembar arsip bersama: dipakai dialog buka arsip (ZIP, 7z/tar/dll) dan dialog Kompres,
// sehingga header, tombol, chip, kolom isian, dan centang tampil seragam. Semua warna dari ExplorerTheme.

/** Header lembar: panah kembali, judul + ringkasan, lalu slot [trailing] (mis. menu titik tiga). */
@Composable
internal fun ArchiveSheetHeader(
    th: ExplorerTheme, title: String, subtitle: String, onBack: () -> Unit,
    trailing: @Composable () -> Unit = {}
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "\u2190", color = th.ink, fontSize = 24.sp,
            modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp, top = 6.dp, bottom = 6.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(title, color = th.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = th.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** Tombol aksi utama (bilah bawah): tinggi minimum 48dp supaya mudah dijangkau jempol. */
@Composable
internal fun ArchiveSheetButton(th: ExplorerTheme, label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.heightIn(min = 48.dp).clip(shape)
            .background(if (enabled) th.accent else th.accent.copy(alpha = 0.35f), shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        Alignment.Center
    ) {
        Text(label, color = th.onAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Tombol teks sekunder (Batal dan sejenisnya). */
@Composable
internal fun ArchiveSheetTextButton(th: ExplorerTheme, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(label, color = th.ink, fontSize = 14.sp, modifier = modifier.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp))
}

/** Chip pilihan: terpilih = latar aksen tipis + bingkai aksen. */
@Composable
internal fun ArchiveSheetChip(
    th: ExplorerTheme, label: String, on: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.heightIn(min = 36.dp).alpha(if (enabled) 1f else 0.4f).clip(shape)
            .background(if (on) th.accent.copy(alpha = 0.15f) else Color.Transparent, shape)
            .border(1.dp, if (on) th.accent else th.frame.copy(alpha = 0.67f), shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        Alignment.Center
    ) {
        Text(label, color = th.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Label bagian (Format, Nama arsip, ...). */
@Composable
internal fun ArchiveSheetCaption(th: ExplorerTheme, text: String) {
    Text(text, color = th.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

/** Catatan kecil di bawah bagian; [error] = merah. */
@Composable
internal fun ArchiveSheetNote(th: ExplorerTheme, text: String, error: Boolean = false) {
    Text(text, color = if (error) MolinaTheme.Bad else th.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
}

/** Baris centang: kotak 20dp berbingkai, terisi aksen saat aktif. */
@Composable
internal fun ArchiveSheetCheck(th: ExplorerTheme, checked: Boolean, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(20.dp).clip(shape).background(if (checked) th.accent else Color.Transparent, shape)
                .border(1.dp, if (checked) th.accent else th.frame, shape),
            Alignment.Center
        ) {
            if (checked) Text("\u2713", color = th.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = th.ink, fontSize = 14.sp)
    }
}

/** Kolom isian satu baris; [secret] menyamarkan isi kecuali [show]; [suffix] = akhiran tetap (mis. ekstensi). */
@Composable
internal fun ArchiveSheetField(
    th: ExplorerTheme, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    suffix: String? = null, secret: Boolean = false, show: Boolean = false, placeholder: String = "", onDone: () -> Unit = {}
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).clip(shape).background(th.surface, shape)
            .border(1.dp, th.frame.copy(alpha = 0.67f), shape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = th.ink, fontSize = 15.sp), cursorBrush = SolidColor(th.accent),
            visualTransformation = if (secret && !show) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = th.dim, fontSize = 15.sp)
                    inner()
                }
            }
        )
        if (suffix != null) Text(suffix, color = th.dim, fontSize = 14.sp, maxLines = 1)
    }
}

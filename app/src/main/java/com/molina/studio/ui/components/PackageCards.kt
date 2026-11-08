package com.molina.studio.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Brush
import com.molina.studio.ui.MolinaTheme

/**
 * [PAKET-KARTU] Komponen kartu halaman Paket bagian Pembaruan. Bahasa visualnya disamakan dengan
 * ToolCard di "Alat tambahan di Debian": sudut 10dp, border 1,5dp, strip status 3dp di kiri,
 * judul 15sp tebal, subjudul 12sp redup, header seksi 14sp NavHeader.
 */
internal enum class PkgState { Unchecked, Current, Outdated, Checking }

private val PkgShape = RoundedCornerShape(10.dp)
private val PkgBorder = MolinaTheme.PanelBorder // [TOKEN-PUSAT]
private val PkgGood = MolinaTheme.Good // [OK-GOOD] semua indikator OK memakai Good
private val PkgBlue = MolinaTheme.Link

@Composable
private fun pkgStrip(state: PkgState): Color = when (state) {
    PkgState.Outdated -> MolinaTheme.Warn
    PkgState.Current -> statusStripColor(detected = true, checking = false, checked = true)
    PkgState.Unchecked -> statusStripColor(detected = false, checking = false, checked = false)
    PkgState.Checking -> statusStripColor(detected = false, checking = true, checked = false)
}

/** Header seksi seperti "Alat tambahan di Debian", dengan ringkasan status opsional di kanan. */
@Composable
internal fun PackageSectionHeader(title: String, trailing: String? = null, state: PkgState = PkgState.Unchecked) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = MolinaTheme.Explorer.NavHeader, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(
                trailing,
                color = when (state) {
                    PkgState.Outdated -> MolinaTheme.Warn
                    PkgState.Current -> PkgGood
                    else -> MolinaTheme.Explorer.TextDim
                },
                fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Kartu status satu komponen (Debian, yt-dlp, NewPipe): strip kiri menunjukkan keadaan.
 * [PAKET-KARTU2] [state] null = tanpa strip; [onClick] non-null = kartu aksi dengan chevron.
 */
@Composable
internal fun PackageStatusCard(
    title: String,
    subtitle: String,
    state: PkgState?,
    badge: String? = null,
    onClick: (() -> Unit)? = null,
    subtitleMaxLines: Int = 3,
    icon: (@Composable () -> Unit)? = null // [KARTU-IKON] ikon opsional di kiri judul
) {
    val strip = if (state == null) Color.Transparent else pkgStrip(state)
    var rowMod = Modifier.fillMaxWidth().drawBehind { drawRect(strip, Offset.Zero, Size(3.dp.toPx(), size.height)) }
    if (onClick != null) rowMod = rowMod.clickable(onClick = onClick)
    Column(Modifier.fillMaxWidth().clip(PkgShape).background(MolinaTheme.Explorer.Card, PkgShape).border(1.5.dp, PkgBorder, PkgShape)) {
        Row(
            rowMod.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    title, color = MolinaTheme.Explorer.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(subtitle, color = MolinaTheme.Explorer.TextDim, fontSize = 12.sp, maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis)
            }
            if (badge != null) {
                MolinaBadge(badge, aero = false, gold = true)
                Spacer(Modifier.width(8.dp))
            }
            if (onClick != null) Text("\u25B8", color = MolinaTheme.Explorer.NavHeader, fontSize = 16.sp)
        }
    }
}

/** Satu kartu berisi beberapa baris, dipisah garis tipis. */
@Composable
internal fun PackageGroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(PkgShape).background(MolinaTheme.Explorer.Card, PkgShape).border(1.5.dp, PkgBorder, PkgShape),
        content = content
    )
}

@Composable
internal fun PackageDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MolinaTheme.Explorer.Hairline))
}

/** Baris informasi baca-saja di dalam [PackageGroupCard]: judul tebal, nilai redup. [STORAGE-KARTU] */
@Composable
internal fun PackageInfoRow(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
        Text(title, color = MolinaTheme.Explorer.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(value, color = MolinaTheme.Explorer.TextDim, fontSize = 12.sp)
    }
}

@Composable
internal fun PackageSwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = MolinaTheme.Explorer.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(summary, color = MolinaTheme.Explorer.TextDim, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Blok aksi: kapsul PERIKSA SEKARANG dan PERBARUI SEMUA, lalu catatan redup. */
@Composable
internal fun PackageActionsBlock(
    running: Boolean,
    updates: Int,
    lastCheck: String,
    updateNote: String,
    onCheck: () -> Unit,
    onUpdateAll: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionChip(if (running) "MEMERIKSA\u2026" else "PERIKSA SEKARANG", MolinaTheme.Explorer.Ink) { if (!running) onCheck() }
            ActionChip(if (updates > 0) "PERBARUI SEMUA ($updates)" else "PERBARUI SEMUA", PkgBlue, onUpdateAll)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Terakhir: $lastCheck. Berjalan di latar belakang dan bisa memakai data seluler.",
            color = MolinaTheme.Explorer.TextDim, fontSize = 12.sp
        )
        Text(updateNote, color = MolinaTheme.Explorer.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** [PAKET-FRAME] Warna strip status kartu: hijau = terdeteksi, abu = tidak/belum diperiksa, hijau Brand berdenyut = memeriksa. */
@Composable
internal fun statusStripColor(detected: Boolean, checking: Boolean, checked: Boolean): Color {
    if (checking) {
        val transition = rememberInfiniteTransition()
        val a by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse)
        )
        return MolinaTheme.Brand.copy(alpha = a)
    }
    return when {
        !checked -> MolinaTheme.Idle.copy(alpha = 0.45f)
        detected -> MolinaTheme.Good
        else -> MolinaTheme.Idle
    }
}

/** [PAKET-FRAME] Tombol aksi berbentuk kapsul beroutline (PASANG biru, VERSI putih, HAPUS merah). */
@Composable
internal fun ActionChip(label: String, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(shape).border(1.dp, color.copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/** Lencana bulat merah glossy (jumlah paket menunggu, dsb.) ala Windows 7. */
@Composable
internal fun MolinaBadge(text: String, aero: Boolean = false, gold: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (gold) listOf(Color(0xFFFFDC85), MolinaTheme.Brand)
                    else if (aero) listOf(Color(0xFF7FC4FF), Color(0xFF1B6FC2))
                    else listOf(MolinaTheme.Bad, Color(0xFFC4392B))
                ),
                shape
            )
            .border(1.dp, Color(0x55000000), shape)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, color = if (gold) Color(0xFF2B1D00) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- [PAKET-PILIHAN] Pilihan tunggal, slider, dan catatan ----------

/**
 * Baris pilihan tunggal (radio) di dalam [PackageGroupCard]. Seluruh baris bisa diketuk, radio hanya penanda.
 * [subtitle] teks redup opsional; [preview] cuplikan visual opsional di bawah judul (contoh prompt berwarna).
 */
@Composable
internal fun PackageChoiceRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    subtitle: String? = null,
    preview: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MolinaTheme.Explorer.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = MolinaTheme.Explorer.TextDim, fontSize = 12.sp)
            if (preview != null) preview()
        }
    }
}

/**
 * Baris slider di dalam [PackageGroupCard]: judul dengan nilai di kanan, slider, lalu [footer] opsional
 * (mis. pratinjau ukuran font). Thumb dipendekkan jadi 24dp karena thumb bawaan M3 setinggi 44dp. [SLIDER-THUMB]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PackageSliderRow(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    footer: (@Composable () -> Unit)? = null
) {
    val thumbInteraction = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, color = MolinaTheme.Explorer.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(valueText, color = MolinaTheme.Explorer.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            interactionSource = thumbInteraction,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = thumbInteraction,
                    thumbSize = DpSize(4.dp, 24.dp)
                )
            }
        )
        if (footer != null) footer()
    }
}

/** Catatan redup di bawah kartu grup (pengganti teks bodySmall lepas di halaman setelan). */
@Composable
internal fun PackageNote(text: String) {
    Text(
        text, color = MolinaTheme.Explorer.TextDim, fontSize = 12.sp,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp)
    )
}

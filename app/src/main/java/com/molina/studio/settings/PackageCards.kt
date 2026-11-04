package com.molina.studio.settings

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
import com.molina.studio.ui.MolinaTheme

/**
 * [PAKET-KARTU] Komponen kartu halaman Paket bagian Pembaruan. Bahasa visualnya disamakan dengan
 * ToolCard di "Alat tambahan di Debian": sudut 10dp, border 1,5dp, strip status 3dp di kiri,
 * judul 15sp tebal, subjudul 12sp redup, header seksi 14sp NavHeader.
 */
internal enum class PkgState { Unchecked, Current, Outdated, Checking }

private val PkgShape = RoundedCornerShape(10.dp)
private val PkgBorder = MolinaTheme.PanelBorder // [TOKEN-PUSAT]
private val PkgGood = MolinaTheme.Detected // sama dengan strip "terdeteksi" pada ToolCard
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
        Text(title, color = W7D.NavHeader, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(
                trailing,
                color = when (state) {
                    PkgState.Outdated -> MolinaTheme.Warn
                    PkgState.Current -> PkgGood
                    else -> W7D.TextDim
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
    onClick: (() -> Unit)? = null
) {
    val strip = if (state == null) Color.Transparent else pkgStrip(state)
    var rowMod = Modifier.fillMaxWidth().drawBehind { drawRect(strip, Offset.Zero, Size(3.dp.toPx(), size.height)) }
    if (onClick != null) rowMod = rowMod.clickable(onClick = onClick)
    Column(Modifier.fillMaxWidth().clip(PkgShape).background(W7D.Card, PkgShape).border(1.5.dp, PkgBorder, PkgShape)) {
        Row(
            rowMod.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    title, color = W7D.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(subtitle, color = W7D.TextDim, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (badge != null) {
                W7Badge(badge, aero = false, gold = true)
                Spacer(Modifier.width(8.dp))
            }
            if (onClick != null) Text("\u25B8", color = W7D.NavHeader, fontSize = 16.sp)
        }
    }
}

/** Satu kartu berisi beberapa baris, dipisah garis tipis. */
@Composable
internal fun PackageGroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(PkgShape).background(W7D.Card, PkgShape).border(1.5.dp, PkgBorder, PkgShape),
        content = content
    )
}

@Composable
internal fun PackageDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(W7D.Hairline))
}

@Composable
internal fun PackageSwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = W7D.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(summary, color = W7D.TextDim, fontSize = 12.sp)
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
            ActionChip(if (running) "MEMERIKSA\u2026" else "PERIKSA SEKARANG", W7D.Ink) { if (!running) onCheck() }
            ActionChip(if (updates > 0) "PERBARUI SEMUA ($updates)" else "PERBARUI SEMUA", PkgBlue, onUpdateAll)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Terakhir: $lastCheck. Berjalan di latar belakang dan bisa memakai data seluler.",
            color = W7D.TextDim, fontSize = 12.sp
        )
        Text(updateNote, color = W7D.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

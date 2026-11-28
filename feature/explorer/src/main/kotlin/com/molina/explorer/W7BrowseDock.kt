package com.molina.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.molina.studio.ui.MolinaTheme
import com.molina.core.ui.Win7
import com.molina.core.ui.win7ButtonSurface

/**
 * [FILES-DOCK] Panel bawah Penjelajah (mode jelajah): satu baris [aksi cepat kontekstual] [Alat] [Terminal] [pil melayang].
 * [DOCK-V3] Alat dan Terminal berupa ikon saja; Buka dihapus.
 * [FLOAT-DOCK] Status Debian dan menu kisi pindah ke pil melayang [W7BrowseFloat] agar daftar lebih luas.
 */
@Composable
fun W7BrowseDock(
    quickLabel: String?,
    toolsEnabled: Boolean,
    terminalEnabled: Boolean,
    toolsActive: Boolean = false, // [DOCK-ACCENT] menu Alat terbuka
    onQuick: () -> Unit,
    onTools: () -> Unit,
    onTerminal: () -> Unit,
    trailing: @Composable () -> Unit
) {
    // [DOCK-V3] Buka dihapus; Properti ada di menu Alat. Alat dan Terminal berupa ikon. Pil melayang (menu kisi,
    // status Debian, Mode Pilih) menempati slot bekas Buka di ujung kanan. quickLabel null = tidak ada aksi cepat kontekstual.
    Row(
        Modifier.fillMaxWidth().padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (quickLabel != null) DockButton(quickLabel, Modifier.weight(1f), true, false, null, onQuick)
        else Spacer(Modifier.weight(1f))
        DockIconButton(W7Ico.TOOLS, "Alat", toolsEnabled, onTools, toolsActive)
        DockIconButton(W7Ico.TERMINAL, "Terminal", terminalEnabled, onTerminal)
        trailing()
    }
}

/**
 * [FLOAT-DOCK] Pil melayang di kanan bawah daftar: label Mode Pilih dan bantuan (bila Mode Pilih aktif),
 * lalu slot [menu] (ikon kisi drop-up). Tanpa label/bantuan, hanya tombol kisi (tanpa bingkai pil). Latar gelap semi-transparan.
 */
@Composable
fun W7BrowseFloat(
    modifier: Modifier,
    accent: Boolean = false, // [FLOAT-GOLD] garis tepi emas saat Mode Pilih aktif
    modeLabel: String?,
    onHelp: (() -> Unit)?,
    menu: @Composable () -> Unit
) {
    // [DOCK-NOFRAME] Tanpa label/bantuan, pil hanya membungkus tombol kisi yang sudah punya bingkai sendiri: lepas bingkai pil (bingkai ganda).
    if (modeLabel.isNullOrEmpty() && onHelp == null) {
        Box(modifier) { menu() }
        return
    }
    val th = LocalExplorerTheme.current // [EXPLORER-THEME-2]
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.clip(shape).background(Color(0xE60B0B0F), shape).border(1.dp, if (accent) th.brand else Win7.BtnLine, shape)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (modeLabel != null && modeLabel.isNotEmpty()) {
            Text(
                modeLabel, color = MolinaTheme.Explorer.PickInk, fontSize = 12.sp, maxLines = 1, // [PICK-RED]
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        if (onHelp != null) {
            W7HelpIcon(onHelp) // [HELP-PANEL]
            Spacer(Modifier.width(6.dp))
        }
        menu() // [DEBIAN-MENU] status Debian kini item pertama di menu kisi (W7DebianMenuItem)
    }
}

/**
 * [DEBIAN-MENU] Item status Debian di menu kisi (drop-up). Dulu ikon terpisah di pil melayang.
 * Terlihat di Debian = hijau; di luar Debian = kuning dan ikon dicoret miring (tidak bergantung warna saja).
 * Ketuk menampilkan penjelasan lengkap lalu [onDone] (menutup menu). Status dihitung pemanggil dari
 * isVisibleInDebian, jadi sinkron dengan target aktif (berkas terpilih atau folder saat ini).
 */
@Composable
fun W7DebianMenuItem(visible: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val tone = if (visible) MolinaTheme.Good else MolinaTheme.Warn
    val title = if (visible) "Debian: terlihat" else "Debian: di luar"
    val hint = if (visible) "Bisa dipakai dari Terminal" else "Tidak terlihat dari Terminal"
    Row(
        Modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = title
                role = Role.Button
            }
            .clickable {
                Toast.makeText(
                    ctx,
                    if (visible) "Terlihat di Debian: path ini bisa dipakai dari Terminal."
                    else "Di luar Debian: folder ini tidak terlihat dari Terminal. Aktifkan \"Akses penyimpanan penuh\" di Setelan Terminal, atau pindahkan ke folder Molina.",
                    Toast.LENGTH_LONG
                ).show()
                onDone()
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { W7DebianGlyph(tone, visible) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = tone, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hint, color = Win7.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** [DEBIAN-ICON] Glyph terminal 16dp; dicoret miring bila [visible] false. */
@Composable
private fun W7DebianGlyph(tone: Color, visible: Boolean) {
    run {
        Canvas(Modifier.size(16.dp)) {
            val u = size.width / 16f
            val sw = 1.4f * u
            drawRoundRect(
                tone, topLeft = Offset(1f * u, 2.5f * u), size = Size(14f * u, 11f * u),
                cornerRadius = CornerRadius(2f * u), style = Stroke(width = sw)
            )
            drawLine(tone, Offset(4.5f * u, 6f * u), Offset(7f * u, 8f * u), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(tone, Offset(7f * u, 8f * u), Offset(4.5f * u, 10f * u), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(tone, Offset(8.5f * u, 10.5f * u), Offset(11.5f * u, 10.5f * u), strokeWidth = sw, cap = StrokeCap.Round)
            if (!visible) drawLine(tone, Offset(2f * u, 14.5f * u), Offset(14f * u, 1.5f * u), strokeWidth = sw, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun DockButton(label: String, modifier: Modifier, enabled: Boolean, primary: Boolean, accent: Color?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier.height(30.dp).clip(shape)
            .win7ButtonSurface(shape, if (primary && enabled) Win7.DefaultLine else Win7.BtnLine)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp),
        Alignment.Center
    ) {
        Text(
            label, color = if (enabled) (accent ?: Win7.Ink) else Win7.TextDim, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** [DOCK-V3] Tombol dock berupa ikon saja (44x36dp, senada W7DropButton); label hanya untuk aksesibilitas. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockIconButton(ico: W7Ico, desc: String, enabled: Boolean, onClick: () -> Unit, active: Boolean = false) {
    val ctx = LocalContext.current // [DOCK-TIP] tekan lama menampilkan nama tombol
    val th = LocalExplorerTheme.current // [EXPLORER-THEME-2]
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.size(width = 44.dp, height = 36.dp).clip(shape)
            .win7ButtonSurface(shape, if (active) th.brand else Win7.BtnLine) // [DOCK-ACCENT] senada W7DropButton
            .semantics { contentDescription = desc; role = Role.Button }
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = { Toast.makeText(ctx, desc, Toast.LENGTH_SHORT).show() }),
        Alignment.Center
    ) {
        W7MenuIcon(ico, enabled, 20.dp)
    }
}

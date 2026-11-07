package com.molina.studio.cinema

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Brush
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

/**
 * [FILES-DOCK] Panel bawah Penjelajah (mode jelajah): tinggi TETAP, satu baris aksi
 * (Aksi cepat, Alat, Terminal, Buka) dengan bobot sama, tanpa lebar minimum.
 * [FLOAT-DOCK] Status Debian dan menu kisi pindah ke pil melayang [W7BrowseFloat] agar daftar lebih luas.
 */
@Composable
internal fun W7BrowseDock(
    quickLabel: String,
    toolsEnabled: Boolean,
    terminalEnabled: Boolean,
    onQuick: () -> Unit,
    onTools: () -> Unit,
    onTerminal: () -> Unit,
    onOpen: () -> Unit
) {
    // [DOCK-V2] Sisipkan dihapus; Alat menempati slotnya. Terminal = cd + Enter ke sesi aktif [TERMINAL-CD].
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DockButton(quickLabel, Modifier.weight(1f), true, false, null, onQuick)
        DockButton("Alat", Modifier.weight(1f), toolsEnabled, false, null, onTools)
        DockButton("Terminal", Modifier.weight(1.1f), terminalEnabled, false, MolinaTheme.Good, onTerminal)
        DockButton("Buka", Modifier.weight(0.9f), true, true, MolinaTheme.Brand, onOpen)
    }
}

/**
 * [FLOAT-DOCK] Pil melayang di kanan bawah daftar: label Mode Pilih (bila ada), bantuan, status Debian,
 * lalu slot [menu] (ikon kisi drop-up). Latar gelap semi-transparan (API 26 tanpa blur).
 */
@Composable
internal fun W7BrowseFloat(
    modifier: Modifier,
    accent: Boolean = false, // [FLOAT-GOLD] garis tepi emas saat Mode Pilih aktif
    debian: Boolean?,
    modeLabel: String?,
    onHelp: (() -> Unit)?,
    menu: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.clip(shape).background(Color(0xE60B0B0F), shape).border(1.dp, if (accent) MolinaTheme.Brand else Win7.BtnLine, shape)
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
        if (debian != null) {
            W7DebianBadge(debian) // [DEBIAN-ICON]
            Spacer(Modifier.width(2.dp))
        }
        menu()
    }
}

/**
 * [DEBIAN-ICON] Status lokasi terhadap Debian sebagai ikon terminal (menggantikan teks "Debian"/"Luar").
 * Terlihat di Debian = hijau; di luar Debian = kuning dan dicoret miring, jadi tidak bergantung pada warna saja.
 * Ketuk untuk penjelasan singkat. Area sentuh 28dp.
 */
@Composable
private fun W7DebianBadge(visible: Boolean) {
    val ctx = LocalContext.current
    val tone = if (visible) MolinaTheme.Good else MolinaTheme.Warn
    val desc = if (visible) "Terlihat di Debian" else "Di luar Debian"
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
            .semantics(mergeDescendants = true) {
                contentDescription = desc
                role = Role.Button
            }
            .clickable {
                Toast.makeText(
                    ctx,
                    if (visible) "Terlihat di Debian: path ini bisa dipakai dari Terminal."
                    else "Di luar Debian: folder ini tidak terlihat dari Terminal. Aktifkan \"Akses penyimpanan penuh\" di Setelan Terminal, atau pindahkan ke folder Molina.",
                    Toast.LENGTH_LONG
                ).show()
            },
        contentAlignment = Alignment.Center
    ) {
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
            .background(Brush.verticalGradient(listOf(Win7.BtnTop, Win7.BtnBottom)), shape)
            .border(1.dp, if (primary && enabled) Win7.DefaultLine else Win7.BtnLine, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp),
        Alignment.Center
    ) {
        Text(
            label, color = if (enabled) (accent ?: Win7.Ink) else Win7.TextDim, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

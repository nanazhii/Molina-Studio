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
            val tone = if (debian) MolinaTheme.Good else MolinaTheme.Warn
            Box(Modifier.size(8.dp).background(tone, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(4.dp))
            Text(if (debian) "Debian" else "Luar", color = tone, fontSize = 10.sp, maxLines = 1)
            Spacer(Modifier.width(8.dp))
        }
        menu()
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

package com.molina.studio.cinema

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.ui.MolinaTheme

internal val CinemaPurple = MolinaTheme.Cinema

@Composable
internal fun CinemaLogo(message: String) {
    Box(Modifier.size(96.dp).border(2.dp, CinemaPurple, CircleShape), Alignment.Center) {
        Text("\u25B6", color = CinemaPurple, fontSize = 40.sp)
    }
    Spacer(Modifier.height(16.dp))
    Text("Molina", color = MolinaTheme.Gold, fontSize = 32.sp, fontWeight = FontWeight.Bold)
    Text("CINEMA", color = MolinaTheme.OnBackground, fontSize = 20.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
    Text("HOME THEATER", color = MolinaTheme.Muted, fontSize = 12.sp, letterSpacing = 4.sp)
    Spacer(Modifier.height(24.dp))
    Text(message, color = MolinaTheme.Muted, textAlign = TextAlign.Center)
}

@Composable
internal fun CinemaAction(glyph: String, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MolinaTheme.CardBg)
            .border(1.dp, CinemaPurple, CircleShape), Alignment.Center) {
            Text(glyph, color = CinemaPurple, fontSize = 16.sp)
        }
        Text(label, color = MolinaTheme.Muted, fontSize = 10.sp)
    }
}
/** Penanda "video sedang main di latar belakang"; sama ungu (CinemaPurple) dengan ikon mpv di tab Log. */
@Composable
internal fun LiveBadge() {
    Row(
        Modifier.clip(CircleShape).background(CinemaPurple.copy(alpha = 0.16f))
            .border(1.dp, CinemaPurple, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(CinemaPurple))
        Spacer(Modifier.width(6.dp))
        Text("LIVE", color = CinemaPurple, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

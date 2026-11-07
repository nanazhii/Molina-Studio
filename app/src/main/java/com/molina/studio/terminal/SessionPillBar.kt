package com.molina.studio.terminal

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.ui.MolinaTheme
import java.util.Locale

// [PILL-BAR] Bilah sesi berbentuk pil di bawah header Terminal: pil aktif emas + garis bawah emas + titik hijau
// status Debian dan tombol + untuk sesi baru. Mini CPU/RAM/jaringan (PillStats) kini di header [HDR-STATS].
// Pil aktif hijau [PILL-GREEN] dengan tombol ✕ di sebelah judul; tutup sesi selalu lewat dialog konfirmasi.

private val PillBarHeight = 32.dp
private val PillHeight = 24.dp

@Composable
internal fun SessionPillBar() {
    if (TerminalSessions.sessions.isEmpty()) return // belum ada sesi (mis. Debian belum terpasang)
    PillBarContent()
}

@Composable
private fun PillBarContent() {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val sessions = TerminalSessions.sessions
    val tick = TerminalSessions.titleTick
    val current = TerminalSessions.currentIndex
    val listState = rememberLazyListState()
    var confirm by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(current, sessions.size) {
        if (current in sessions.indices) listState.animateScrollToItem(current)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(PillBarHeight)
            .background(MolinaTheme.Background)
            .drawBehind {
                drawLine(
                    MolinaTheme.CardLine,
                    Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f
                )
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(sessions) { i, s ->
                val title = remember(tick, s) {
                    TerminalSessions.labelOf(s) ?: s.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Debian"
                }
                SessionPill(i, title, i == current, onClose = { confirm = i }) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    TerminalSessions.select(i)
                }
            }
            item {
                AddPill(TerminalSessions.canAdd()) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    when {
                        RootfsManager.state.value !is RootfsState.Ready ->
                            Toast.makeText(ctx, "Debian belum terpasang", Toast.LENGTH_SHORT).show()
                        !TerminalSessions.canAdd() ->
                            Toast.makeText(ctx, "Maksimal ${TerminalSessions.MAX_SESSIONS} sesi", Toast.LENGTH_SHORT).show()
                        else -> TerminalSessions.newSession(ctx)
                    }
                }
            }
        }
    }

    confirm?.let { i -> CloseSessionDialog(i) { confirm = null } }
}

@Composable
private fun SessionPill(index: Int, title: String, selected: Boolean, onClose: () -> Unit, onClick: () -> Unit) {
    val accent = MolinaTheme.Brand // [PILL-GREEN]
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .height(PillHeight)
            .widthIn(max = 150.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.12f) else MolinaTheme.CardBg, shape)
            .border(1.dp, if (selected) accent.copy(alpha = 0.5f) else MolinaTheme.CardLine, shape)
            .drawBehind {
                if (selected) {
                    val h = 2.dp.toPx()
                    drawLine(
                        accent,
                        Offset(size.width * 0.2f, size.height - h / 2f),
                        Offset(size.width * 0.8f, size.height - h / 2f),
                        h, StrokeCap.Round
                    )
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(MolinaTheme.Good))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            "${index + 1} $title",
            color = if (selected) Color.White else MolinaTheme.Muted, // [PILL-WHITE] judul putih polos; frame, garis bawah, dan titik tetap hijau
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (selected) {
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(20.dp).clip(CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Text("\u2715", color = MolinaTheme.Bad, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}


@Composable
private fun AddPill(enabledLook: Boolean, onClick: () -> Unit) {
    val gold = MolinaTheme.Brand
    val a = if (enabledLook) 1f else 0.4f
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .size(width = 32.dp, height = PillHeight)
            .clip(shape)
            .border(1.dp, gold.copy(alpha = 0.6f * a), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("+", color = gold.copy(alpha = a), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Mini CPU/RAM; dipisah agar pembaruan tiap detik tidak ikut menyusun ulang daftar pil. */
@Composable
internal fun PillStats(onClick: () -> Unit = {}) { // [STATS-CLICK]
    val ctx = LocalContext.current
    var snap by remember { mutableStateOf<StatsSnapshot?>(null) }
    LaunchedEffect(ctx) { SystemStats.shared(ctx).collect { snap = it } }
    val st = snap ?: return
    Column(
        Modifier // [STATS-CLICK-MOD]
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row {
            StatText("CPU ${st.cpuPercent}%", levelColor(st.cpuLevel))
            Spacer(Modifier.width(6.dp))
            StatText("RAM ${ramShort(st.ramMb)}", levelColor(st.ramLevel))
        }
        Row {
            StatText("\u2193${netShort(st.rxBps)}", MolinaTheme.NetDown)
            Spacer(Modifier.width(6.dp))
            StatText("\u2191${netShort(st.txBps)}", MolinaTheme.NetUp)
        }
    }
}

@Composable
private fun StatText(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
}

private fun levelColor(l: Level): Color = when (l) {
    Level.Normal -> MolinaTheme.Good
    Level.Warn -> MolinaTheme.Warn
    Level.High -> MolinaTheme.Bad
}

private fun ramShort(mb: Int): String =
    if (mb >= 1024) String.format(Locale.US, "%.1fG", mb / 1024.0) else "${mb}M"

/** Kecepatan ringkas untuk bilah sempit: 0.0K/s, 12.3K/s, 350K/s, 1.2M/s. */
private fun netShort(bps: Long): String {
    val kb = bps / 1024.0
    return when {
        kb < 100.0 -> String.format(Locale.US, "%.1fK/s", kb)
        kb < 1000.0 -> String.format(Locale.US, "%.0fK/s", kb)
        else -> String.format(Locale.US, "%.1fM/s", kb / 1024.0)
    }
}

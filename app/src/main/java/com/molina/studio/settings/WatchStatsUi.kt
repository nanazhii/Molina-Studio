package com.molina.studio.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.cinema.WatchStats
import com.molina.studio.ui.MolinaTheme
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val Red = Color(0xFFE8776B)

/** Ikon ubin Statistik tontonan: panel gelap dengan tiga batang emas. */
@Composable
internal fun W7IconStatsBig(box: Dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        val h = size.height
        drawRoundRect(Color(0xFF444444), Offset(w * 0.10f, h * 0.06f), Size(w * 0.80f, h * 0.88f), CornerRadius(w * 0.08f))
        drawRoundRect(Color(0xFF0B0B0B), Offset(w * 0.15f, h * 0.11f), Size(w * 0.70f, h * 0.78f), CornerRadius(w * 0.05f))
        val base = h * 0.80f
        listOf(0.22f, 0.36f, 0.50f).forEachIndexed { i, frac ->
            val bh = h * frac
            drawRoundRect(MolinaTheme.Gold, Offset(w * (0.25f + i * 0.19f), base - bh), Size(w * 0.13f, bh), CornerRadius(w * 0.02f))
        }
    }
}

/** Halaman Statistik tontonan: ringkasan, grafik 7 hari, judul teratas. Semua data lokal. */
@Composable
internal fun WatchStatsPage() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val s = remember(tick) { WatchStats.attach(ctx); WatchStats.snapshot() }
    var on by remember { mutableStateOf(WatchStats.enabled(ctx)) }
    var confirm by remember { mutableStateOf(false) }

    Text(
        "Waktu putar aktif di Molina Cinema (saat dijeda tidak dihitung). Data hanya di perangkat ini, tanpa jaringan.",
        color = W7D.TextDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard("Hari ini", dur(s.todaySec), Modifier.weight(1f))
        StatCard("7 hari", dur(s.weekSec), Modifier.weight(1f))
        StatCard("Total", dur(s.totalSec), Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    Panel {
        Text("7 hari terakhir", color = W7D.NavHeader, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        val max = (s.days.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val slot = size.width / s.days.size
            val barW = slot * 0.56f
            s.days.forEachIndexed { i, (_, sec) ->
                val frac = sec.toFloat() / max
                val bh = if (sec <= 0L) 3.dp.toPx() else (size.height * frac).coerceAtLeast(6.dp.toPx())
                val x = slot * i + (slot - barW) / 2f
                val color = when {
                    sec <= 0L -> Color(0xFF262626)
                    i == s.days.lastIndex -> MolinaTheme.Gold
                    else -> MolinaTheme.Gold.copy(alpha = 0.55f)
                }
                drawRoundRect(color, Offset(x, size.height - bh), Size(barW, bh), CornerRadius(3.dp.toPx()))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            s.days.forEach { (d, _) ->
                Text(
                    LocalDate.ofEpochDay(d).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("id", "ID")),
                    color = W7D.TextDim, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Panel {
        Text("Paling sering ditonton", color = W7D.NavHeader, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("${s.sessions} sesi putar tercatat", color = W7D.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        if (s.top.isEmpty()) {
            Text("Belum ada data. Putar video di Cinema, lalu kembali ke sini.", color = W7D.Ink, fontSize = 13.sp)
        }
        s.top.take(5).forEachIndexed { i, t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = MolinaTheme.Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = W7D.Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${t.plays}x diputar", color = W7D.TextDim, fontSize = 11.sp)
                }
                Text(dur(t.seconds), color = W7D.Ink, fontSize = 13.sp)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Catat statistik tontonan", color = W7D.Ink, fontSize = 14.sp)
                Text("Matikan untuk berhenti mencatat (data lama tetap).", color = W7D.TextDim, fontSize = 11.sp)
            }
            Switch(checked = on, onCheckedChange = { on = it; WatchStats.setEnabled(ctx, it) })
        }
        Spacer(Modifier.height(6.dp))
        TextButton(enabled = s.totalSec > 0L, onClick = { confirm = true }) { Text("HAPUS STATISTIK", color = Red) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Hapus statistik?") },
            text = { Text("Seluruh riwayat waktu tonton di perangkat ini akan dihapus. Tidak memengaruhi riwayat putar atau penanda.") },
            confirmButton = { TextButton(onClick = { confirm = false; WatchStats.clear(); tick++ }) { Text("HAPUS", color = Red) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("BATAL") } }
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier.clip(shape).background(W7D.Card, shape).border(1.dp, W7D.Hairline, shape).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = MolinaTheme.Gold, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = W7D.TextDim, fontSize = 11.sp)
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(W7D.Card, shape).border(1.dp, W7D.Hairline, shape).padding(12.dp)) {
        content()
    }
}

private fun dur(sec: Long): String = when {
    sec < 60 -> "$sec dtk"
    sec < 3600 -> "${sec / 60} mnt"
    else -> "${sec / 3600} j ${(sec % 3600) / 60} mnt"
}

package com.molina.studio.cinema

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.settings.GlossBar
import com.molina.studio.settings.RgbFrame
import com.molina.studio.settings.Win7GlassWindow
import androidx.compose.ui.res.painterResource
import com.molina.studio.R
import com.molina.studio.ui.MolinaTheme

private val OrbTop = Color(0xFF8FC4EE)
private val OrbBottom = Color(0xFF2D6DB5)

/**
 * Beranda Molina Cinema bergaya Windows 7 Aero (serasi dengan Terminal/Unduhan/Monitor):
 * jendela kaca berbingkai RGB (berputar hanya saat live/mencari), kolom cari, 4 ubin aksi,
 * daftar "Terakhir diputar", dan strip status. Logika tetap di CinemaHome.
 */
@Composable
internal fun CinemaWin7Body(
    modifier: Modifier,
    info: String,
    busy: Boolean,
    live: Boolean,
    input: String,
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
    onFile: () -> Unit,
    onChannels: () -> Unit,
    onSettings: () -> Unit,
    onReset: () -> Unit,
    onRecent: (CinemaRecent.Item) -> Unit,
    onTv: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val recents = remember(CinemaRecent.version) { CinemaRecent.load(ctx) }
    Column(modifier.background(MolinaTheme.Background).padding(12.dp)) {
        RgbFrame(modifier = Modifier.weight(1f).fillMaxWidth(), animate = live || busy) {
            Win7GlassWindow(
                title = "Molina Cinema \u2013 Home Theater",
                icon = { TitleOrb() },
                modifier = Modifier.fillMaxSize(),
                fillClient = true
            ) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BrandRow(live)
                    SearchBlock(input, onInput, onSubmit) { onInput(clipText(ctx)) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tile("\uD83D\uDCC4", "Berkas", Modifier.weight(1f), onFile)
                        Tile("\uD83D\uDCFA", "Saluran", Modifier.weight(1f), onChannels)
                        if (onTv != null) Tile("\uD83C\uDFAC", "Mode TV", Modifier.weight(1f), onTv)
                        Tile("\u2699", "Setelan", Modifier.weight(1f), onSettings)
                        Tile("\u21BB", "Segarkan", Modifier.weight(1f), onReset)
                    }
                    recents.firstOrNull()?.let { first -> ResumeCard(first, ResumeBridge.enabled(ctx)) { onRecent(first) } }
                    RecentList(recents, onRecent) { CinemaRecent.clear(ctx) }
                }
                StatusStrip(info, busy)
            }
        }
    }
}

private fun clipText(ctx: Context): String = try {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = cm.primaryClip
    if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(ctx).toString().trim() else ""
} catch (e: Exception) {
    ""
}

@Composable
private fun TitleOrb() {
    Box(
        Modifier.size(16.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(OrbTop, OrbBottom)), CircleShape),
        Alignment.Center
    ) { Text("\u25B6", color = Color.White, fontSize = 8.sp) }
}

@Composable
private fun BrandRow(live: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_cinema_orb), contentDescription = null, modifier = Modifier.size(56.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Molina", color = MolinaTheme.Gold, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("CINEMA \u00B7 HOME THEATER", color = Win7.NavHeader, fontSize = 11.sp, letterSpacing = 2.sp)
        }
        if (live) LiveBadge()
    }
}

@Composable
private fun SearchBlock(input: String, onInput: (String) -> Unit, onSubmit: () -> Unit, onPaste: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(3.dp),
            placeholder = { Text("Cari di YouTube atau tempel tautan", fontSize = 13.sp) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Win7.Ink,
                unfocusedTextColor = Win7.Ink,
                focusedBorderColor = Win7.DefaultLine,
                unfocusedBorderColor = Win7.Line,
                cursorColor = Win7.DefaultLine,
                focusedPlaceholderColor = Win7.TextDim,
                unfocusedPlaceholderColor = Win7.TextDim,
                focusedContainerColor = Win7.Input,
                unfocusedContainerColor = Win7.Input
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            Win7Button("Tempel", onClick = onPaste)
            Win7Button("Putar", isDefault = true, onClick = onSubmit)
        }
    }
}

@Composable
private fun Tile(glyph: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Column(
        modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(Win7.BtnTop, Win7.BtnBottom)), shape)
            .border(1.dp, Win7.BtnLine, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(glyph, color = CinemaPurple, fontSize = 22.sp)
        Spacer(Modifier.height(2.dp))
        Text(label, color = Win7.Ink, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun RecentList(items: List<CinemaRecent.Item>, onPick: (CinemaRecent.Item) -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Terakhir diputar", color = Win7.NavHeader, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
            )
            if (items.isNotEmpty()) {
                Text("Hapus", color = Win7.TextDim, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onClear).padding(4.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Win7.Hairline))
        if (items.isEmpty()) {
            Text("Belum ada riwayat putar.", color = Win7.TextDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
        } else {
            items.forEach { it ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(it) }.padding(vertical = 6.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Win7File(video = it.video, box = 28.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(it.title, color = Win7.Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (it.subtitle.isNotEmpty()) {
                            Text(it.subtitle, color = Win7.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusStrip(info: String, busy: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Win7.Hairline))
        Spacer(Modifier.height(6.dp))
        if (busy) {
            GlossBar(fraction = null, top = OrbTop, bottom = OrbBottom, height = 10.dp)
            Spacer(Modifier.height(6.dp))
        }
        Text(info, color = Win7.TextDim, fontSize = 12.sp)
    }
}


/** Satu ketukan untuk melanjutkan putaran terakhir; teks bawah menyesuaikan setelan "ingat posisi". */
@Composable
private fun ResumeCard(item: CinemaRecent.Item, remembered: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color(0x22E6B450), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable(onClick = onPick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("\u25B6", color = MolinaTheme.Gold, fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Lanjutkan", color = MolinaTheme.Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(item.title, color = Win7.Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (remembered) "Dilanjutkan dari posisi terakhir" else "Diputar dari awal (aktifkan Lanjutkan di Settings > Otomasi)",
                color = Win7.TextDim, fontSize = 11.sp
            )
        }
    }
}

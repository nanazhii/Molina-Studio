package com.molina.studio.cinema

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.core.ui.GlossBar
import com.molina.core.ui.RgbFrame
import com.molina.core.ui.RgbSpot
import com.molina.core.ui.Win7GlassWindow
import androidx.compose.ui.res.painterResource
import com.molina.studio.R
import com.molina.studio.ui.MolinaTheme
import com.molina.core.ui.Win7
import com.molina.core.ui.win7ButtonSurface
import com.molina.core.ui.Win7Button
import com.molina.core.ui.Win7IconButton
import com.molina.core.ui.Win7PasteGlyph
import com.molina.core.ui.Win7File

private val OrbTop = MolinaTheme.AeroBlueTop
private val OrbBottom = MolinaTheme.AeroBlueBottom

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
    onTv: (() -> Unit)? = null,
    refreshing: Boolean = false
) {
    val ctx = LocalContext.current
    val recents = remember(CinemaRecent.version) { CinemaRecent.load(ctx) }
    Column(modifier.background(MolinaTheme.Background).padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp)) { // [CINEMA-FULL] [CINEMA-TOP] bingkai dirapatkan ke status bar (dulu top = 12.dp)
        RgbFrame(modifier = Modifier.weight(1f).fillMaxWidth(), animate = live || busy, spot = RgbSpot.Cinema) {
            Win7GlassWindow(
                title = "Molina Cinema \u2013 Home Theater",
                icon = { TitleOrb() },
                modifier = Modifier.fillMaxSize(),
                fillClient = true,
                fullscreen = true // [CINEMA-FULL]
            ) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BrandRow(live)
                    SearchBlock(input, onInput, onSubmit) { onInput(clipText(ctx)) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconTile({ CinemaFileOrb() }, "Berkas", Modifier.weight(1f), onFile) // [ORB-SET]
                        IconTile({ CinemaChannelsOrb() }, "Saluran", Modifier.weight(1f), onChannels)
                        if (onTv != null) IconTile({ CinemaTvOrb() }, "Mode TV", Modifier.weight(1f), onTv)
                        IconTile({ CinemaSettingsOrb() }, "Setelan", Modifier.weight(1f), onSettings) // [CINEMA-ICONS]
                        IconTile({ CinemaRefreshOrb(spinning = refreshing) }, "Segarkan", Modifier.weight(1f), onReset)
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
    // [TITLE-ORB] Digambar di Canvas: glyph teks segitiga ikut metrik font sehingga turun dan tidak di tengah.
    Canvas(Modifier.size(18.dp)) {
        val r = size.minDimension / 2f
        val c = center
        drawCircle(Brush.verticalGradient(listOf(OrbTop, OrbBottom)), radius = r, center = c)
        drawCircle(Color.White.copy(alpha = 0.45f), radius = r - 0.5.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))
        // Titik berat segitiga ada 1/3 dari alas; geser kotaknya w/6 ke kanan agar tampak di tengah.
        val w = r * 0.82f
        val h = r * 0.98f
        val x0 = c.x - w / 2f + w / 6f
        val tri = Path().apply {
            moveTo(x0, c.y - h / 2f); lineTo(x0, c.y + h / 2f); lineTo(x0 + w, c.y); close()
        }
        drawPath(tri, Color.White)
    }
}

@Composable
private fun BrandRow(live: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_cinema_orb), contentDescription = null, modifier = Modifier.size(56.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Molina", color = MolinaTheme.Brand, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("CINEMA \u00B7 HOME THEATER", color = Win7.NavHeader, fontSize = 11.sp, letterSpacing = 2.sp)
        }
        if (live) LiveBadge()
    }
}

@Composable
private fun SearchBlock(input: String, onInput: (String) -> Unit, onSubmit: () -> Unit, onPaste: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CinemaSearchField(input, onInput, onSubmit) // [CINEMA-FIELD] 40dp (OutlinedTextField M3 minimal 56dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            Win7IconButton("Tempel", onClick = onPaste) { Win7PasteGlyph() } // [ICON-BTN] ikon saja, hemat ruang
            Win7Button("Putar", isDefault = true, onClick = onSubmit)
        }
    }
}

/** [CINEMA-FIELD] Kolom cari/tempel tautan setinggi 40dp, gaya Win7 (garis fokus DefaultLine). */
@Composable
private fun CinemaSearchField(value: String, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier.fillMaxWidth().height(40.dp).clip(shape).background(Win7.Input, shape)
            .border(1.dp, if (focused) Win7.DefaultLine else Win7.Line, shape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Win7.Ink, fontSize = 14.sp),
            cursorBrush = SolidColor(Win7.DefaultLine),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), Alignment.CenterStart) {
                    if (value.isEmpty()) Text(
                        "Cari di YouTube atau tempel tautan", color = Win7.TextDim, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    inner()
                }
            }
        )
    }
}

/** Ubin aksi: slot ikon setinggi 28dp (emoji maupun ikon Canvas) supaya semua ubin sama tinggi. */
@Composable
private fun IconTile(icon: @Composable () -> Unit, label: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    val src = remember { MutableInteractionSource() } // [TILE-PRESS]
    val pressed by src.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.94f else 1f, label = "tilePress")
    Column(
        modifier.graphicsLayer { scaleX = press; scaleY = press }.clip(shape)
            .win7ButtonSurface(shape)
            .clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.height(28.dp), Alignment.Center) { icon() }
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
            .background(MolinaTheme.Cinema.copy(alpha = 0.133f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) // [CINEMA-TOKEN] aksen putar = ungu
            .clickable(onClick = onPick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("\u25B6", color = MolinaTheme.Cinema, fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Lanjutkan", color = MolinaTheme.Cinema, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(item.title, color = Win7.Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (remembered) "Dilanjutkan dari posisi terakhir" else "Diputar dari awal (aktifkan Lanjutkan di Settings > Otomasi)",
                color = Win7.TextDim, fontSize = 11.sp
            )
        }
    }
}

package com.molina.studio.settings

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.cinema.Win7
import com.molina.studio.cinema.Win7BackOrb
import com.molina.studio.ui.MolinaTheme

private val W7LinkColor = Color(0xFF6CB4FF)

/** Palet Aero Gelap: hitam pekat (OLED) dengan aksen biru Aero. */
internal object W7D {
    val Bg = Color(0xFF000000)
    val Accent = Color(0xFF6CB4FF)
    val Card = Color(0xFF0B0B0B)
    val BarTop = Color(0xFF151515)
    val BarBottom = Color(0xFF060606)
    val Field = Color(0xFF101010)
    val Ink = Color(0xFFEDEDED)
    val TextDim = Color(0xFFA3A3A3)
    val NavHeader = Color(0xFF8FC4EE)
    val Hairline = Color(0xFF262626)
    val Line = Color(0xFF3A3A3A)
    val SelFill = Color(0xFF12263A)
    val SelLine = Color(0xFF3C7FB1)
}

/** Skema warna gelap Aero: dipasang di dalam jendela Settings agar semua halaman ikut hitam pekat. */
internal val Win7Scheme = darkColorScheme(
    primary = Color(0xFF6CB4FF), onPrimary = Color.Black,
    primaryContainer = W7D.SelFill, onPrimaryContainer = Color(0xFFD9EBF9),
    secondary = Color(0xFF3C7FB1), onSecondary = Color.White,
    background = W7D.Bg, onBackground = W7D.Ink,
    surface = W7D.Bg, onSurface = W7D.Ink,
    surfaceVariant = Color(0xFF151515), onSurfaceVariant = W7D.TextDim,
    outline = W7D.Line, outlineVariant = W7D.Hairline,
    surfaceContainerHigh = Color(0xFF101010), surfaceContainerHighest = Color(0xFF181818)
)

/**
 * Jendela Settings ala Panel Kontrol Windows 7: tombol kembali bulat, bilah alamat breadcrumb,
 * kotak cari, dan bilah perintah. [dark] = area isi memakai tema gelap bawaan (Log, pratinjau terminal).
 */
@Composable
internal fun Win7SettingsWindow(
    modifier: Modifier,
    crumbs: List<String>,
    canUp: Boolean,
    onUp: () -> Unit,
    onCrumb: (Int) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    dark: Boolean,
    actions: @Composable () -> Unit,
    showHeader: Boolean = true,
    content: @Composable () -> Unit
) {
    val outer = MaterialTheme.colorScheme
    val typo = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    MaterialTheme(colorScheme = Win7Scheme, typography = typo, shapes = shapes) {
        Surface(modifier, color = W7D.Bg, contentColor = W7D.Ink) {
            Column(Modifier.fillMaxSize()) {
                if (showHeader) W7Header(crumbs, canUp, onUp, onCrumb, query, onQuery, actions)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (dark) {
                        MaterialTheme(colorScheme = outer, typography = typo, shapes = shapes) {
                            Surface(Modifier.fillMaxSize(), color = MolinaTheme.Background, contentColor = outer.onBackground) {
                                content()
                            }
                        }
                    } else {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun W7Header(
    crumbs: List<String>,
    canUp: Boolean,
    onUp: () -> Unit,
    onCrumb: (Int) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    actions: @Composable () -> Unit
) {
    val focus = LocalFocusManager.current
    // Kolom cari hanya boleh fokus setelah disentuh pengguna (cegah fokus otomatis + keyboard).
    val touched = remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(W7D.BarTop, W7D.BarBottom)))
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Win7BackOrb(canUp, onUp)
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.weight(1f).height(28.dp).background(W7D.Field)
                    .border(1.dp, W7D.Field).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val from = if (crumbs.size > 2) crumbs.size - 2 else 0
                if (from > 0) Text("\u2026 \u203A ", color = W7D.TextDim, fontSize = 13.sp)
                for (i in from until crumbs.size) {
                    if (i > from) Text(" \u203A ", color = W7D.TextDim, fontSize = 13.sp)
                    Text(
                        crumbs[i],
                        color = if (i == crumbs.lastIndex) W7D.Ink else W7D.NavHeader,
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clickable { onCrumb(i) }
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = W7D.Ink, fontSize = 13.sp),
                cursorBrush = SolidColor(W7D.Ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.weight(1f).height(30.dp).background(W7D.Field)
                    .border(1.dp, W7D.Field).padding(horizontal = 8.dp)
                    .onFocusChanged { if (!it.isFocused) touched.value = false }
                    .focusProperties { canFocus = touched.value }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                if (e.changes.any { c -> c.pressed }) touched.value = true
                            }
                        }
                    },
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f), Alignment.CenterStart) {
                            if (query.isEmpty()) Text("Cari setelan", color = W7D.TextDim, fontSize = 13.sp)
                            inner()
                        }
                        if (query.isNotEmpty()) {
                            Text(
                                "\u00D7", color = W7D.TextDim, fontSize = 20.sp,
                                modifier = Modifier.clickable { onQuery("") }.padding(horizontal = 6.dp)
                            )
                        }
                        W7Magnifier()
                    }
                }
            )
            actions()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(W7D.Hairline))
    }
}

@Composable
private fun W7Magnifier() {
    Canvas(Modifier.size(14.dp)) {
        val s = 1.6.dp.toPx()
        val w = size.width
        drawCircle(W7D.NavHeader, radius = w * 0.32f, center = Offset(w * 0.38f, w * 0.38f), style = Stroke(s))
        drawLine(W7D.NavHeader, Offset(w * 0.62f, w * 0.62f), Offset(w * 0.95f, w * 0.95f), s, StrokeCap.Round)
    }
}

@Composable
internal fun W7Heading(text: String) {
    Text(
        text, color = MaterialTheme.colorScheme.primary, fontSize = 20.sp,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

/** Satu entri daftar ala Panel Kontrol: ikon, judul tautan biru, keterangan abu-abu. */
@Composable
internal fun W7Link(icon: @Composable () -> Unit, title: String, summary: String, onClick: () -> Unit) {
    Win7FrameBox(selected = false, onClick = onClick, modifier = Modifier.padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = W7LinkColor, fontSize = 15.sp)
                Text(summary, color = W7D.TextDim, fontSize = 12.sp)
            }
        }
    }
}

/** Ubin kategori beranda Settings. [selected] = kategori terakhir dibuka: bingkai biru tipis ala Windows 7. */
@Composable
internal fun W7CategoryTile(
    icon: @Composable () -> Unit,
    title: String,
    links: List<Pair<String, () -> Unit>>,
    onOpen: () -> Unit,
    selected: Boolean = false,
    badge: String? = null,
    badgeAero: Boolean = false,
    badgeGold: Boolean = false
) {
    Win7FrameBox(selected = selected, onClick = onOpen, modifier = Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Top) {
            icon()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = W7LinkColor, fontSize = 18.sp)
                links.forEach { l ->
                    Text(l.first, color = W7LinkColor, fontSize = 13.sp, modifier = Modifier.clickable(onClick = l.second).padding(top = 6.dp))
                }
            }
            if (badge != null) W7Badge(badge, badgeAero, badgeGold)
        }
    }
}

/** Panel "informasi dasar" ala System Windows 7. Dibaca sekali saat tampil. */
@Composable
internal fun W7SystemSummary() {
    val ctx = LocalContext.current
    val rows = remember {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        listOf(
            "Molina Studio" to ver,
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Perangkat" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Arsitektur" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "?"),
            "RAM" to "${mi.availMem / 1048576} MB tersedia dari ${mi.totalMem / 1048576} MB"
        )
    }
    val shape = RoundedCornerShape(3.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, W7D.Hairline, shape).background(W7D.Card)) {
        Text(
            "Lihat informasi dasar tentang perangkat Anda", color = W7D.NavHeader, fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(W7D.BarTop, W7D.BarBottom)))
                .padding(horizontal = 10.dp, vertical = 7.dp)
        )
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(r.first, color = W7D.TextDim, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                Text(r.second, color = W7D.Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun W7Badge(top: Color, bottom: Color, label: String) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier.size(34.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(top, bottom)), shape)
            .border(1.dp, Color(0x55000000), shape),
        Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = if (label.length > 2) 9.sp else 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable internal fun W7IconTerminal() = W7Badge(Color(0xFF505050), Color(0xFF202020), ">_")
@Composable internal fun W7IconLog() = W7Badge(Color(0xFF8FC4EE), Color(0xFF2D6DB5), "LOG")
@Composable internal fun W7IconYtdlp() = W7Badge(Color(0xFFE8776B), Color(0xFFB3261E), "yt")
@Composable internal fun W7IconInfo() = W7Badge(Color(0xFF7FC57A), Color(0xFF3F8F3A), "i")
@Composable internal fun W7IconHit() = W7Badge(Color(0xFF8FC4EE), Color(0xFF2D6DB5), "\u203A")

@Composable
internal fun W7IconShield() {
    Canvas(Modifier.size(34.dp)) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.5f, h * 0.06f); lineTo(w * 0.88f, h * 0.20f); lineTo(w * 0.82f, h * 0.58f)
            quadraticBezierTo(w * 0.70f, h * 0.84f, w * 0.5f, h * 0.95f)
            quadraticBezierTo(w * 0.30f, h * 0.84f, w * 0.18f, h * 0.58f)
            lineTo(w * 0.12f, h * 0.20f); close()
        }
        drawPath(p, Brush.verticalGradient(listOf(Color(0xFF8FD18A), Color(0xFF3F8F3A))))
        drawPath(p, Color(0xFF2E6B2A), style = Stroke(width = 1.5f))
    }
}

@Composable
internal fun W7IconMonitor(box: Dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        val h = size.height
        drawRoundRect(Color(0xFF444444), Offset(w * 0.06f, h * 0.10f), Size(w * 0.88f, h * 0.60f), CornerRadius(w * 0.06f))
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF8FC4EE), Color(0xFF2D6DB5))),
            Offset(w * 0.11f, h * 0.16f), Size(w * 0.78f, h * 0.48f), CornerRadius(w * 0.03f)
        )
        drawRect(Color(0xFF8A94A0), Offset(w * 0.42f, h * 0.70f), Size(w * 0.16f, h * 0.12f))
        drawRoundRect(Color(0xFF6E7884), Offset(w * 0.25f, h * 0.82f), Size(w * 0.50f, h * 0.08f), CornerRadius(w * 0.03f))
    }
}

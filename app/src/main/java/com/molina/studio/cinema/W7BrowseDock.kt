package com.molina.studio.cinema

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.ui.MolinaTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [DROP-MENU] Keterangan item/folder terpilih di ATAS daftar (bekas bilah ikon), tinggi tetap, tiap teks satu baris:
 * ikon/thumbnail + nama (ellipsis di tengah, ekstensi tetap terbaca) + ukuran/tanggal, lalu path (ujung kanan
 * dipertahankan; ketuk untuk menyalin).
 */
@Composable
internal fun W7BrowseInfo(
    files: List<java.io.File>,
    isDir: Boolean?,
    size: Long,
    modified: Long,
    onCopyPath: () -> Unit
) {
    val locale = Locale("id", "ID")
    val one = files.singleOrNull()
    val title = when {
        files.size > 1 -> files.size.toString() + " item dipilih"
        one != null -> dockMid(one.name.ifEmpty { one.path }, 34)
        else -> ""
    }
    val meta = when {
        files.size > 1 -> "Ketuk Properti untuk ukuran total"
        isDir == null -> "Folder saat ini"
        isDir -> "Folder  \u00B7  " + SimpleDateFormat("dd/MM/yyyy HH.mm", locale).format(Date(modified))
        else -> String.format(locale, "%,d KB", maxOf(1L, (size + 1023) / 1024)) +
            "  \u00B7  " + SimpleDateFormat("dd/MM/yyyy HH.mm", locale).format(Date(modified))
    }
    val path = if (files.size == 1) dockTail(files[0].path, 48) else dockTail(files.firstOrNull()?.parent ?: "", 48)
    val thumbFile = one?.takeIf { W7Thumb.hasThumb(it) }
    // Thumbnail dimuat di IO setelah jeda 250 ms agar ganti seleksi cepat tidak memicu dekode sia-sia
    val thumb: ImageBitmap? by produceState<ImageBitmap?>(null, thumbFile?.path) {
        value = null
        if (thumbFile != null) {
            delay(250)
            value = W7Thumb.load(thumbFile)?.asImageBitmap()
        }
    }
    Column(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Win7.BarTop, Win7.BarBottom)))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            if (thumbFile != null) {
                Box(
                    Modifier.size(width = if (W7Thumb.isAudio(thumbFile)) 30.dp else 52.dp, height = 30.dp).clip(RoundedCornerShape(2.dp))
                        .background(Win7.Input).border(1.dp, Win7.Hairline, RoundedCornerShape(2.dp)),
                    Alignment.Center
                ) {
                    val bmp = thumb
                    if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Text(if (W7Thumb.isAudio(thumbFile)) "\u266A" else "\u25B6", color = MolinaTheme.Cinema.copy(alpha = 0.7f), fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
            }
            if (thumbFile == null && one != null) {
                if (one.isDirectory) Win7Folder(26.dp) else W7FileIcon(one.extension, 28.dp)
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = Win7.Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta, color = Win7.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            path, color = Win7.TextDim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Clip,
            modifier = Modifier.fillMaxWidth().height(16.dp).clickable(onClick = onCopyPath)
        )
    }
}

/**
 * [FILES-DOCK] Panel bawah Penjelajah (mode jelajah): tinggi TETAP.
 * Baris 1: label Mode Pilih (kiri), bantuan, status Debian ringkas, lalu slot [menu] (ikon kisi drop-up) di ujung kanan.
 * Baris 2: aksi (Aksi cepat, Sisipkan, Terminal, Buka) dengan bobot sama, tanpa lebar minimum.
 */
@Composable
internal fun W7BrowseDock(
    debian: Boolean?,
    quickLabel: String,
    insertEnabled: Boolean,
    terminalEnabled: Boolean,
    onQuick: () -> Unit,
    onInsert: () -> Unit,
    onTerminal: () -> Unit,
    onOpen: () -> Unit,
    modeLabel: String? = null,
    onHelp: (() -> Unit)? = null,
    menu: @Composable () -> Unit = {}
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                modeLabel.orEmpty(), color = Win7.NavHeader, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (onHelp != null) W7HelpIcon(onHelp) // [HELP-PANEL]
            if (debian != null) {
                val tone = if (debian) MolinaTheme.Good else MolinaTheme.Warn
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(8.dp).background(tone, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(4.dp))
                Text(if (debian) "Debian" else "Luar", color = tone, fontSize = 10.sp, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            menu()
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DockButton(quickLabel, Modifier.weight(1f), true, false, null, onQuick)
            DockButton("Sisipkan", Modifier.weight(1f), insertEnabled, false, null, onInsert)
            DockButton("Terminal", Modifier.weight(1.1f), terminalEnabled, false, MolinaTheme.Good, onTerminal)
            DockButton("Buka", Modifier.weight(0.9f), true, true, MolinaTheme.Gold, onOpen)
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

/** Potong di tengah supaya ekstensi tetap terlihat: "film_panjang...mu.mkv". */
private fun dockMid(s: String, max: Int): String {
    if (s.length <= max) return s
    val head = (max - 1) * 2 / 3
    val tail = max - 1 - head
    return s.substring(0, head) + "\u2026" + s.substring(s.length - tail)
}

/** Pertahankan ujung path (bagian paling informatif). */
private fun dockTail(s: String, max: Int): String = if (s.length <= max) s else "\u2026" + s.substring(s.length - (max - 1))

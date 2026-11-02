package com.molina.studio.cinema

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.core.common.ShelfCard
import com.molina.studio.ui.MolinaTheme
import java.io.File

private val CardW = 104.dp
private val PosterShape = RoundedCornerShape(6.dp)

/**
 * Kartu poster 2:3 Molina Cinema, dipakai Mode TV ([TvBrowser]). Poster/metadata datang dari pendamping
 * `molina_dl --sidecar` (MediaSidecar); gambar dimuat W7Thumb (poster sidecar diprioritaskan, kembali ke
 * frame video). Kartu yang pernah dilanjutkan mpv diberi garis emas tipis di tepi bawah poster (panjangnya
 * = posisi/durasi). Tekan lama = [onLong], ketuk = [onClick].
 */
@Composable
internal fun ShelfCardView(card: ShelfCard.Card, onLong: () -> Unit = {}, onClick: () -> Unit) {
    val thumb: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, card.thumbPath) {
        value = card.thumbPath?.let { W7Thumb.load(File(it)) }?.asImageBitmap()
    }
    Column(
        Modifier.width(CardW).pointerInput(card.ref) { detectTapGestures(onLongPress = { onLong() }, onTap = { onClick() }) }
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(PosterShape)
                .background(Brush.verticalGradient(listOf(Color(0xFF241B2B), Color(0xFF0E0B10))), PosterShape)
                .border(1.dp, if (card.hasPoster) MolinaTheme.Gold else Win7.Line, PosterShape),
            Alignment.Center
        ) {
            val img = thumb
            if (img != null) {
                Image(img, contentDescription = card.title, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            } else {
                Text(card.monogram, color = MolinaTheme.Gold, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            if (card.resumeSec != null) {
                // Aksen "sudah pernah dilanjutkan": jalur redup + garis emas sepanjang posisi/durasi.
                val f = card.resumeFraction
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66000000)))
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth(f ?: 1f).height(3.dp)
                        .background(MolinaTheme.Gold.copy(alpha = if (f == null) 0.55f else 1f))
                )
            }
            card.duration?.let {
                Text(
                    it, color = Color.White, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = if (card.resumeSec != null) 7.dp else 4.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(card.title, color = Win7.Ink, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (card.subtitle.isNotEmpty()) {
            Text(card.subtitle, color = Win7.TextDim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * [CINEMA-SHELF-BRAND] Sampul bulat kanal untuk judul baris rak: poster/frame video terbaru di baris itu
 * (lewat W7Thumb, poster sidecar diprioritaskan); kembali ke monogram nama kanal bila belum ada gambar.
 */
@Composable
internal fun ChannelCover(name: String, card: ShelfCard.Card?, diameter: Dp = 28.dp) {
    val thumb: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, card?.thumbPath) {
        value = card?.thumbPath?.let { W7Thumb.load(File(it)) }?.asImageBitmap()
    }
    Box(
        Modifier.size(diameter).clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF241B2B), Color(0xFF0E0B10))), CircleShape)
            .border(1.dp, MolinaTheme.Gold, CircleShape),
        Alignment.Center
    ) {
        val img = thumb
        if (img != null) {
            Image(img, contentDescription = name, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        } else {
            Text(ShelfCard.monogram(name), color = MolinaTheme.Gold, fontSize = (diameter.value * 0.46f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Lencana jumlah video pada judul baris rak kanal; teks dari [com.molina.core.common.TvGuide.badge]. */
@Composable
internal fun CountBadge(text: String) {
    Text(
        text, color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MolinaTheme.Gold).padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

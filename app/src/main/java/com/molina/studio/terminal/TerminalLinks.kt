package com.molina.studio.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.studio.cinema.CinemaActions
import com.molina.studio.cinema.Win7
import com.molina.studio.cinema.Win7Button
import com.molina.studio.settings.Win7GlassWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dialog "Tautan di layar": daftar URL dari transkrip Terminal (terbaru di atas), masing-masing bisa
 * diputar di Molina Cinema, dibuka di peramban, disalin, atau disisipkan ke prompt (tanpa Enter).
 * Transkrip dibaca di main thread; pemindaian regex berjalan di Dispatchers.Default.
 */
@Composable
internal fun LinksDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember { TerminalSessions.currentSession?.emulator?.screen?.transcriptText.orEmpty() }
    val links by produceState<List<String>?>(null, text) {
        value = withContext(Dispatchers.Default) { TerminalLinkScan.extract(text) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Win7GlassWindow(
                title = "Tautan di layar",
                icon = { Text("\u2197", color = Color.White, fontSize = 12.sp) }
            ) {
                val list = links
                if (list == null) {
                    Text("Memindai transkrip\u2026", color = Win7.TextDim, fontSize = 12.sp)
                } else if (list.isEmpty()) {
                    Text(
                        "Tidak ada tautan http/https/rtsp/rtmp di transkrip Terminal.",
                        color = Win7.TextDim, fontSize = 12.sp
                    )
                } else {
                    Text("${list.size} tautan, terbaru di atas.", color = Win7.TextDim, fontSize = 12.sp)
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        list.forEach { url -> LinkRow(ctx, url, onDismiss) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Win7Button("Tutup", onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun LinkRow(ctx: Context, url: String, onDismiss: () -> Unit) {
    val isWeb = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            url, color = Win7.Ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            maxLines = 3, overflow = TextOverflow.Ellipsis
        )
        Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.End) {
            LinkBtn("Putar") {
                val input = CinemaActions.classify(url)
                val ok = input is CinemaActions.Input.Direct && CinemaActions.play(ctx, input.uri)
                if (ok) onDismiss() else toast(ctx, "Molina Cinema tidak dapat memutar tautan ini")
            }
            if (isWeb) LinkBtn("Buka") {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(intent); onDismiss() } catch (e: Exception) { toast(ctx, "Tidak ada peramban untuk membuka tautan") }
            }
            LinkBtn("Salin") {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("molina-terminal-url", url))
                toast(ctx, "Tautan disalin")
            }
            LinkBtn("Sisip") { TerminalActions.insert(url); onDismiss() }
        }
    }
}

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

@Composable
private fun LinkBtn(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier.padding(start = 4.dp).height(28.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(Win7.BtnTop, Win7.BtnBottom)), shape)
            .border(1.dp, Win7.BtnLine, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        Alignment.Center
    ) { Text(label, color = Win7.Ink, fontSize = 12.sp) }
}

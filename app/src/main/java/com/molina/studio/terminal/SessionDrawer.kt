package com.molina.studio.terminal

import com.molina.studio.ui.MolinaTheme
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.ui.SwipeDirection
import com.molina.studio.ui.onHorizontalSwipe

private val Gold = MolinaTheme.Gold
private val CloseRed = Color(0xFFFF8A80)
private val DrawerWidth = 240.dp
private val DrawerButtonWidth = 100.dp
private val DrawerButtonTextSp = 8.sp

/**
 * Drawer sesi ala Termux: daftar sesi [1] [2] ... (maks. TerminalSessions.MAX_SESSIONS),
 * tombol KEYBOARD dan NEW SESSION, tombol pengaturan, serta tombol ✕ per sesi
 * untuk menutup (kill) tab. Dibuka lewat tombol ≡ (tap atau swipe); ditutup dengan
 * tap latar gelap, swipe ke kiri, atau tombol Back.
 */
@Composable
fun SessionDrawer(open: Boolean, onClose: () -> Unit, onOpenSettings: () -> Unit) {
    if (!open) return
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf<Int?>(null) }
    val swallow = remember { MutableInteractionSource() }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize()) {
        // Latar gelap: tap menutup drawer.
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() },
                    indication = null, onClick = onClose)
        )
        Column(
            Modifier.fillMaxHeight().width(DrawerWidth)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(interactionSource = swallow, indication = null, onClick = {})
                .onHorizontalSwipe(22.dp, SwipeDirection.Left) { onClose() }
        ) {
            // Header: pengaturan + penghitung sesi.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(44.dp).clickable { onClose(); onOpenSettings() },
                    contentAlignment = Alignment.Center
                ) { Text("\u2699", fontSize = 22.sp, color = Gold) }
                Spacer(Modifier.weight(1f))
                Text(
                    "${TerminalSessions.sessions.size}/${TerminalSessions.MAX_SESSIONS}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            // Daftar sesi.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                val tick = TerminalSessions.titleTick
                TerminalSessions.sessions.forEachIndexed { i, s ->
                    val selected = i == TerminalSessions.currentIndex
                    val title = remember(tick, s) {
                        TerminalSessions.labelOf(s) ?: s.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Debian"
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (selected) Gold.copy(alpha = 0.16f) else Color.Transparent),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "[${i + 1}] $title",
                            modifier = Modifier.weight(1f)
                                .clickable { TerminalSessions.select(i); onClose() }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            color = if (selected) Gold else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Box(
                            Modifier.size(48.dp).clickable { confirm = i },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("\u2715", color = CloseRed, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Tombol bawah: KEYBOARD dan NEW SESSION.
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                DrawerButton("KEYBOARD", true, Modifier.width(DrawerButtonWidth)) {
                    onClose()
                    toggleKeyboard(ctx)
                }
                DrawerButton("NEW SESSION", TerminalSessions.canAdd(), Modifier.width(DrawerButtonWidth)) {
                    when {
                        RootfsManager.state.value !is RootfsState.Ready ->
                            toast(ctx, "Debian belum terpasang")
                        !TerminalSessions.canAdd() ->
                            toast(ctx, "Maksimal ${TerminalSessions.MAX_SESSIONS} sesi")
                        else -> { TerminalSessions.newSession(ctx); onClose() }
                    }
                }
            }
        }
    }

    confirm?.let { i ->
        val last = TerminalSessions.sessions.size <= 1
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Tutup sesi [${i + 1}]?") },
            text = {
                Text(
                    if (last) "Ini sesi terakhir: proses di dalamnya dihentikan dan Molina keluar."
                    else "Proses yang berjalan di sesi ini akan dihentikan."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    TerminalSessions.closeSession(i)
                }) { Text("Tutup", color = CloseRed) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Batal") } }
        )
    }
}

@Composable
private fun DrawerButton(label: String, enabledLook: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val a = if (enabledLook) 1f else 0.4f
    Box(
        modifier
            .clip(shape)
            .background(Gold.copy(alpha = 0.12f * a), shape)
            .border(1.dp, Gold.copy(alpha = 0.6f * a), shape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Gold.copy(alpha = a), fontSize = DrawerButtonTextSp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

/** Tampilkan atau sembunyikan keyboard layar untuk terminal aktif. */
private fun toggleKeyboard(ctx: Context) {
    val v = TerminalSessions.client.view ?: return
    if (!TerminalPrefs.softKeyboardAllowed(ctx)) {
        toast(ctx, "Soft keyboard dinonaktifkan di Settings")
        return
    }
    v.requestFocus()
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
}

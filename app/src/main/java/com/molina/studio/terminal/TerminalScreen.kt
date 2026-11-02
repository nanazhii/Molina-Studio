package com.molina.studio.terminal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.Toast
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.ui.onHorizontalSwipe
import androidx.compose.ui.viewinterop.AndroidView
import com.molina.terminal.view.TerminalView
import kotlin.math.floor

private fun spToPx(ctx: Context, sp: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), ctx.resources.displayMetrics).toInt()

/** Lebar satu sel monospace pada ukuran font tertentu (sama seperti TerminalRenderer). */
private fun cellWidthPx(ctx: Context, sp: Int): Float =
    Paint().apply { typeface = Typeface.MONOSPACE; textSize = spToPx(ctx, sp).toFloat() }.measureText("X")

@Composable
fun TerminalScreen(modifier: Modifier = Modifier, onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
    var granted by remember {
        mutableStateOf(context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = true // ditolak pun lanjut (cwd fallback)
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(perm) }

    if (!granted) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Meminta izin penyimpanan...", color = MaterialTheme.colorScheme.onSurface)
        }
    } else {
        TerminalContent(modifier, onOpenSettings)
    }
}

@Composable
private fun TerminalContent(modifier: Modifier, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val mods = remember { TerminalModifiers() }
    remember(context) { RootfsManager.refresh(context) }
    val rootfsState by RootfsManager.state.collectAsState()
    var drawerOpen by remember { mutableStateOf(false) }

    Box(modifier) {
    Column(Modifier.fillMaxSize()) {
        TerminalHeader(onOpenSettings)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (rootfsState !is RootfsState.Ready) {
                SetupPanel(
                    rootfsState,
                    onInstall = { RootfsManager.install(context) },
                    onCancel = { RootfsManager.cancel() }
                )
            } else {
                val fontSp = TerminalPrefs.fontSp
                val density = LocalDensity.current
                // Terminal Margin Adjustment: pusatkan kolom penuh, sisa lebar dibagi dua sisi.
                val cell = remember(fontSp) { cellWidthPx(context, fontSp) }
                val marginDp = if (TerminalPrefs.marginAdjustment && cell > 0f) {
                    val w = constraints.maxWidth.toFloat() - 2f * with(density) { 6.dp.toPx() } // bingkai viewport 4dp + 1dp + 1dp
                    val rest = w - floor(w / cell) * cell
                    with(density) { (rest / 2f).toDp() }
                } else 0.dp
                AndroidView(
                    modifier = Modifier.fillMaxSize()
                        .padding(4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(6.dp))
                        .padding(1.dp)
                        .padding(horizontal = marginDp),
                    factory = { ctx -> createTerminalView(ctx, mods) },
                    update = { v ->
                        v.keepScreenOn = TerminalUi.keepScreenOn
                        val sp = TerminalPrefs.fontSp
                        if (v.tag != sp) {
                            v.tag = sp
                            v.setTextSize(spToPx(v.context, sp))
                        }
                        // Pindah sesi (tab) dari drawer.
                        val target = TerminalSessions.active
                        if (target != null && v.currentSession !== target) {
                            v.attachSession(target)
                            v.requestFocus()
                            v.onScreenUpdated()
                        }
                    },
                    onRelease = { v ->
                        if (TerminalSessions.client.view === v) TerminalSessions.client.view = null
                    }
                )
            }
        }
        ExtraKeys(mods) { drawerOpen = true }
    }
    SessionDrawer(
        open = drawerOpen,
        onClose = { drawerOpen = false },
        onOpenSettings = onOpenSettings
    )
    }
}

private val KeyRed = Color(0xFFFF8A80)    // ESC, CLEAR
private val KeyBlue = Color(0xFF82B1FF)   // HOME, panah
private val KeyGreen = Color(0xFF69F0AE)  // COPY, PASTE
private val KeyAmber = Color(0xFFFFD54F)  // CTRL, ALT, TAB, menu

private fun createTerminalView(ctx: Context, mods: TerminalModifiers): TerminalView {
    val view = TerminalView(ctx, null)
    val session = TerminalSessions.ensureSession(ctx)
    TerminalSessions.client.view = view
    view.setTerminalViewClient(MolinaViewClient(view, mods))
    view.tag = TerminalPrefs.fontSp
    view.setTextSize(spToPx(ctx, TerminalPrefs.fontSp))
    view.setTypeface(Typeface.MONOSPACE)
    view.isFocusable = true
    view.isFocusableInTouchMode = true
    view.attachSession(session)
    view.requestFocus()
    return view
}

@Composable
private fun ExtraKeys(mods: TerminalModifiers, onOpenDrawer: () -> Unit) {
    val ctx = LocalContext.current
    val menu = remember {
        listOf("/", "|", "~", "\\", "_", "&").map { sym -> sym to { TerminalActions.send(sym) } } +
            listOf(
                "END" to { TerminalActions.sendKey(KeyEvent.KEYCODE_MOVE_END) },
                "PGUP" to { TerminalActions.sendKey(KeyEvent.KEYCODE_PAGE_UP) },
                "PGDN" to { TerminalActions.sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
            )
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Key("ESC", color = KeyRed) { TerminalActions.send("\u001b") }
            DrawerKey("\u2261", menu, KeyAmber, onOpenDrawer)
            Key("\u2014", color = KeyAmber) { TerminalActions.send("-") }
            Key("HOME", color = KeyBlue) { TerminalActions.sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
            Key("\u2191", color = KeyBlue) { TerminalActions.sendKey(KeyEvent.KEYCODE_DPAD_UP) }
            Key("CLEAR", color = KeyRed) { TerminalActions.clear() }
            Key("COPY", color = KeyGreen) {
                val ok = TerminalActions.copyAll(ctx)
                Toast.makeText(
                    ctx, if (ok) "Seluruh teks terminal disalin" else "Tidak ada teks untuk disalin",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Key("\u21C6", color = KeyAmber) { TerminalActions.send("\t") }
            Key("CTRL", active = mods.ctrl, locked = mods.ctrlLocked, color = KeyAmber,
                onLongClick = { mods.toggleCtrlLock() }) { mods.tapCtrl() }
            Key("ALT", active = mods.alt, locked = mods.altLocked, color = KeyAmber,
                onLongClick = { mods.toggleAltLock() }) { mods.tapAlt() }
            Key("\u2190", color = KeyBlue) { TerminalActions.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            Key("\u2193", color = KeyBlue) { TerminalActions.sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
            Key("\u2192", color = KeyBlue) { TerminalActions.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            Key("PASTE", color = KeyGreen) { TerminalActions.paste(ctx) }
        }
    }
}

@Composable
private fun RowScope.Key(
    label: String,
    active: Boolean = false,
    locked: Boolean = false,
    color: Color = Color.Unspecified,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Box(Modifier.weight(1f)) { KeyButton(label, active, locked, color, onLongClick, onClick) }
}

/**
 * Tombol ≡: tap atau geser (swipe) membuka drawer sesi; tekan lama membuka menu simbol.
 */
@Composable
private fun RowScope.DrawerKey(
    label: String,
    items: List<Pair<String, () -> Unit>>,
    color: Color,
    onOpenDrawer: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.weight(1f).onHorizontalSwipe(18.dp) { onOpenDrawer() }
    ) {
        KeyButton(label, false, false, color, { open = true }) { onOpenDrawer() }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (text, action) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; action() })
            }
        }
    }
}

/**
 * Tombol ekstra. active = modifier aktif sekali pakai (latar redup);
 * locked = modifier dikunci lewat long-press (latar menyala + bingkai).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyButton(
    label: String,
    active: Boolean,
    locked: Boolean,
    tint: Color,
    onLongClick: (() -> Unit)?,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val base = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    val shape = RoundedCornerShape(8.dp)
    val bg = when {
        locked -> base.copy(alpha = 0.40f)
        active -> base.copy(alpha = 0.22f)
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(bg, shape)
            .then(if (locked) Modifier.border(1.5.dp, base, shape) else Modifier)
            .combinedClickable(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
                onLongClick = onLongClick?.let { lc ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        lc()
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 13.sp,
            maxLines = 1,
            fontWeight = if (active || locked) FontWeight.Bold else FontWeight.Medium,
            color = if (active || locked) base else base.copy(alpha = 0.85f)
        )
    }
}

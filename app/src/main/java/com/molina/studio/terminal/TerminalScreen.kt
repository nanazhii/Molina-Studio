package com.molina.studio.terminal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.util.TypedValue
import android.widget.Toast
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.clickable
import com.molina.studio.ui.MolinaTheme
import com.molina.studio.ui.onHorizontalSwipe
import androidx.compose.ui.viewinterop.AndroidView
import com.molina.terminal.view.TerminalView
import kotlin.math.floor
import androidx.compose.foundation.layout.fillMaxHeight
import com.molina.studio.ui.SwipeDirection

private fun spToPx(ctx: Context, sp: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), ctx.resources.displayMetrics).toInt()

/** Lebar satu sel monospace pada ukuran font tertentu (sama seperti TerminalRenderer). */
private fun cellWidthPx(ctx: Context, sp: Int): Float =
    Paint().apply { typeface = MolinaFont.typeface(ctx); textSize = spToPx(ctx, sp).toFloat() }.measureText("X")

@Composable
fun TerminalScreen(modifier: Modifier = Modifier, onOpenSettings: () -> Unit = {}, onOpenMonitor: () -> Unit = {}) { // [TS-MONITOR-CB1]
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
        TerminalContent(modifier, onOpenSettings, onOpenMonitor) // [TS-MONITOR-CB2]
    }
}

@Composable
private fun TerminalContent(modifier: Modifier, onOpenSettings: () -> Unit, onOpenMonitor: () -> Unit) { // [TS-MONITOR-CB3]
    val context = LocalContext.current
    val mods = remember { TerminalModifiers() }
    remember(context) { RootfsManager.refresh(context) }
    val rootfsState by RootfsManager.state.collectAsState()
    var drawerOpen by remember { mutableStateOf(false) }
    // [CMD-MARK] Kilatan tepi bingkai: p 1 -> 0 dalam 700 ms; hijau = berhasil, merah = gagal (Ctrl+C tidak berkilat).
    val flash = remember { Animatable(0f) }
    LaunchedEffect(CommandFlash.seq) {
        if (CommandFlash.seq == 0) return@LaunchedEffect
        flash.snapTo(1f)
        flash.animateTo(0f, tween(700))
    }
    val frameColor = lerp(Color(0xFF454A55), if (CommandFlash.ok) MolinaTheme.Good else MolinaTheme.Bad, flash.value)

    Box(modifier) {
    Column(Modifier.fillMaxSize()) {
        TerminalHeader(onOpenSettings, onOpenMonitor) // [TS-MONITOR-CB4]
        SessionPillBar()
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
                    val w = constraints.maxWidth.toFloat() - 2f * with(density) { 6.dp.toPx() } // [FRAME-BOLD] bingkai viewport: 4dp luar + 2dp dalam (garis 2dp digambar di dalam batas)
                    val rest = w - floor(w / cell) * cell
                    with(density) { (rest / 2f).toDp() }
                } else 0.dp
                AndroidView(
                    modifier = Modifier.fillMaxSize()
                        .padding(4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(if (flash.value > 0f) 3.dp else 2.dp, frameColor, RoundedCornerShape(6.dp)) // [FRAME-BOLD]
                        .padding(2.dp)
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
                            startCursorBlink(v)
                            v.requestFocus()
                            v.onScreenUpdated()
                        }
                    },
                    onRelease = { v ->
                        v.setTerminalCursorBlinkerState(false, false)
                        if (TerminalSessions.client.view === v) TerminalSessions.client.view = null
                    }
                )
            }
            // [FILE-DRAWER] geser dari tepi kiri area terminal membuka drawer berkas (seperti editor Code)
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxHeight().width(14.dp)
                    .onHorizontalSwipe(24.dp, SwipeDirection.Right) { drawerOpen = true }
            )
        }
        NanoKeys() // [NANO-KEYS]
        ExtraKeys(mods) { drawerOpen = true }
    }
    TerminalFileDrawer(open = drawerOpen, onClose = { drawerOpen = false }) // [FILE-DRAWER]
    }
}

private val KeyRed = Color(0xFFFF5252)    // ESC, CLEAR [TERM-KEYS-BOLD] lebih pekat
private val KeyBlue = Color(0xFF448AFF)   // HOME, panah
private val KeyGreen = Color(0xFF00E676)  // COPY, PASTE
private val KeyFrame = Color(0xFF454A55)  // bingkai terluar panel extra keys
private val KeyAmber = MolinaTheme.Brand  // TAB, menu, ENTER [XK-COMMENT]
private val KeyNeutral = MolinaTheme.OnBackground // CTRL, ALT (menyala emas saat aktif)

private fun createTerminalView(ctx: Context, mods: TerminalModifiers): TerminalView {
    val view = TerminalView(ctx, null)
    val session = TerminalSessions.ensureSession(ctx)
    TerminalSessions.client.view = view
    view.setTerminalViewClient(MolinaViewClient(view, mods))
    view.tag = TerminalPrefs.fontSp
    view.setTextSize(spToPx(ctx, TerminalPrefs.fontSp))
    view.setTypeface(MolinaFont.typeface(ctx))
    view.isFocusable = true
    view.isFocusableInTouchMode = true
    view.attachSession(session)
    startCursorBlink(view)
    view.requestFocus()
    return view
}

// [CURSOR-BLINK] Kedip pelan (600 ms) memakai blinker bawaan TerminalView. Flag kedip tersimpan per emulator,
// jadi harus dinyalakan ulang setiap pindah sesi; dihentikan saat view dilepas agar tidak bocor.
private const val CURSOR_BLINK_MS = 600

private fun startCursorBlink(v: TerminalView) {
    v.setTerminalCursorBlinkerRate(CURSOR_BLINK_MS)
    v.setTerminalCursorBlinkerState(true, true)
}

/**
 * [NANO-KEYS] [NANO-KEYS-V2] Baris tombol sementara saat nano aktif. Pembungkus nano menamai sesi "nano: <berkas>" lewat
 * judul terminal dan mengosongkannya saat nano selesai. Tiap tombol mengirim urutan kontrol bawaan nanorc Molina.
 * Baris bisa digeser ke samping: enam tombol pertama (SAVE RUN UNDO FIND ALL QUIT) terlihat di ponsel potret,
 * sisanya (REDO GOTO REPL CODE) ada di sebelah kanan.
 */
private val NanoKeyWidth = 60.dp
private val NanoKeyList = listOf(
    Triple("SAVE", KeyGreen, "\u0013"),    // ^S
    Triple("RUN", KeyGreen, "\u001br"),    // [NANO-RUN] Alt+R: simpan, keluar, jalankan skrip dari berkasnya
    Triple("UNDO", KeyBlue, "\u001a"),     // ^Z
    Triple("FIND", KeyAmber, "\u0006"),    // ^F
    Triple("ALL", KeyAmber, "\u0001"),     // ^A pilih semua (makro nanorc)
    Triple("QUIT", KeyRed, "\u0011"),      // ^Q
    Triple("REDO", KeyBlue, "\u0019"),     // ^Y
    Triple("GOTO", KeyAmber, "\u0007"),    // ^G ke baris
    Triple("REPL", KeyAmber, "\u0012"),    // ^R ganti
    Triple("CODE", KeyGreen, "\u001bo"),   // Alt+O: simpan lalu buka di Code
    Triple("VIEW", KeyGreen, "\u001bp")    // [NANO-PREVIEW] Alt+P: simpan lalu pratinjau MD/HTML/JSON
)

private enum class NanoPrompt { NONE, SAVE, NAME, RUNFIX } // [NANO-PROMPT] [NANO-RUNFIX]
private val NanoPromptKeyWidth = 96.dp
// [NANO-PROMPT-84] nano 7.x: "File Name to Write"; nano 8.x: "Write to File" / "Write Selection to File"
private val NanoNamePrompts = listOf("File Name to Write", "Write to File", "Write Selection to File")
private val NanoSaveKeys = listOf(
    Triple("YA", KeyGreen, "y"),          // jawab Y pada "Save modified buffer?"
    Triple("TIDAK", KeyRed, "n"),         // jawab N: buang perubahan
    Triple("BATAL", KeyBlue, "\u0003")    // ^C: kembali ke editor
)
private val NanoNameKeys = listOf(
    Triple("ENTER", KeyGreen, "\r"),      // terima nama berkas
    Triple("BATAL", KeyBlue, "\u0003")    // ^C: batal menyimpan
)

// [NANO-RUNFIX] dialog molina-run (judul sesi "nano-run: ...") -> tombol sesuai opsi di teks prompt; lihat NanoRunPrompt
private val NanoRunKeyWidth = 84.dp
private fun runTint(key: Char): Color = when (key) {
    '1' -> KeyGreen
    '2' -> KeyBlue
    'a' -> KeyAmber
    '3' -> KeyAmber
    'e' -> KeyBlue
    else -> KeyRed
}

@Composable
private fun NanoKeys() {
    val tick = TerminalSessions.titleTick // dibaca agar Compose menyusun ulang saat judul sesi berubah
    val s = TerminalSessions.currentSession
    val inRunDialog = s?.title?.startsWith(NanoRunPrompt.TITLE_PREFIX) == true
    if (tick < 0 || s == null || (s.title?.startsWith("nano: ") != true && !inRunDialog)) return
    var prompt by remember(s) { mutableStateOf(NanoPrompt.NONE) }
    var runChoices by remember(s) { mutableStateOf<List<NanoRunPrompt.Choice>?>(null) }
    LaunchedEffect(s) { // [NANO-PROMPT] baca tiga baris bawah layar nano: prompt simpan/nama berkas -> tombol YA/TIDAK/BATAL
        while (true) {
            var found = NanoPrompt.NONE
            var foundRun: List<NanoRunPrompt.Choice>? = null
            val runDlg = s.title?.startsWith(NanoRunPrompt.TITLE_PREFIX) == true
            runCatching {
                val em = s.emulator
                val scr = em?.screen
                if (em != null && scr != null) {
                    val cols = em.mColumns
                    val n = em.mRows
                    if (runDlg) { // dialog molina-run: prompt bisa di baris mana pun (nano sudah keluar), baca seluruh layar
                        foundRun = NanoRunPrompt.parse((0 until n).map { r -> scr.getSelectedText(0, r, cols, r) })
                        if (foundRun != null) found = NanoPrompt.RUNFIX
                    }
                    if (!runDlg) for (r in maxOf(0, n - 3) until n) {
                        val t = scr.getSelectedText(0, r, cols, r)
                        if (t.contains("Save modified buffer", ignoreCase = true)) found = NanoPrompt.SAVE
                        else if (NanoNamePrompts.any { p -> t.contains(p, ignoreCase = true) }) found = NanoPrompt.NAME
                    }
                }
                Unit
            }
            if (prompt != found) prompt = found
            if (runChoices != foundRun) runChoices = foundRun
            delay(300)
        }
    }
    if (inRunDialog && prompt != NanoPrompt.RUNFIX) return // skrip sedang jalan/dialog belum tampil: tanpa baris tombol
    val keys = when (prompt) {
        NanoPrompt.SAVE -> NanoSaveKeys
        NanoPrompt.NAME -> NanoNameKeys
        NanoPrompt.RUNFIX -> (runChoices ?: emptyList()).map { c -> Triple(c.label, runTint(c.key), NanoRunPrompt.sequenceOf(c)) }
        NanoPrompt.NONE -> NanoKeyList
    }
    val keyWidth = when (prompt) {
        NanoPrompt.NONE -> NanoKeyWidth
        NanoPrompt.RUNFIX -> NanoRunKeyWidth
        else -> NanoPromptKeyWidth
    }
    val runDefault = runChoices?.firstOrNull { it.isDefault }?.label // tombol bawaan (Enter) disorot
    val forced = prompt != NanoPrompt.NONE // [NK-DROP] prompt simpan/nama berkas: tombol wajib terlihat
    val open = forced || TerminalPrefs.nanoKeysOpen
    val title = when (prompt) {
        NanoPrompt.SAVE -> "simpan perubahan?"
        NanoPrompt.NAME -> "nama berkas"
        NanoPrompt.RUNFIX -> "periksa skrip"
        NanoPrompt.NONE -> s.title?.removePrefix("nano: ").orEmpty()
    }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth()) { // drop-up: baris tombol tumbuh ke atas dari gagang di bawahnya
        if (open) Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)
                .clip(shape)
                .background(Color.Black, shape)
                .border(2.dp, if (prompt == NanoPrompt.NONE) MolinaTheme.Brand else KeyAmber, shape)
                .horizontalScroll(rememberScrollState())
                .padding(3.dp)
        ) {
            keys.forEach { (label, tint, seq) ->
                Box(Modifier.width(keyWidth).padding(1.dp)) {
                    KeyButton(label, prompt == NanoPrompt.RUNFIX && label == runDefault, false, tint, null) { TerminalActions.send(seq) }
                }
            }
        }
        NanoHandle(title, open, forced) { TerminalPrefs.setNanoKeysOpen(!TerminalPrefs.nanoKeysOpen) }
    }
}

/** [NK-DROP] Gagang tipis di atas extra keys: ketuk untuk membuka (▴) atau melipat (▾) tombol nano. Pilihan diingat. */
@Composable
private fun NanoHandle(title: String, open: Boolean, forced: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val tint = if (forced) KeyAmber else MolinaTheme.Brand
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(shape)
            .background(Color.Black, shape)
            .border(1.dp, tint, shape)
            .clickable(enabled = !forced, onClick = onToggle)
            .heightIn(min = 24.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("NANO", color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(8.dp))
        Text(
            title, color = KeyNeutral.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        Text(if (open) "\u25BE" else "\u25B4", color = tint, fontSize = 14.sp)
    }
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
    val shape = RoundedCornerShape(12.dp)
    val lit = mods.ctrl || mods.alt || mods.ctrlLocked || mods.altLocked
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp) // [XK-COMPACT] padding vertikal 4dp -> 2dp
            .clip(shape)
            .background(Color.Black, shape) // [TERM-KEYS-BLACK] hitam pekat solid; bingkai hanya di sisi terluar
            .border(2.dp, if (lit) MolinaTheme.Brand else KeyFrame, shape) // [TERM-KEYS-BOLD] bingkai 2dp
            .padding(3.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            Key("ESC", color = KeyRed) { TerminalActions.send("\u001b") }
            DrawerKey("\u2261", menu, KeyAmber, onOpenDrawer)
            Key("HOME", color = KeyBlue) { TerminalActions.sendKey(KeyEvent.KEYCODE_MOVE_HOME) } // [XK-ORDER] "-" dihapus; HOME, CLEAR, COPY, PASTE bergeser satu slot
            Key("CLEAR", color = KeyRed) { TerminalActions.clear() }
            Key("\u2191", color = KeyBlue) { TerminalActions.sendKey(KeyEvent.KEYCODE_DPAD_UP) }
            Key("PASTE", color = KeyGreen) { TerminalActions.paste(ctx) } // [XK-SWAP] PASTE di slot lama COPY, COPY di slot paling kanan
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
            Key("CTRL", active = mods.ctrl, locked = mods.ctrlLocked, color = KeyNeutral,
                onLongClick = { mods.toggleCtrlLock() }) { mods.tapCtrl() }
            Key("ALT", active = mods.alt, locked = mods.altLocked, color = KeyNeutral,
                onLongClick = { mods.toggleAltLock() }) { mods.tapAlt() }
            Key("\u2190", color = KeyBlue) { TerminalActions.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            Key("\u2193", color = KeyBlue) { TerminalActions.sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
            Key("\u2192", color = KeyBlue) { TerminalActions.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            Key("ENTER", color = KeyAmber) { TerminalActions.sendKey(KeyEvent.KEYCODE_ENTER) } // [XK-ENTER] sama dengan Enter keyboard (\r; Alt+Enter = ESC \r)
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
    Box(Modifier.weight(1f).padding(1.dp)) { KeyButton(label, active, locked, color, onLongClick, onClick) }
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
        Modifier.weight(1f).padding(1.dp).onHorizontalSwipe(18.dp) { onOpenDrawer() }
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
    val gold = MolinaTheme.Brand
    val shape = RoundedCornerShape(8.dp)
    val bg = when {
        locked -> gold.copy(alpha = 0.40f)
        active -> gold.copy(alpha = 0.22f)
        else -> Color.Transparent // [TERM-KEYS-BLACK] tombol diam tanpa tile abu-abu
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp) // [XK-COMPACT] tinggi tombol 38dp -> 32dp, hemat ruang terminal
            .clip(shape)
            .background(bg, shape)
            .then(
                if (locked) Modifier.border(1.5.dp, gold, shape)
                else if (active) Modifier.border(1.dp, gold.copy(alpha = 0.7f), shape)
                else Modifier
            )
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
            fontSize = 14.sp,
            maxLines = 1,
            fontWeight = if (active || locked) FontWeight.Bold else FontWeight.SemiBold,
            color = if (active || locked) gold else base
        )
    }
}

package com.molina.studio.terminal

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.molina.core.common.LogRedact
import com.molina.core.common.MolinaDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.molina.core.common.MolinaPaths
import com.molina.studio.AppPaths
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.ui.MolinaTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pilihan tampilan Terminal (SharedPreferences molina_ui): layar tetap menyala. */
internal object TerminalUi {
    private const val PREFS = "molina_ui"
    private const val KEY_KEEP_ON = "term_keep_on"
    private var loaded = false

    var keepScreenOn by mutableStateOf(false)
        private set

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        keepScreenOn = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_KEEP_ON, false)
    }

    fun setKeepScreenOn(ctx: Context, v: Boolean) {
        loaded = true
        keepScreenOn = v
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_KEEP_ON, v).apply()
    }
}

private val transcriptScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Simpan seluruh transkrip (scrollback + layar) ke folder data aplikasi /sdcard/Molina/Logs/Terminal/
 * (sama seperti Crash; folder kerja tidak ditulisi aplikasi).
 * Teks dibaca di main thread (emulator tidak thread-safe); penulisan berkas di Dispatchers.IO.
 * [onDone] dipanggil di main thread: path versi Debian (/sdcard/...), atau null bila kosong/gagal.
 * Bila Setelan "Samarkan token saat menyimpan transkrip" aktif, teks disamarkan lewat [LogRedact].
 */
internal fun saveTranscriptAsync(ctx: Context, onDone: (String?) -> Unit) {
    val raw = TerminalSessions.currentSession?.emulator?.screen?.transcriptText
    if (raw.isNullOrBlank()) { onDone(null); return }
    val app = ctx.applicationContext
    val redact = TerminalPrefs.redactTranscript
    transcriptScope.launch {
        val path = try {
            val dir = AppPaths.get(app).dir(MolinaDir.TerminalLog)
            if (!dir.isDirectory && !dir.mkdirs()) {
                null
            } else {
                val name = "terminal-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
                val body = (if (redact) LogRedact.text(raw) else raw).trimEnd() + "\n"
                File(dir, name).writeText(body)
                "/sdcard/${MolinaPaths.APP_FOLDER}/${MolinaDir.TerminalLog.folderName}/$name"
            }
        } catch (e: Exception) {
            null
        }
        withContext(Dispatchers.Main) { onDone(path) }
    }
}

private val HdrTop = Color(0xFF4A4A4A)
private val HdrMid = Color(0xFF262626)
private val HdrBottom = Color(0xFF111111)
private val HdrDim = Color(0xFFC8C8C8)

/**
 * Header ala Windows 7 Aero di atas viewport Terminal: titik status sesi, nomor sesi, monitor CPU/RAM/jaringan,
 * saklar layar tetap menyala, dan menu utilitas (Ctrl+C, Ctrl+R, simpan transkrip, pengaturan).
 * Extra keys di bawah tidak diubah.
 */
@Composable
internal fun TerminalHeader(onOpenSettings: () -> Unit, onOpenMonitor: () -> Unit = {}) { // [HDR-MONITOR-CB]
    val ctx = LocalContext.current
    remember(ctx) { TerminalUi.load(ctx) }
    val total = TerminalSessions.sessions.size
    val idx = TerminalSessions.currentIndex
    val keepOn = TerminalUi.keepScreenOn
    var menu by remember { mutableStateOf(false) }
    var snippets by remember { mutableStateOf(false) }
    var scriptPicker by remember { mutableStateOf(false) }
    var links by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(Brush.verticalGradient(listOf(HdrTop, HdrMid, HdrBottom)))
            .drawBehind {
                drawRect(Color.White.copy(alpha = 0.10f), Offset.Zero, Size(size.width, size.height * 0.5f))
                drawLine(Color(0x99D0D0D0), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(MolinaTheme.Good))
        Spacer(Modifier.width(8.dp))
        Text("Debian", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Text(
            "[" + (idx + 1) + "/" + total + "]",
            color = HdrDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.weight(1f))
        // [HDR-STATS] Monitor CPU/RAM/jaringan (dulu di bilah pil). Ukuran font tetap lewat cubit dan Pengaturan.
        MonitorChips(onOpen = onOpenMonitor) // [MONITOR-CHIPS]
        // [HDR-KEEPON-MENU] saklar layar tetap menyala dipindah ke menu titik tiga; ruang header untuk monitor
        Box {
            HdrBtn("\u22EE") { menu = true }
            if (snippets) SnippetDialog { snippets = false }
            if (scriptPicker) com.molina.studio.code.ScriptPickerDialog { scriptPicker = false }
            if (links) LinksDialog { links = false }
            if (search) TerminalSearchDialog { search = false }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Hentikan proses (Ctrl+C)") },
                    onClick = { menu = false; TerminalActions.send("\u0003") }
                )
                DropdownMenuItem(
                    text = { Text("Cari riwayat perintah (Ctrl+R)") },
                    onClick = { menu = false; TerminalActions.send("\u0012") }
                )
                DropdownMenuItem(
                    text = { Text("Simpan transkrip ke berkas") },
                    onClick = {
                        menu = false
                        saveTranscriptAsync(ctx) { path ->
                            Toast.makeText(
                                ctx, if (path != null) "Transkrip disimpan: $path" else "Gagal menyimpan: transkrip kosong atau izin storage belum diberikan",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Snippet perintah") },
                    onClick = { menu = false; snippets = true }
                )
                DropdownMenuItem(
                    text = { Text("Pilih skrip") },
                    onClick = { menu = false; scriptPicker = true }
                )
                DropdownMenuItem(
                    text = { Text("Cari di scrollback") },
                    onClick = { menu = false; search = true }
                )
                DropdownMenuItem(
                    text = { Text("Tautan di layar") },
                    onClick = { menu = false; links = true }
                )
                DropdownMenuItem(
                    text = { Text(if (keepOn) "\u2611 Layar tetap menyala" else "\u2610 Layar tetap menyala") },
                    onClick = {
                        menu = false
                        TerminalUi.setKeepScreenOn(ctx, !keepOn)
                        Toast.makeText(
                            ctx, if (!keepOn) "Layar tetap menyala: aktif" else "Layar tetap menyala: nonaktif",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Pengaturan") },
                    onClick = { menu = false; onOpenSettings() }
                )
            }
        }
    }
}

@Composable
private fun HdrBtn(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .padding(start = 4.dp)
            .size(width = 34.dp, height = 28.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF3A3A3A), Color(0xFF151515))), shape)
            .border(1.dp, Color(0x66D0D0D0), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 12.sp, color = if (enabled) Color.White else Color(0x66FFFFFF))
    }
}

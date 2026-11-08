package com.molina.studio.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.molina.studio.code.CodeTerminalBridge
import com.molina.studio.code.ScriptNav
import java.io.File

/**
 * [CODE-TERM] Panel Terminal di dalam layar Code. Memakai sesi Debian yang sama dengan Terminal penuh
 * (TerminalSessions): satu sesi per folder proyek, dibuka di folder itu; bila sudah ada, dipakai ulang.
 * Tombol ≡ membuka Terminal penuh (drawer sesi).
 */
@Composable
internal fun CodeTerminalPanel(modifier: Modifier, projectDir: File) {
    val context = LocalContext.current
    val mods = remember { TerminalModifiers() }
    remember(context) { RootfsManager.refresh(context) }
    val rootfsState by RootfsManager.state.collectAsState()
    val guest = remember(projectDir.path) { CodeTerminalBridge.guestPath(projectDir) }
    var ready by remember(guest) { mutableStateOf(false) }

    // Pilih/buat sesi SEBELUM TerminalView dipasang, agar tidak ada sesi /root yatim.
    LaunchedEffect(guest, rootfsState) {
        if (rootfsState !is RootfsState.Ready) return@LaunchedEffect
        val idx = if (guest != null) TerminalSessions.indexOfStartDir(guest) else -1
        when {
            idx >= 0 -> TerminalSessions.select(idx)
            guest != null && TerminalSessions.canAdd() -> TerminalSessions.newSession(context, guest, projectDir.name)
            else -> TerminalSessions.ensureSession(context)
        }
        ready = true
    }

    Column(modifier.background(Color.Black)) {
        if (rootfsState !is RootfsState.Ready) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Debian belum terpasang. Pasang lewat Settings > Terminal.", color = Color(0xFFB8C2CC), fontSize = 12.sp)
            }
        } else {
            if (guest == null) {
                Text(
                    "Folder ini tidak terlihat di Debian. Aktifkan Akses penyimpanan penuh di Terminal.",
                    color = Color(0xFFFFB74D), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (ready) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp),
                        factory = { ctx -> createTerminalView(ctx, mods) },
                        update = { v ->
                            v.keepScreenOn = TerminalUi.keepScreenOn
                            val sp = TerminalPrefs.fontSp
                            if (v.tag != sp) {
                                v.tag = sp
                                v.setTextSize(spToPx(v.context, sp))
                            }
                            val target = TerminalSessions.active
                            if (target != null && v.currentSession !== target) {
                                v.attachSession(target)
                                startCursorBlink(v)
                                v.onScreenUpdated()
                            }
                        },
                        onRelease = { v ->
                            v.setTerminalCursorBlinkerState(false, false)
                            if (TerminalSessions.client.view === v) TerminalSessions.client.view = null
                        }
                    )
                }
            }
            ExtraKeys(mods) { ScriptNav.openTerminal = true }
        }
    }
}

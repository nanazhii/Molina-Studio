package com.molina.studio.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.molina.explorer.ExplorerTheme
import com.molina.explorer.Win7OpenDialog
import com.molina.studio.bridge.MolinaExplorer
import com.molina.studio.cinema.CinemaActions
import com.molina.studio.cinema.rememberAppExplorerEnv
import kotlinx.coroutines.launch
import java.io.File

/**
 * [EXPLORER-ONE] SUMBER TUNGGAL Penjelajah Molina mode jelajah (semua aksi: terminal, bagikan, Code, Vault, skrip, dll).
 * Dipakai tab Berkas (layar penuh) dan dialog Folder IPTV di Saluran Home MPV. Menambah aksi baru cukup di sini.
 *
 * [onLeave] dipanggil setelah aksi yang meninggalkan Penjelajah berhasil (Buka di Code, Edit di Terminal) agar
 * pemanggil bisa menutup dialog induknya. [theme] hanya mengubah warna, bukan fungsi.
 */
@Composable
internal fun MolinaExplorerHost(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    asScreen: Boolean = false,
    startDir: File? = null,
    theme: ExplorerTheme = ExplorerTheme.Terminal,
    onOpenEditor: (() -> Unit)? = null, // [EDITOR-IN-DROP] null = item "Editor" disembunyikan
    onLeave: () -> Unit = {}
) {
    val ctx = LocalContext.current
    remember { MolinaExplorer.attach(ctx) }
    // MOLINA-H13-BRIDGE: hasil impor Vault kembali lewat startActivityForResult. State ini sengaja tidak disimpan
    // lintas rotasi/proses: bila hilang, hasil diabaikan dan tidak ada berkas asli yang dihapus (fail-safe).
    val vaultScope = rememberCoroutineScope()
    var vaultReq by remember { mutableStateOf<VaultBridge.Request?>(null) }
    var vaultDone by remember { mutableStateOf<((List<File>, String) -> Unit)?>(null) }
    val vaultLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val req = vaultReq
        val done = vaultDone
        vaultReq = null
        vaultDone = null
        if (req != null && done != null) vaultScope.launch {
            val o = VaultBridge.verify(req, res.resultCode, res.data)
            done(o.deletable, o.message)
        }
    }
    Win7OpenDialog(
        onDismiss = onDismiss,
        browse = true,
        asScreen = asScreen,
        screenModifier = modifier,
        startDir = startDir,
        onCopyPath = { f -> MolinaExplorer.copyPath(ctx, f) },
        onOpenTerminal = { f -> MolinaExplorer.openTerminalHere(ctx, f) },
        onCdTerminal = { f -> MolinaExplorer.cdHere(ctx, f) },
        onShare = { files -> MolinaExplorer.share(ctx, files) },
        onOpenWith = { f -> MolinaExplorer.openWith(ctx, f) },
        onInsertPaths = { fs -> MolinaExplorer.insertPaths(ctx, fs) },
        isVisibleInDebian = { f -> MolinaExplorer.visibleInDebian(f) },
        onPlay = { files -> CinemaActions.playFiles(ctx, files) },
        onOpenInCode = { files -> MolinaExplorer.openInCode(ctx, files); onLeave() },
        onOpenEditor = onOpenEditor,
        onEditInTerminal = { f -> if (MolinaExplorer.editInTerminal(ctx, f)) onLeave() }, // [NANO-EDIT]
        onNewTextInTerminal = { d, n -> MolinaExplorer.newInTerminal(ctx, d, n) }, // [NANO-NEW]
        onSendToScripts = { f -> MolinaExplorer.sendToScripts(f) },
        onSaveToVault = { files, done ->
            when (val p = VaultBridge.prepare(ctx, files)) {
                is VaultBridge.Prepared.Fail -> done(emptyList(), p.message)
                is VaultBridge.Prepared.Ready -> {
                    vaultReq = p.request
                    vaultDone = done
                    try {
                        vaultLauncher.launch(p.request.intent)
                    } catch (e: Exception) {
                        vaultReq = null
                        vaultDone = null
                        done(emptyList(), "Vault tidak bisa dibuka: " + (e.message ?: e.javaClass.simpleName))
                    }
                }
            }
        },
        env = rememberAppExplorerEnv(theme)
    )
}

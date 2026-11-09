package com.molina.studio.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.molina.studio.cinema.Win7Folder
import com.molina.studio.cinema.Win7OpenDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Isi tab Berkas (nav bawah). Penjelajah Molina dibuka otomatis saat tab dipilih; bila ditutup,
 * layar ini menyisakan tombol untuk membukanya lagi. Dulu hidup di ubin Berkas Settings.
 */
@Composable
fun FilesTabScreen(modifier: Modifier = Modifier, onExit: () -> Unit = {}) {
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
        onDismiss = onExit,
        browse = true,
        asScreen = true,
        screenModifier = modifier,
        onCopyPath = { f -> MolinaExplorer.copyPath(ctx, f) },
        onOpenTerminal = { f -> MolinaExplorer.openTerminalHere(ctx, f) },
        onCdTerminal = { f -> MolinaExplorer.cdHere(ctx, f) },
        onShare = { files -> MolinaExplorer.share(ctx, files) },
        onOpenWith = { f -> MolinaExplorer.openWith(ctx, f) },
        onInsertPaths = { fs -> MolinaExplorer.insertPaths(ctx, fs) },
        isVisibleInDebian = { f -> MolinaExplorer.visibleInDebian(f) },
        onPlay = { files -> MolinaExplorer.play(ctx, files) },
        onOpenInNano = { files -> MolinaExplorer.openInNano(ctx, files) },
        onEditInTerminal = { f -> MolinaExplorer.editInTerminal(ctx, f) }, // [NANO-EDIT]
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
        }
    )
}

/**
 * Ikon tab Berkas: folder satu warna (mengikuti warna tab) digambar dengan Canvas,
 * sehingga tidak bergantung pada glyph font Android 8.
 */
@Composable
fun TabFolderIcon(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(color.copy(alpha = 0.70f), Offset(w * 0.06f, h * 0.16f), Size(w * 0.40f, h * 0.22f), CornerRadius(w * 0.05f))
        drawRoundRect(color, Offset(w * 0.06f, h * 0.28f), Size(w * 0.88f, h * 0.58f), CornerRadius(w * 0.08f))
    }
}

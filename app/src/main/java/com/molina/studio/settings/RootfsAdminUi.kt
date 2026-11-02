package com.molina.studio.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.molina.studio.ui.MolinaTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.terminal.RootfsAdmin
import com.molina.studio.terminal.RootfsAdminState
import com.molina.studio.terminal.RootfsBackupFile
import com.molina.studio.terminal.RootfsManager
import com.molina.studio.terminal.RootfsState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface Pending {
    data class Restore(val f: RootfsBackupFile) : Pending
    data object Reset : Pending
}

/** [TERM-ROOTFS3] Monogram "M" emas Molina (MolinaTheme.Gold), sama dengan emas Terminal dan Cinema. */
@Composable
internal fun W7IconRootfs() {
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier.size(34.dp).clip(shape).background(MolinaTheme.Background, shape).border(1.5.dp, MolinaTheme.Gold, shape),
        Alignment.Center
    ) {
        Text("M", color = MolinaTheme.Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

private fun mb(bytes: Long) = "${bytes / (1024 * 1024)} MB"

/** Settings > Sistem > "Kelola rootfs Debian" (TERM-ROOTFS2): cadangkan, pulihkan, atur ulang. */
@Composable
internal fun RootfsAdminDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val st by RootfsAdmin.state.collectAsState()
    val rootfs by RootfsManager.state.collectAsState()
    var pending by remember { mutableStateOf<Pending?>(null) }
    var backups by remember { mutableStateOf(RootfsAdmin.listBackups(ctx)) }
    val running = st as? RootfsAdminState.Running
    val ready = rootfs is RootfsState.Ready
    val view = LocalView.current

    // Layar menyala selama operasi berjalan agar proses panjang tidak terputus tidur layar.
    DisposableEffect(running != null) {
        val old = view.keepScreenOn
        if (running != null) view.keepScreenOn = true
        onDispose { view.keepScreenOn = old }
    }
    // Segarkan daftar cadangan setiap operasi selesai.
    DisposableEffect(st is RootfsAdminState.Finished) {
        if (st is RootfsAdminState.Finished) backups = RootfsAdmin.listBackups(ctx)
        onDispose { }
    }

    val fmt = remember { SimpleDateFormat("d MMM yyyy HH:mm", Locale("id", "ID")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                W7IconRootfs()
                Spacer(Modifier.width(10.dp))
                Text("Kelola rootfs Debian")
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when {
                    running != null -> {
                        Text("${running.op.label}: ${running.phase} ${running.percent}%", fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { running.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("Semua sesi Terminal dihentikan selama proses. Biarkan aplikasi tetap terbuka.", fontSize = 12.sp)
                    }
                    st is RootfsAdminState.Finished -> {
                        val f = st as RootfsAdminState.Finished
                        Text(if (f.ok) "Berhasil" else "Tidak berhasil", fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(f.message, fontSize = 12.sp)
                    }
                    pending is Pending.Reset -> Text(
                        "Seluruh rootfs Debian (paket, setelan, berkas di home) akan DIHAPUS. Folder /sdcard tidak tersentuh. " +
                            "Cadangkan dulu bila perlu. Lanjutkan?", fontSize = 12.sp
                    )
                    pending is Pending.Restore -> Text(
                        "Rootfs saat ini akan diganti isi cadangan ${(pending as Pending.Restore).f.file.name}. " +
                            "Semua sesi Terminal ditutup. Rootfs lama dikembalikan otomatis bila pemulihan gagal. Lanjutkan?", fontSize = 12.sp
                    )
                    else -> {
                        Text(
                            "Cadangan disimpan di ${RootfsAdmin.backupDir(ctx).path}. Berkas .tar.gz yang Anda salin ke folder itu ikut terdaftar.",
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        if (!ready) Text("Rootfs belum terpasang: pencadangan dan pengaturan ulang dinonaktifkan, tetapi Anda bisa memulihkan dari cadangan di bawah.", fontSize = 12.sp)
                        if (backups.isEmpty()) Text("Belum ada cadangan rootfs.", fontSize = 12.sp)
                        backups.forEach { b ->
                            TextButton(onClick = { pending = Pending.Restore(b) }) {
                                Text("Pulihkan ${b.file.name}\n${mb(b.bytes)} \u00B7 ${fmt.format(Date(b.modified))}", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column {
                when {
                    running != null -> TextButton(onClick = { RootfsAdmin.cancel() }) { Text("Batalkan") }
                    st is RootfsAdminState.Finished -> TextButton(onClick = { RootfsAdmin.dismissResult() }) { Text("Oke") }
                    pending is Pending.Restore -> TextButton(onClick = {
                        val p = pending as Pending.Restore; pending = null; RootfsAdmin.restore(ctx, p.f.file)
                    }) { Text("Ya, pulihkan") }
                    pending is Pending.Reset -> TextButton(onClick = { pending = null; RootfsAdmin.reset(ctx) }) { Text("Ya, hapus rootfs") }
                    else -> {
                        TextButton(enabled = ready, onClick = { RootfsAdmin.backup(ctx) }) { Text("Cadangkan sekarang") }
                        TextButton(enabled = ready, onClick = { pending = Pending.Reset }) { Text("Atur ulang rootfs") }
                    }
                }
            }
        },
        dismissButton = {
            if (pending != null && running == null) TextButton(onClick = { pending = null }) { Text("Batal") }
            else TextButton(onClick = onDismiss) { Text("Tutup") }
        }
    )
}

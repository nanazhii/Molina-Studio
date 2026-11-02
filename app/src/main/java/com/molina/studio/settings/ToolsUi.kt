package com.molina.studio.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.packages.BuiltinTools
import com.molina.studio.packages.ToolCheck
import com.molina.studio.packages.ToolItem
import com.molina.studio.packages.ToolPurge
import com.molina.studio.packages.ToolStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Blue = Color(0xFF6CB4FF)
private val Red = Color(0xFFE8776B)

private enum class ToolAction(val label: String, val timeoutMs: Long, val confirm: Boolean) {
    Install("Pasang", 30 * 60_000L, true),
    Version("Versi", 2 * 60_000L, false),
    Remove("Hapus", 15 * 60_000L, true)
}

private class PendingRun(val tool: ToolItem, val action: ToolAction, val backup: Boolean = false, val previewed: Boolean = false)

private class ToolDraft(val id: String?, name: String, note: String, install: String, version: String, remove: String) {
    var name by mutableStateOf(name)
    var note by mutableStateOf(note)
    var install by mutableStateOf(install)
    var version by mutableStateOf(version)
    var remove by mutableStateOf(remove)
}

private fun scriptOf(t: ToolItem, a: ToolAction): String = when (a) {
    ToolAction.Install -> t.install
    ToolAction.Version -> t.version
    ToolAction.Remove -> t.remove
}

/**
 * Halaman "Paket": alat khusus (pasang, versi, hapus). yt-dlp punya halaman sendiri; alat Debian bawaan
 * (Python 3, Git, FFmpeg) dan alat buatan pengguna memakai tiga skrip yang dijalankan di Debian PRoot.
 * Skrip umum ada di Script execution.
 */
@Composable
internal fun PackagesPage(
    onOpenYtdlp: () -> Unit,
    onOpenBundle: () -> Unit,
    onApt: (AptJob) -> Unit
) {
    val ctx = LocalContext.current
    remember { ToolStore.init(ctx) }
    val custom by ToolStore.custom.collectAsState()
    val checks by ToolStore.checks.collectAsState()
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingRun?>(null) }
    var draft by remember { mutableStateOf<ToolDraft?>(null) }
    var toDrop by remember { mutableStateOf<ToolItem?>(null) }
    var preview by remember { mutableStateOf<ToolItem?>(null) }
    remember { AptHistory.init(ctx) }
    val aptLast by AptHistory.last.collectAsState()
    val aptPending by AptHistory.pending.collectAsState()

    // [PAKET-VERSI] satu-satunya tempat cek versi alat: antrean BERURUTAN (semua memakai satu PRoot Debian), hasil tampil di kartu
    var checking by remember { mutableStateOf<String?>(null) }
    var checkHandle by remember { mutableStateOf<com.molina.code.run.RunHandle?>(null) }
    var queue by remember { mutableStateOf<List<String>>(emptyList()) }
    var batchTotal by remember { mutableStateOf(0) }
    var batchOk by remember { mutableStateOf(0) }
    var batchNote by remember { mutableStateOf<String?>(null) }
    val notes = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { queue = emptyList(); checkHandle?.cancel() } }

    fun runNext() {
        val tid = queue.firstOrNull()
        if (tid == null) {
            checking = null
            checkHandle = null
            if (batchTotal > 0) batchNote = "Selesai: $batchOk dari $batchTotal terdeteksi."
            batchTotal = 0
            return
        }
        queue = queue.drop(1)
        val tool = (BuiltinTools.all + ToolStore.custom.value).firstOrNull { it.id == tid }
        val script = if (tool != null) scriptOf(tool, ToolAction.Version) else ""
        if (tool == null || script.isBlank()) {
            notes[tid] = "Skrip versi belum diisi."
            runNext()
            return
        }
        checking = tid
        notes.remove(tid)
        val out = StringBuilder()
        checkHandle = startShell(
            ctx, ToolRun("${tool.name}: versi", script, ToolAction.Version.timeoutMs, "", false),
            onChunk = { if (out.length < 20_000) out.append(it) },
            onDone = { r ->
                checkHandle = null
                when {
                    r.startError != null || r.timedOut -> { notes[tid] = describe(r) }
                    r.cancelled -> { notes[tid] = "Pemeriksaan dihentikan." }
                    else -> {
                        if (r.exitCode == 0) batchOk += 1
                        ToolStore.setCheck(tid, if (r.exitCode == 0) firstLine(out.toString()) else "Tidak terdeteksi (kode ${r.exitCode})")
                    }
                }
                if (r.cancelled) {
                    queue = emptyList()
                    checking = null
                    if (batchTotal > 0) batchNote = "Dihentikan: $batchOk dari $batchTotal terdeteksi."
                    batchTotal = 0
                } else {
                    runNext()
                }
            }
        )
    }

    fun begin(ids: List<String>, batch: Boolean) {
        if (checking != null) {
            toast(ctx, "Pemeriksaan lain sedang berjalan")
            return
        }
        batchNote = null
        batchOk = 0
        batchTotal = if (batch) ids.size else 0
        queue = ids
        runNext()
    }

    Text(
        "Pusat paket Molina: pembaruan, paket Debian (apt), dan alat (pasang, cek versi, hapus). Skrip umum ada di menu Script execution.",
        color = W7D.TextDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)
    )
    UpdateStatusSection()
    Category("Debian (apt)")
    DebianInfoRow(aptLast to aptPending) // [DEBIAN-INFO]
    BadgeOverlay(if (aptPending > 0) aptPending.toString() else null, false, gold = true) {
        W7Link({ W7IconAptSync() }, "Periksa pembaruan paket", AptHistory.checkSubtitle(aptPending)) { onApt(AptJob.CheckUpdates) }
    }
    W7Link({ W7IconAptSync() }, "Perbarui dan tingkatkan paket", AptHistory.subtitle(aptLast)) { onApt(AptJob.UpdateUpgrade) }
    W7Link({ W7IconToolList() }, "Bersihkan cache dan paket yatim", "Hapus cache .deb dan paket yatim (autoremove dan apt clean): menambah ruang kosong Debian") { onApt(AptJob.CleanUp) }
    W7Link({ W7IconToolList() }, "Pasang paket Starter", "ca-certificates, python3, python3-mutagen, openssh-client, yt-dlp sekali ketuk (apt resmi Debian + yt-dlp terverifikasi SHA-256)") { onApt(AptJob.InstallStarter) } // [TERM-STARTER1] [STARTER-YTDLP]
    Spacer(Modifier.height(12.dp))
    Category("Alat")
    remember { com.molina.studio.automation.AutoPrefs.init(ctx) }
    val autoSt by com.molina.studio.automation.AutoPrefs.state.collectAsState()
    BadgeOverlay(if (autoSt.ytdlpOutdated) "Baru" else null, true, gold = true) {
        W7Link(
            { W7IconYtdlp() }, "Mesin cadangan (yt-dlp)",
            if (autoSt.ytdlpOutdated) "Pembaruan tersedia: ${autoSt.ytdlpInstalled} \u2192 ${autoSt.ytdlpLatest}"
            else "TikTok, Facebook, dll. lewat Debian (default mati)",
            onOpenYtdlp
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Alat Debian", color = W7D.NavHeader, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        ActionText("+ TAMBAH ALAT", Blue) { draft = ToolDraft(null, "", "", "", "", "") }
    }
    Spacer(Modifier.height(4.dp))
    if (checking != null) {
        ActionText("MEMERIKSA" + (if (batchTotal > 0) " ${batchTotal - queue.size}/$batchTotal" else "") + "\u2026 KETUK UNTUK BERHENTI", W7D.Ink) { checkHandle?.cancel() }
    } else {
        ActionText("PERIKSA SEMUA VERSI", W7D.Ink) {
            begin((BuiltinTools.all + custom).filter { it.version.isNotBlank() }.map { it.id }, true)
        }
    }
    batchNote?.let {
        Text(it, color = W7D.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp).padding(bottom = 6.dp))
    }
    Spacer(Modifier.height(4.dp))
    (BuiltinTools.all + custom).forEach { tool ->
        key(tool.id) {
            ToolCard(
                tool = tool,
                check = checks[tool.id],
                checking = checking == tool.id,
                note = notes[tool.id],
                expanded = expandedId == tool.id,
                onToggle = { expandedId = if (expandedId == tool.id) null else tool.id },
                onAction = { a ->
                    if (scriptOf(tool, a).isBlank()) {
                        toast(ctx, "Skrip ${a.label.lowercase()} belum diisi. Ubah alat untuk mengisinya.")
                    } else {
                        if (a == ToolAction.Remove && tool.builtin && tool.purgePkgs.isNotEmpty()) preview = tool
                        else if (a == ToolAction.Version) begin(listOf(tool.id), false)
                        else pending = PendingRun(tool, a)
                    }
                },
                onEdit = { draft = ToolDraft(tool.id, tool.name, tool.note, tool.install, tool.version, tool.remove) },
                onDrop = { toDrop = tool }
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    Category("Komponen bundel")
    remember { com.molina.studio.automation.AutoPrefs.init(ctx) }
    val bundleSt by com.molina.studio.automation.AutoPrefs.state.collectAsState()
    val bundleAll = remember { com.molina.core.common.BundledComponents.all(com.molina.engine.newpipe.NewPipeInfo.VERSION) }
    val bundleRep = remember { com.molina.core.common.BundledComponents.securityReport(bundleAll) }
    BadgeOverlay(if (bundleSt.newpipeOutdated || bundleSt.ytdlpOutdated) "Baru" else null, true, gold = true) {
        W7Link(
            { BundleLinkIcon(bundleRep.requiredVerified) }, "Komponen bundel",
            "${bundleAll.size} komponen \u00b7 ${bundleRep.hashPinned} terverifikasi SHA-256 \u00b7 ringkasan keamanan",
            onOpenBundle
        )
    }
    Spacer(Modifier.height(8.dp))

    draft?.let { d ->
        ToolEditorDialog(d, ctx, onCancel = { draft = null }, onSaved = { draft = null })
    }
    preview?.let { t ->
        PurgePreviewDialog(
            t,
            onProceed = { backup ->
                preview = null
                pending = PendingRun(t, ToolAction.Remove, backup, true)
            },
            onClose = { preview = null }
        )
    }
    pending?.let { pr ->
        val note = when (pr.action) {
            ToolAction.Install -> "Memasang ${pr.tool.name} di Debian. Butuh internet."
            ToolAction.Version -> "Memeriksa versi ${pr.tool.name}."
            ToolAction.Remove ->
                if (pr.tool.builtin) {
                    "Menghapus ${pr.tool.name} dari Debian sampai tuntas: purge paket, dependensi yatim, konfigurasi, " +
                        "cache, dan sisa berkas, lalu diverifikasi. Pengaturan pengguna ikut hilang (mis. ~/.gitconfig " +
                        "untuk Git). Aplikasi lain yang bergantung padanya bisa berhenti bekerja."
                } else {
                    "Menjalankan skrip hapus ${pr.tool.name}. Pastikan skripnya membersihkan sisa berkas sendiri."
                }
        }
        ToolRunDialog(
            ToolRun(
                "${pr.tool.name}: ${pr.action.label}",
                (if (pr.backup) ToolPurge.backupScript(pr.tool.name, pr.tool.backupPaths) else "") + scriptOf(pr.tool, pr.action),
                pr.action.timeoutMs,
                note + if (pr.backup) " Konfigurasi dicadangkan dulu ke /sdcard/${com.molina.studio.terminal.TerminalSessions.WORKSPACE_DIR}/.molina-backup." else "",
                pr.action.confirm && !pr.previewed
            ),
            onClose = { pending = null }
        ) { code, out ->
            when {
                code == -1 -> {}
                pr.action == ToolAction.Version ->
                    ToolStore.setCheck(pr.tool.id, if (code == 0) firstLine(out) else "Tidak terdeteksi (kode $code)")
                code == 0 -> ToolStore.setCheck(pr.tool.id, "")
            }
        }
    }
    toDrop?.let { t ->
        AlertDialog(
            onDismissRequest = { toDrop = null },
            title = { Text("Buang alat dari daftar?") },
            text = { Text("\"${t.name}\" dibuang dari daftar. Alat yang sudah terpasang di Debian tidak ikut dihapus.") },
            confirmButton = {
                TextButton(onClick = {
                    toDrop = null
                    if (expandedId == t.id) expandedId = null
                    ToolStore.delete(t.id)
                    toast(ctx, "\"${t.name}\" dibuang")
                }) { Text("BUANG", color = Red) }
            },
            dismissButton = { TextButton(onClick = { toDrop = null }) { Text("BATAL") } }
        )
    }
}

/**
 * Settings > Paket > Komponen bundel [BUNDLE-PAGE]: halaman read-only (ringkasan keamanan, asal, lisensi, versi
 * terakhir yang tercatat). [PAKET-VERSI] Cek versi alat dipindah ke Paket > Alat.
 */
@Composable
internal fun BundlePage(onOpenYtdlp: () -> Unit) {
    BundledComponentsSection(onOpenYtdlp = onOpenYtdlp)
}

/** Titik status kartu alat: hijau = terdeteksi, abu = belum; berdenyut emas Molina saat sedang diperiksa [SETELAN-PUSAT]. */
@Composable
private fun StatusDot(detected: Boolean, checking: Boolean) {
    if (checking) {
        val transition = rememberInfiniteTransition()
        val alpha by transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse)
        )
        Box(Modifier.size(9.dp).clip(CircleShape).background(com.molina.studio.ui.MolinaTheme.Gold.copy(alpha = alpha)))
    } else {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (detected) Color(0xFF4CAF50) else Color(0xFF8A8F98)))
    }
}

@Composable
private fun ToolCard(
    tool: ToolItem,
    check: ToolCheck?,
    checking: Boolean,
    note: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAction: (ToolAction) -> Unit,
    onEdit: () -> Unit,
    onDrop: () -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    val base = check?.takeIf { it.text.isNotBlank() }?.let { "${it.text} \u00B7 ${stamp(it.at)}" }
        ?: tool.note.ifBlank { "Pasang, versi, hapus" }
    val sub = if (checking) "memeriksa versi\u2026" else base
    Column(
        Modifier.fillMaxWidth().clip(shape).background(W7D.Card, shape)
            .border(1.dp, if (expanded) W7D.SelLine else W7D.Hairline, shape)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val detected = check?.text?.let { it.isNotBlank() && !it.startsWith("Tidak terdeteksi") } == true
            StatusDot(detected, checking)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(tool.name, color = W7D.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = W7D.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (expanded) "\u25BE" else "\u25B8", color = W7D.NavHeader, fontSize = 16.sp)
        }
        if (!expanded) return@Column
        Box(Modifier.fillMaxWidth().height(1.dp).background(W7D.Hairline))
        Column(Modifier.padding(12.dp)) {
            if (tool.note.isNotBlank()) {
                Text(tool.note, color = W7D.Ink, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            note?.let {
                Text(it, color = Color(0xFFE8A35F), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionText("PASANG", Blue) { onAction(ToolAction.Install) }
                ActionText("VERSI", W7D.Ink) { onAction(ToolAction.Version) }
                ActionText("HAPUS", Red) { onAction(ToolAction.Remove) }
            }
            if (!tool.builtin) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ActionText("UBAH", W7D.Ink, onClick = onEdit)
                    Spacer(Modifier.weight(1f))
                    ActionText("BUANG", W7D.TextDim, onClick = onDrop)
                }
            }
        }
    }
}

@Composable
private fun ToolEditorDialog(d: ToolDraft, ctx: Context, onCancel: () -> Unit, onSaved: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (d.id == null) "Alat baru" else "Ubah alat") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FieldLabel("Nama alat")
                ToolField(d.name, { d.name = it.take(ToolStore.MAX_NAME) }, singleLine = true)
                FieldLabel("Catatan (opsional)")
                ToolField(d.note, { d.note = it.take(ToolStore.MAX_NOTE) }, singleLine = true)
                FieldLabel("Skrip pasang")
                ToolField(d.install, { d.install = it }, Modifier.height(90.dp), mono = true)
                FieldLabel("Skrip versi")
                ToolField(d.version, { d.version = it }, Modifier.height(60.dp), mono = true)
                FieldLabel("Skrip hapus")
                ToolField(d.remove, { d.remove = it }, Modifier.height(90.dp), mono = true)
                Text(
                    "Dijalankan di Debian dengan sh. Contoh pasang: apt install -y htop. " +
                        "Skrip panjang lebih baik dibuat di Script execution.",
                    color = W7D.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = d.name.isNotBlank(), onClick = {
                val r = ToolStore.save(d.id, d.name, d.note, d.install, d.version, d.remove)
                toast(ctx, r.message)
                if (r.ok) onSaved()
            }) { Text("SIMPAN") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("BATAL") } }
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = W7D.NavHeader, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun ToolField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    mono: Boolean = false
) {
    val shape = RoundedCornerShape(5.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = TextStyle(
            color = W7D.Ink,
            fontSize = if (mono) 12.sp else 14.sp,
            lineHeight = if (mono) 17.sp else 20.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
        ),
        cursorBrush = SolidColor(W7D.NavHeader),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        modifier = modifier.fillMaxWidth().clip(shape).background(W7D.Field, shape).border(1.dp, W7D.Line, shape).padding(10.dp)
    )
}

@Composable
private fun ActionText(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.alpha(if (enabled) 1f else 0.35f)
            .clip(RoundedCornerShape(4.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

private fun firstLine(out: String): String =
    out.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80) ?: "OK"

private fun stamp(at: Long): String = SimpleDateFormat("d MMM HH:mm", Locale("id", "ID")).format(Date(at))

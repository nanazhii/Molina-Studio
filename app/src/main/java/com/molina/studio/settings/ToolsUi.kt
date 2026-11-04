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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.molina.studio.packages.ToolChecker
import com.molina.studio.packages.ToolItem
import com.molina.studio.packages.ToolPurge
import com.molina.studio.packages.ToolStore
import com.molina.studio.stream.YtdlpInstallState
import com.molina.studio.stream.YtdlpInstaller
import com.molina.studio.stream.YtdlpLocator
import com.molina.studio.stream.YtdlpStatus
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

private class PendingRun(
    val tool: ToolItem, val action: ToolAction, val backup: Boolean = false, val previewed: Boolean = false,
    /** [PAKET-DEPEN] alat yang dipasang otomatis setelah pemasangan ini sukses. */
    val thenInstall: ToolItem? = null
)

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
    val ytInstall by YtdlpInstaller.state.collectAsState() // [PAKET-SERAGAM]
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingRun?>(null) }
    var draft by remember { mutableStateOf<ToolDraft?>(null) }
    var toDrop by remember { mutableStateOf<ToolItem?>(null) }
    var needPython by remember { mutableStateOf<ToolItem?>(null) } // [PAKET-DEPEN]
    var preview by remember { mutableStateOf<ToolItem?>(null) }
    remember { AptHistory.init(ctx) }
    val aptLast by AptHistory.last.collectAsState()
    val aptPending by AptHistory.pending.collectAsState()

    // [PAKET-VERSI] [PAKET-CHECKER] cek versi dipegang ToolChecker (antrean berurutan, lane TOOLS); UI hanya mengamati
    val chk by ToolChecker.state.collectAsState()
    val checking = chk.checkingId
    val notes = chk.notes
    val batchTotal = chk.total
    val queueLeft = chk.remaining
    val batchNote = chk.note
    fun begin(ids: List<String>, batch: Boolean) {
        if (!ToolChecker.start(ctx, ids, batch)) toast(ctx, "Pemeriksaan lain sedang berjalan")
    }

    // [PAKET-AUTOCEK] sekali per proses (penjaganya ada di ToolChecker)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.molina.studio.automation.AutoPrefs.init(ctx)
        if (com.molina.studio.automation.AutoPrefs.state.value.autoToolCheck) ToolChecker.requestStale(ctx) // [PAKET-SAKELAR]
    }

    // [PAKET-HEADER] indikator tipis pemeriksaan versi (manual maupun otomatis)
    if (checking != null) {
        val who = (BuiltinTools.all + custom).firstOrNull { it.id == checking }?.name
        val pos = if (chk.size > 1) " ${chk.size - chk.remaining}/${chk.size}" else ""
        Text(
            "Memeriksa$pos" + (who?.let { " \u00B7 $it" } ?: "") + "\u2026",
            color = com.molina.studio.ui.MolinaTheme.Brand, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }
    Text(
        "Di sini Anda memperbarui program di dalam Debian, memasang atau menghapus alat tambahan, dan memeriksa versinya. Skrip biasa ada di menu Script execution.",
        color = W7D.TextDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)
    )
    UpdateStatusSection(onApt)
    Spacer(Modifier.height(16.dp)) // [PAKET-KARTU2]
    PackageSectionHeader("Program Debian")
    DebianInfoRow(aptLast to aptPending) // [DEBIAN-INFO]
    // [PAKET-TERPUSAT] cek/perbarui dipindah ke bagian Pembaruan di atas
    Spacer(Modifier.height(8.dp))
    PackageStatusCard(
        "Bersihkan sisa program yang tidak terpakai",
        "Hapus file unduhan lama dan program yang tidak lagi dibutuhkan agar ruang kosong bertambah",
        null, onClick = { onApt(AptJob.CleanUp) }
    )
    Spacer(Modifier.height(8.dp))
    PackageStatusCard(
        "Pasang paket dasar (Starter)",
        "Python, SSH, pengunduh video (yt-dlp), dan sertifikat keamanan sekali ketuk. Dari sumber resmi dan diperiksa keasliannya",
        null, onClick = { onApt(AptJob.InstallStarter) } // [TERM-STARTER1] [STARTER-YTDLP]
    )
    Spacer(Modifier.height(16.dp))
    PackageSectionHeader("Alat")
    // [YTDLP-PUSAT] status dan pasang/versi/hapus yt-dlp hanya di kartu "Alat tambahan di Debian"; kartu ini tautan ke sakelar
    PackageStatusCard(
        "Pengaturan pengunduh tambahan (yt-dlp)",
        "Sakelar aktif/mati. Pasang, versi, dan hapus ada di kartu yt-dlp di bawah",
        null,
        onClick = onOpenYtdlp
    )
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Alat tambahan di Debian", color = W7D.NavHeader, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        ActionText("+ TAMBAH ALAT", Blue) { draft = ToolDraft(null, "", "", "", "", "") }
    }
    Spacer(Modifier.height(4.dp))
    if (checking != null) {
        ActionText("MEMERIKSA" + (if (batchTotal > 0) " ${batchTotal - queueLeft}/$batchTotal" else "") + "\u2026 KETUK UNTUK BERHENTI", W7D.Ink) { ToolChecker.cancel() }
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
                note = if (tool.nativeInstall) ytNote(ytInstall) ?: notes[tool.id] else notes[tool.id],
                needs = tool.requires.mapNotNull { id -> BuiltinTools.all.firstOrNull { it.id == id } }
                    .filter { d -> checks[d.id]?.text?.let { it.isNotBlank() && !it.startsWith("Tidak terdeteksi") } != true }
                    .joinToString(", ") { it.name }.ifEmpty { null },
                expanded = expandedId == tool.id,
                onToggle = { expandedId = if (expandedId == tool.id) null else tool.id },
                onAction = { a ->
                    if (tool.nativeInstall && a == ToolAction.Install) {
                        if (ytInstall is YtdlpInstallState.Working) toast(ctx, "Pemasangan yt-dlp sedang berjalan")
                        else if (YtdlpLocator.status(ctx) == YtdlpStatus.NoPython && tool.requires.isNotEmpty()) needPython = tool
                        else { YtdlpInstaller.install(ctx); toast(ctx, "Memasang atau memperbarui yt-dlp di latar belakang\u2026") }
                    } else if (scriptOf(tool, a).isBlank()) {
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

    Spacer(Modifier.height(8.dp))
    PackageSectionHeader("Komponen bawaan Molina")
    remember { com.molina.studio.automation.AutoPrefs.init(ctx) }
    val bundleSt by com.molina.studio.automation.AutoPrefs.state.collectAsState()
    val bundleAll = remember { com.molina.core.common.BundledComponents.all(com.molina.engine.newpipe.NewPipeInfo.VERSION) }
    val bundleRep = remember { com.molina.core.common.BundledComponents.securityReport(bundleAll) }
    val cBundle = ManagedComponents.bundle(bundleSt, bundleAll.size, bundleRep.hashPinned, bundleRep.requiredVerified) // [KOMPONEN-PUSAT]
    PackageStatusCard(
        cBundle.title, cBundle.subtitle, cBundle.state, badge = cBundle.badge,
        onClick = onOpenBundle
    )
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
    needPython?.let { t ->
        val dep = BuiltinTools.all.firstOrNull { it.id == t.requires.firstOrNull() }
        AlertDialog(
            onDismissRequest = { needPython = null },
            title = { Text("${t.name} butuh ${dep?.name ?: "alat lain"}") },
            text = { Text("${t.name} membutuhkan ${dep?.name ?: "alat lain"} di Debian, dan belum terpasang. Pasang ${dep?.name ?: "alat itu"} dulu, lalu ${t.name} dipasang otomatis?") },
            confirmButton = {
                TextButton(onClick = {
                    needPython = null
                    if (dep != null) pending = PendingRun(dep, ToolAction.Install, thenInstall = t)
                }) { Text("PASANG KEDUANYA", color = Blue) }
            },
            dismissButton = { TextButton(onClick = { needPython = null }) { Text("BATAL") } }
        )
    }
    pending?.let { pr ->
        val note = when (pr.action) {
            ToolAction.Install -> "Memasang ${pr.tool.name} di Debian. Butuh internet." +
                (if (pr.thenInstall != null) " Setelah selesai, ${pr.thenInstall.name} dipasang otomatis." else "")
            ToolAction.Version -> "Memeriksa versi ${pr.tool.name}."
            ToolAction.Remove ->
                if (pr.tool.nativeInstall) {
                    "Menghapus yt-dlp sampai tuntas: semua salinan biner, cache, dan konfigurasi, lalu diverifikasi. " +
                        "Python 3 tidak ikut dihapus. Anda bisa memasangnya lagi kapan saja."
                } else if (pr.tool.builtin) {
                    "Menghapus ${pr.tool.name} dari Debian sampai tuntas: program, pendukung yang tidak terpakai, setelan, " +
                        "cache, dan sisa file, lalu dipastikan bersih. Setelan pribadi ikut hilang (mis. ~/.gitconfig " +
                        "untuk Git). Program lain yang memakainya bisa berhenti bekerja."
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
                pr.thenInstall != null && pr.action == ToolAction.Install && code == 0 -> {
                    ToolStore.setCheck(pr.tool.id, "")
                    YtdlpInstaller.install(ctx)
                    toast(ctx, "${pr.tool.name} terpasang. Memasang ${pr.thenInstall.name} di latar belakang\u2026")
                }
                code == 0 -> {
                    ToolStore.setCheck(pr.tool.id, "")
                    if (pr.tool.nativeInstall) com.molina.studio.automation.AutoPrefs.clearYtdlp(ctx)
                }
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
internal fun BundlePage(onOpenPackages: () -> Unit) {
    BundledComponentsSection(onOpenPackages = onOpenPackages)
}

/** Titik status kartu alat: hijau = terdeteksi, abu = belum; berdenyut emas Molina saat sedang diperiksa [SETELAN-PUSAT]. */
@Composable
private fun StatusDot(detected: Boolean, checking: Boolean, checked: Boolean = true) { // [PAKET-SERAGAM2] belum diperiksa = cincin kosong
    if (checking) {
        val transition = rememberInfiniteTransition()
        val alpha by transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse)
        )
        Box(Modifier.size(9.dp).clip(CircleShape).background(com.molina.studio.ui.MolinaTheme.Brand.copy(alpha = alpha)))
    } else {
        if (!checked) Box(Modifier.size(9.dp).clip(CircleShape).border(1.5.dp, Color(0xFF8A8F98), CircleShape))
        else Box(Modifier.size(9.dp).clip(CircleShape).background(if (detected) Color(0xFF5FB36B) else Color(0xFF8A8F98)))
    }
}

@Composable
private fun ToolCard(
    tool: ToolItem,
    check: ToolCheck?,
    checking: Boolean,
    note: String?,
    needs: String? = null, // [PAKET-CHIP] syarat yang belum terpenuhi, mis. "Python 3"
    expanded: Boolean,
    onToggle: () -> Unit,
    onAction: (ToolAction) -> Unit,
    onEdit: () -> Unit,
    onDrop: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp) // [PAKET-FRAME] sudut lebih lembut
    // [PAKET-SERAGAM2] baris seragam: "Versi terakhir: ... \u00B7 tanggal" / "Tidak terdeteksi \u00B7 tanggal" / "Belum diperiksa"
    val base = check?.takeIf { it.text.isNotBlank() }?.let {
        if (it.text.startsWith("Tidak terdeteksi")) "${it.text} \u00B7 ${stamp(it.at)}${if (it.auto) " \u00B7 otomatis" else ""}"
        else "Versi terakhir: ${it.text} \u00B7 ${stamp(it.at)}${if (it.auto) " \u00B7 otomatis" else ""}"
    } ?: "Belum diperiksa"
    val sub = if (checking) "memeriksa versi\u2026" else base
    Column(
        Modifier.fillMaxWidth().clip(shape).background(W7D.Card, shape)
            .border(1.5.dp, if (expanded) com.molina.studio.ui.MolinaTheme.Brand.copy(alpha = 0.75f) else Color(0xFF454A55), shape)
    ) {
        val detected = check?.text?.let { it.isNotBlank() && !it.startsWith("Tidak terdeteksi") } == true
        val strip = statusStripColor(detected, checking, checked = check?.text?.isNotBlank() == true)
        Row(
            Modifier.fillMaxWidth()
                .drawBehind { drawRect(strip, Offset.Zero, Size(3.dp.toPx(), size.height)) } // [PAKET-FRAME] strip status 3dp
                .clickable(onClick = onToggle).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tool.name, color = W7D.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                    )
                    if (needs != null) {
                        Spacer(Modifier.width(6.dp))
                        val chip = RoundedCornerShape(8.dp)
                        Text(
                            "Butuh $needs", color = com.molina.studio.ui.MolinaTheme.Brand, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier.clip(chip).border(1.dp, com.molina.studio.ui.MolinaTheme.Brand, chip)
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
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
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionChip(if (tool.nativeInstall) "PASANG / PERBARUI" else "PASANG", Blue) { onAction(ToolAction.Install) }
                ActionChip("VERSI", W7D.Ink) { onAction(ToolAction.Version) }
                ActionChip("HAPUS", Red) { onAction(ToolAction.Remove) }
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

/** [PAKET-FRAME] Warna strip status kartu: hijau = terdeteksi, abu = tidak/belum diperiksa, emas berdenyut = memeriksa. */
@Composable
internal fun statusStripColor(detected: Boolean, checking: Boolean, checked: Boolean): Color {
    if (checking) {
        val transition = rememberInfiniteTransition()
        val a by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse)
        )
        return com.molina.studio.ui.MolinaTheme.Brand.copy(alpha = a)
    }
    return when {
        !checked -> Color(0xFF8A8F98).copy(alpha = 0.45f)
        detected -> Color(0xFF5FB36B)
        else -> Color(0xFF8A8F98)
    }
}

/** [PAKET-FRAME] Tombol aksi berbentuk kapsul beroutline (PASANG biru, VERSI putih, HAPUS merah). */
@Composable
internal fun ActionChip(label: String, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(shape).border(1.dp, color.copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)
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

/** [PAKET-SERAGAM] Teks status pemasangan yt-dlp untuk kartu; null saat tidak ada proses. */
private fun ytNote(s: YtdlpInstallState): String? = when (s) {
    YtdlpInstallState.Idle -> null
    is YtdlpInstallState.Working -> s.message
    is YtdlpInstallState.Done -> "Berhasil, versi ${s.version ?: "?"}."
    is YtdlpInstallState.Failed -> "Gagal: ${s.message}"
}

private fun firstLine(out: String): String =
    out.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80) ?: "OK"

private fun stamp(at: Long): String = SimpleDateFormat("d MMM HH:mm", Locale("id", "ID")).format(Date(at))

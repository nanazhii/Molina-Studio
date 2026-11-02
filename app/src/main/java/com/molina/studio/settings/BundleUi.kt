package com.molina.studio.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.core.common.Assurance
import com.molina.core.common.BundledComponent
import com.molina.core.common.BundledComponents
import com.molina.core.common.ComponentReport
import com.molina.core.common.ComponentRole
import com.molina.core.common.NewPipeBump
import com.molina.engine.newpipe.NewPipeInfo
import com.molina.studio.automation.AutoPrefs
import com.molina.studio.automation.AutoState
import com.molina.studio.packages.ToolCheck
import com.molina.studio.packages.ToolStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BundleBlue = Color(0xFF6CB4FF)
private val BundleGreen = Color(0xFF7BC77E)
private val BundleAmber = Color(0xFFE0B04A)

/** id komponen Debian -> id alat bawaan di Paket (BuiltinTools). */
private fun debianToolId(id: String): String? = when (id) {
    "python3" -> "b-python3"
    "git" -> "b-git"
    "ffmpeg" -> "b-ffmpeg"
    else -> null
}

/** Salin teks ke papan klip (API Android biasa, bukan Compose, agar tidak bergantung versi Compose). */
private fun copyText(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun stampOf(at: Long): String = SimpleDateFormat("d MMM HH:mm", Locale("id", "ID")).format(Date(at))

private fun versionText(c: BundledComponent, s: AutoState, check: ToolCheck?): String = when {
    c.id == "ytdlp" -> s.ytdlpInstalled.ifEmpty { "belum terpasang" }
    c.version.isNotEmpty() -> c.version
    check != null && check.text.isNotBlank() -> check.text
    debianToolId(c.id) != null -> "versi belum diperiksa"
    else -> "versi mengikuti apt"
}

private fun updateNote(c: BundledComponent, s: AutoState): String? = when {
    c.id == "newpipe" && s.newpipeOutdated ->
        "Pembaruan tersedia: ${NewPipeInfo.VERSION} \u2192 ${s.newpipeLatest}. Naikkan pin lewat workflow bump-newpipe, tinjau PR-nya, lalu rilis."
    c.id == "ytdlp" && s.ytdlpOutdated ->
        "Pembaruan tersedia: ${s.ytdlpInstalled} \u2192 ${s.ytdlpLatest}. Perbarui di halaman yt-dlp."
    else -> null
}

private fun trustColor(a: Assurance): Color = when {
    a.verified -> BundleGreen
    a == Assurance.Asset -> W7D.TextDim
    else -> BundleAmber
}

@Composable
private fun TrustDot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color, CircleShape))
}

/** Gembok "Molina Secure" digambar langsung di Canvas (tanpa dependensi ikon): gagang busur + badan + lubang kunci. */
@Composable
private fun TrustLock(color: Color, scale: Float = 1f, glint: (() -> Float)? = null) {
    val hole = W7D.Card
    // [BUNDLE-GLINT] SrcAtop hanya bekerja benar di lapisan offscreen; dipasang hanya bila kilau diminta
    val layer = if (glint != null) Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen) else Modifier
    Canvas(Modifier.size(width = (12f * scale).dp, height = (14f * scale).dp).then(layer)) {
        val w = size.width
        val h = size.height
        val bodyTop = h * 0.40f
        drawArc(
            color = color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * 0.22f, h * 0.05f), size = Size(w * 0.56f, h * 0.70f),
            style = Stroke(width = w * 0.16f, cap = StrokeCap.Round)
        )
        drawRoundRect(color = color, topLeft = Offset(0f, bodyTop), size = Size(w, h - bodyTop), cornerRadius = CornerRadius(w * 0.18f))
        drawCircle(color = hole, radius = w * 0.11f, center = Offset(w / 2f, bodyTop + (h - bodyTop) * 0.5f))
        val g = glint?.invoke() ?: -1f
        if (g in 0f..1f) {
            val band = w * 0.55f
            val x = -band + (w + band * 2f) * g
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.7f), Color.Transparent),
                    start = Offset(x, 0f), end = Offset(x + band, h * 0.6f)
                ),
                blendMode = BlendMode.SrcAtop
            )
        }
    }
}

/** Kilau gembok hijau diputar sekali per proses aplikasi (bukan tiap halaman dibuka) agar tidak mengganggu. */
private var glintPlayed = false

/** Penanda header Ringkasan keamanan: gembok hijau lebih besar bila semua komponen Wajib terverifikasi, selain itu titik kuning. */
@Composable
private fun SummaryMark(allVerified: Boolean) {
    val glint = remember { Animatable(-1f) }
    LaunchedEffect(allVerified) {
        if (allVerified && !glintPlayed) {
            glintPlayed = true
            glint.snapTo(0f)
            glint.animateTo(1f, tween(durationMillis = 900, easing = LinearEasing))
            glint.snapTo(-1f)
        }
    }
    Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
        if (allVerified) TrustLock(BundleGreen, 1.35f, glint = { glint.value }) else TrustDot(BundleAmber)
    }
}

/** Ikon baris "Komponen bundel" di halaman Paket [BUNDLE-PAGE]: gembok hijau besar bila semua komponen Wajib terverifikasi, selain itu titik kuning. */
@Composable
internal fun BundleLinkIcon(allVerified: Boolean) {
    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
        if (allVerified) TrustLock(BundleGreen, 2.2f)
        else Box(Modifier.size(14.dp).clip(CircleShape).background(BundleAmber, CircleShape))
    }
}

/** Penanda kepercayaan: gembok bila jaminannya terverifikasi, titik berwarna bila tidak. Lebar tetap agar judul kartu sejajar. */
@Composable
private fun TrustMark(a: Assurance) {
    Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
        if (a.verified) TrustLock(trustColor(a)) else TrustDot(trustColor(a))
    }
}

@Composable
private fun RolePill(role: ComponentRole) {
    val shape = RoundedCornerShape(8.dp)
    val strong = role == ComponentRole.Wajib
    Text(
        role.label, color = if (strong) W7D.NavHeader else W7D.TextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(shape).background(if (strong) W7D.SelFill else W7D.Card, shape)
            .border(1.dp, if (strong) W7D.SelLine else W7D.Hairline, shape)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

@Composable
private fun BundleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        label, color = if (selected) W7D.Ink else W7D.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(shape).background(if (selected) W7D.SelFill else W7D.Card, shape)
            .border(1.dp, if (selected) W7D.SelLine else W7D.Hairline, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun BundleAction(label: String, onClick: () -> Unit) {
    Text(
        label, color = BundleBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
        Text(label, color = W7D.NavHeader, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(84.dp))
        Text(value, color = W7D.Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

/**
 * Settings > Paket > Komponen bundel (gaya kartu Aero Gelap seperti kartu alat): ringkasan keamanan, saring menurut
 * peran, lalu satu kartu per komponen. Sumber data: [BundledComponents]; tidak ada koneksi jaringan di sini.
 */
@Composable
internal fun BundledComponentsSection(
    onOpenYtdlp: () -> Unit
) {
    val ctx = LocalContext.current
    remember { AutoPrefs.init(ctx) }
    val st by AutoPrefs.state.collectAsState()
    val checks by ToolStore.checks.collectAsState()
    val all = remember { BundledComponents.all(NewPipeInfo.VERSION) }
    val counts = remember { BundledComponents.roleCounts(all) }
    val report = remember { BundledComponents.securityReport(all) }
    var filter by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf("") }
    val role = ComponentRole.values().firstOrNull { it.name == filter }
    val shown = BundledComponents.filter(all, role)
    val shape = RoundedCornerShape(6.dp)
    // [BUNDLE-PAGE] judul kini ada di header halaman, bukan Category di sini
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(W7D.SelFill, W7D.Card)), shape)
            .border(1.dp, W7D.Hairline, shape).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SummaryMark(report.requiredVerified)
            Spacer(Modifier.width(8.dp))
            Text("Ringkasan keamanan", color = W7D.NavHeader, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(report.headline(), color = W7D.Ink, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        val pipe = all.firstOrNull { it.id == "newpipe" }
        if (pipe != null) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TrustMark(pipe.assurance)
                Spacer(Modifier.width(8.dp))
                Text(NewPipeBump.pinSummary(pipe.version), color = W7D.Ink, fontSize = 12.sp)
            }
        }
        Text(
            if (report.requiredVerified) "Semua komponen Wajib punya hash terverifikasi atau kode sumber di repo."
            else "Perlu ditinjau (Wajib tanpa hash): " + report.requiredWithoutHash.joinToString(", "),
            color = if (report.requiredVerified) W7D.TextDim else BundleAmber,
            fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            "CI menggagalkan build bila klaim keamanan tidak punya pin nyata.",
            color = W7D.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
        )
        // [SIG-INFO] tipe build + sidik jari tanda tangan (cocokkan dengan sidik jari di log CI)
        val sig = remember { AppSignature.read(ctx) }
        Text(
            if (sig.debuggable) "Build debug: data privat app bisa dibaca lewat adb. Disarankan APK rilis untuk harian."
            else "Build rilis: tidak debuggable.",
            color = if (sig.debuggable) BundleAmber else W7D.TextDim,
            fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
        )
        sig.sha256?.let { fp ->
            Text(
                "Tanda tangan SHA-256: " + fp.take(23) + "\u2026 (ketuk untuk menyalin)",
                color = W7D.TextDim, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp).clickable { copyText(ctx, "sidik jari tanda tangan", fp) }
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BundleChip("Semua ${all.size}", role == null) { filter = "" }
        ComponentRole.values().forEach { r ->
            BundleChip("${r.label} ${counts[r] ?: 0}", role == r) { filter = r.name }
        }
    }
    Spacer(Modifier.height(4.dp))
    // [PAKET-VERSI] tombol cek versi dihapus dari Komponen bundel
    // [BUNDLE-REPORT] laporan teks polos (versi, peran, asal, jaminan) untuk ditempel ke laporan bug
    BundleAction(if (copied == "report") "LAPORAN DISALIN \u2713" else "SALIN LAPORAN KOMPONEN") {
        copyText(
            ctx, "laporan komponen",
            ComponentReport.format(
                all, report, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
                versionOf = { c -> versionText(c, st, debianToolId(c.id)?.let { checks[it] }) },
                extrasOf = { c ->
                    val ck = debianToolId(c.id)?.let { checks[it] }
                    listOfNotNull(
                        if (ck != null && ck.text.isNotBlank()) "dicek " + stampOf(ck.at) else null,
                        updateNote(c, st)
                    )
                }
            )
        )
        copied = "report"
    }
    Spacer(Modifier.height(6.dp))

    shown.forEach { c ->
        val expanded = open == c.id
        val toolId = debianToolId(c.id)
        val check = toolId?.let { checks[it] }
        val update = updateNote(c, st)
        val sub = versionText(c, st, check) + " \u00b7 " + c.origin.label
        Column(
            Modifier.fillMaxWidth().clip(shape).background(W7D.Card, shape)
                .border(1.dp, if (expanded) W7D.SelLine else W7D.Hairline, shape)
        ) {
            Row(
                Modifier.fillMaxWidth().clickable { open = if (expanded) null else c.id }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrustMark(c.assurance)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(c.name, color = W7D.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, color = W7D.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (update != null) {
                    W7Badge("Baru", true, gold = true)
                    Spacer(Modifier.width(6.dp))
                }
                RolePill(c.role)
                Spacer(Modifier.width(8.dp))
                Text(if (expanded) "\u25BE" else "\u25B8", color = W7D.NavHeader, fontSize = 16.sp)
            }
            if (expanded) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(W7D.Hairline))
                Column(Modifier.padding(12.dp)) {
                    if (update != null) {
                        Text(update, color = W7D.NavHeader, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    DetailLine("Kegunaan", c.usedBy)
                    DetailLine("Asal", c.origin.label)
                    DetailLine("Sumber", c.source)
                    DetailLine("Lisensi", c.license)
                    DetailLine("Keamanan", c.assurance.label)
                    if (c.id == "newpipe") DetailLine("Pin", NewPipeBump.pinStatus(c.version))
                    if (check != null && check.text.isNotBlank()) {
                        DetailLine("Versi", check.text)
                        DetailLine("Dicek", stampOf(check.at))
                    }
                    // [PAKET-VERSI] catatan cek versi dihapus
                    Text(c.note, color = W7D.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    if (c.id == "ytdlp") BundleAction("BUKA HALAMAN YT-DLP", onOpenYtdlp)
                    if (c.id == "newpipe" && update != null && NewPipeBump.isValidTag(st.newpipeLatest)) {
                        BundleAction(if (copied == "tag") "DISALIN \u2713" else "SALIN VERSI BARU") {
                            copyText(ctx, "versi NewPipe", st.newpipeLatest); copied = "tag"
                        }
                        NewPipeBump.ghCommand(st.newpipeLatest)?.let { cmd ->
                            BundleAction(if (copied == "gh") "DISALIN \u2713" else "SALIN PERINTAH GH") {
                                copyText(ctx, "perintah bump", cmd); copied = "gh"
                            }
                        }
                    }
                    if (toolId != null) {
                        Text("Periksa versi di Setelan > Paket > Alat.", color = W7D.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

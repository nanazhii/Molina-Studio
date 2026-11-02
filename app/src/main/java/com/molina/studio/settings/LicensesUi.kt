package com.molina.studio.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.core.common.BundledComponent
import com.molina.core.common.BundledComponents
import com.molina.engine.newpipe.NewPipeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [LICENSES-PAGE] Settings > Sistem > Info > Lisensi dan atribusi.
 * Daftar komponen dihasilkan dari [BundledComponents]; teks lisensi dibaca dari assets/licenses/ sehingga
 * bisa dibuka tanpa internet. Satu-satunya sumber daftar komponen tetap BundledComponents.
 */
internal class LicenseDoc(val key: String, val title: String, val file: String)

private val DOC_GPL3 = LicenseDoc("gpl3", "GNU GPL 3.0 (Molina, FFmpeg, NewPipeExtractor)", "molina-gpl-3.0.txt")
private val DOC_GPL2 = LicenseDoc("gpl2", "GNU GPL 2.0 (mpv, PRoot, Git)", "gpl-2.0.txt")
private val DOC_LGPL21 = LicenseDoc("lgpl21", "GNU LGPL 2.1 (libplacebo, sora-editor)", "lgpl-2.1.txt")
private val DOC_APACHE = LicenseDoc("apache2", "Apache License 2.0", "apache-2.0.txt")
private val DOC_MIT_MPV = LicenseDoc("mit-mpv-android", "MIT (mpv-android)", "mit-mpv-android.txt")
private val DOC_MIT_VSC = LicenseDoc("mit-vscode", "MIT (grammar dan tema TextMate)", "mit-vscode.txt")
private val DOC_BSD2 = LicenseDoc("bsd2-dav1d", "BSD 2-Clause (dav1d)", "bsd-2-clause-dav1d.txt")
private val DOC_ISC = LicenseDoc("isc-libass", "ISC (libass)", "isc-libass.txt")
private val DOC_OFL_JB = LicenseDoc("ofl-jb", "SIL OFL 1.1 (JetBrains Mono)", "ofl-jetbrains-mono.txt")
private val DOC_OFL_FIRA = LicenseDoc("ofl-fira", "SIL OFL 1.1 (Fira Code)", "ofl-fira-code.txt")
private val DOC_0BSD = LicenseDoc("0bsd-xz", "0BSD (XZ for Java)", "0bsd-xz-java.txt")
private val DOC_UNLICENSE = LicenseDoc("unlicense-ytdlp", "Unlicense (yt-dlp)", "unlicense-yt-dlp.txt")

private val ALL_DOCS = listOf(
    DOC_GPL3, DOC_GPL2, DOC_LGPL21, DOC_APACHE, DOC_MIT_MPV, DOC_MIT_VSC, DOC_BSD2, DOC_ISC,
    DOC_OFL_JB, DOC_OFL_FIRA, DOC_0BSD, DOC_UNLICENSE
)

/** id komponen (BundledComponents) -> teks lisensi yang dibundel. Komponen tanpa entri = teks ada di Debian. */
private val LICENSE_DOCS: Map<String, List<LicenseDoc>> = mapOf(
    "newpipe" to listOf(DOC_GPL3),
    "libmpv" to listOf(DOC_GPL3, DOC_GPL2, DOC_BSD2, DOC_ISC, DOC_LGPL21, DOC_MIT_MPV),
    "terminal" to listOf(DOC_APACHE),
    "proot" to listOf(DOC_GPL2),
    "ytdlp" to listOf(DOC_UNLICENSE),
    "sora" to listOf(DOC_LGPL21, DOC_MIT_VSC),
    "fonts" to listOf(DOC_OFL_JB, DOC_OFL_FIRA),
    "archive" to listOf(DOC_APACHE, DOC_0BSD),
    "git" to listOf(DOC_GPL2)
)

/** Label singkat pada lencana lisensi. */
private val LICENSE_CHIP: Map<String, String> = mapOf(
    "newpipe" to "GPL-3.0",
    "libmpv" to "GPL-3.0+",
    "terminal" to "Apache-2.0",
    "proot" to "GPL-2.0+",
    "rootfs" to "Beragam",
    "ytdlp" to "Unlicense",
    "sora" to "LGPL-2.1+",
    "fonts" to "OFL-1.1",
    "archive" to "Apache-2.0 / 0BSD",
    "python3" to "PSF",
    "git" to "GPL-2.0",
    "ffmpeg" to "LGPL/GPL"
)

private val LICENSE_GROUPS: List<Pair<String, List<String>>> = listOf(
    "Pemutar media dan unduhan" to listOf("libmpv", "newpipe", "ytdlp"),
    "Terminal dan Debian" to listOf("terminal", "proot", "rootfs", "python3", "git", "ffmpeg"),
    "Editor dan berkas" to listOf("sora", "fonts", "archive")
)

private const val REPO_URL = "https://github.com/nanazhii/Molina-Studio"

/**
 * Susun ulang teks lisensi hasil hard-wrap (78 kolom) agar mengalir di layar ponsel: baris digabung dalam satu paragraf,
 * kecuali baris sebelumnya pendek (judul, hak cipta) atau baris berikutnya berawalan butir ("(a)", "1.", "-").
 * Berkas aslinya tidak diubah.
 */
internal fun reflowLicense(raw: String): List<String> {
    val bullet = Regex("^(\\(?[0-9A-Za-z]{1,3}[.)]|[-*\\u2022])\\s")
    return raw.replace("\r\n", "\n").replace('\r', '\n')
        .split(Regex("\n[ \t]*\n"))
        .map { para ->
            val sb = StringBuilder()
            var prevLen = 0
            for (line in para.split('\n').map { it.trim() }.filter { it.isNotEmpty() }) {
                if (sb.isNotEmpty()) sb.append(if (prevLen < 45 || bullet.containsMatchIn(line)) '\n' else ' ')
                sb.append(line)
                prevLen = line.length
            }
            sb.toString()
        }
        .filter { it.isNotEmpty() }
}

private fun openLink(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(ctx, "Tidak ada browser untuk membuka tautan", Toast.LENGTH_SHORT).show() }
}

@Composable
internal fun LicensesPage() {
    val ctx = LocalContext.current
    val items = remember { BundledComponents.all(NewPipeInfo.VERSION) }
    var viewKey by rememberSaveable { mutableStateOf("") }
    var openId by rememberSaveable { mutableStateOf("") }
    val viewing = ALL_DOCS.firstOrNull { it.key == viewKey }
    BackHandler(enabled = viewing != null) { viewKey = "" }

    if (viewing != null) {
        LicenseTextViewer(viewing) { viewKey = "" }
    } else {
        HubCard(
            "Molina Studio",
            listOf(
                HubRow("Lisensi", "GPL-3.0-or-later \u00b7 ketuk untuk membaca teks lengkap", onClick = { viewKey = DOC_GPL3.key }),
                HubRow("Hak cipta", "\u00a9 Molina Studio (nanazhii). Tanpa jaminan apa pun."),
                HubRow("Kode sumber", REPO_URL, onClick = { openLink(ctx, REPO_URL) }),
                HubRow("Salin alamat", "$REPO_URL.git", onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Molina kode sumber", "$REPO_URL.git"))
                    Toast.makeText(ctx, "Alamat repo disalin", Toast.LENGTH_SHORT).show()
                })
            ),
            dots = false
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Molina adalah perangkat lunak bebas: Anda boleh memakai, mempelajari, mengubah, dan membagikannya sesuai GPL. " +
                "Komponen pihak ketiga di bawah ini tetap memakai lisensinya masing-masing. Semua teks lisensi tersimpan " +
                "di dalam aplikasi, jadi bisa dibaca tanpa internet.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Lencana: kuning = GPL (copyleft kuat), biru = LGPL (copyleft lemah), hijau = lisensi permisif atau lainnya.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            style = MaterialTheme.typography.bodySmall
        )
        val byId = items.associateBy { it.id }
        val grouped = LICENSE_GROUPS.flatMap { it.second }.toSet()
        val sections = LICENSE_GROUPS.map { g -> g.first to g.second.mapNotNull { byId[it] } } +
            listOf("Lainnya" to items.filter { it.id !in grouped })
        sections.forEach { s ->
            if (s.second.isNotEmpty()) {
                Category(s.first)
                s.second.forEach { c ->
                    LicenseRow(
                        c = c,
                        expanded = openId == c.id,
                        docs = LICENSE_DOCS[c.id].orEmpty(),
                        onToggle = { openId = if (openId == c.id) "" else c.id },
                        onRead = { viewKey = it.key }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun LicenseRow(
    c: BundledComponent,
    expanded: Boolean,
    docs: List<LicenseDoc>,
    onToggle: () -> Unit,
    onRead: (LicenseDoc) -> Unit
) {
    val shape = RoundedCornerShape(3.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(shape)
            .border(1.dp, W7D.Hairline, shape).background(W7D.Card)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(c.name, color = W7D.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (c.version.isNotBlank()) Text(c.version, color = W7D.TextDim, fontSize = 12.sp)
            }
            LicenseChip(LICENSE_CHIP[c.id] ?: c.license.take(18))
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) "\u25BE" else "\u25B8", color = W7D.TextDim, fontSize = 14.sp)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
                LicenseDetail("Dipakai untuk", c.usedBy)
                LicenseDetail("Sumber", c.source)
                LicenseDetail("Lisensi", c.license)
                if (docs.isEmpty()) {
                    Text(
                        "Dipasang dari Debian. Teks lisensinya ada di dalam Debian: /usr/share/doc/<paket>/copyright.",
                        color = W7D.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
                    )
                } else {
                    docs.forEach { d -> TextButton(onClick = { onRead(d) }) { Text("Baca: " + d.title) } }
                }
            }
        }
    }
}

@Composable
private fun LicenseDetail(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(label, color = W7D.TextDim, fontSize = 12.sp, modifier = Modifier.width(96.dp))
        Text(value, color = W7D.Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LicenseChip(label: String) {
    val tone = when {
        label.startsWith("LGPL") -> Color(0xFF5AA9E6)
        label.startsWith("GPL") -> Color(0xFFE5A93C)
        else -> Color(0xFF4CC38A)
    }
    Text(
        label, color = tone, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.border(1.dp, tone, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun LicenseTextViewer(doc: LicenseDoc, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember(doc.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(doc.key) {
        text = withContext(Dispatchers.IO) {
            runCatching {
                ctx.assets.open("licenses/" + doc.file).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull() ?: ""
        }
    }
    TextButton(onClick = onClose) { Text("\u2039 Kembali ke daftar lisensi") }
    Text(doc.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    val t = text
    if (t == null) {
        Text("Membuka teks lisensi...", color = W7D.TextDim, fontSize = 12.sp)
    } else if (t.isEmpty()) {
        Text(
            "Teks lisensi tidak ditemukan di aplikasi. Teks lengkap ada di kode sumber: $REPO_URL",
            color = W7D.TextDim, fontSize = 12.sp
        )
    } else {
        val paragraphs = remember(t) { reflowLicense(t) }
        paragraphs.forEach { p ->
            Text(
                p, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

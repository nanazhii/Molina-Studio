package com.molina.studio.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.core.common.BtopProbe
import com.molina.core.common.BtopRow
import com.molina.core.common.BtopSort
import com.molina.core.common.TaskGroup
import com.molina.studio.cinema.Win7
import com.molina.studio.cinema.Win7Button
import com.molina.studio.system.BtopMonitor
import com.molina.studio.system.BtopSnapshot
import com.molina.studio.system.TaskActions
import com.molina.studio.terminal.StatsSnapshot
import com.molina.studio.terminal.SystemStats
import com.molina.studio.ui.MolinaTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [BTOP-MONITOR] Tab "btop" di Monitor Sistem: CPU per inti, RAM/swap, jaringan, dan tabel proses detail. */

private val BtopLocale: Locale = Locale("id", "ID")
private const val BTOP_HISTORY = 200 // 200 sampel x 1,5 dtk = 5 menit
private val BtopHistoryLabel = "${BTOP_HISTORY * BtopMonitor.PERIOD_MS / 60_000L} menit"
private val BtopPlot = Color(0xFF070A0E)

/** Riwayat disimpan di tingkat proses supaya tidak hilang saat pindah tab. Hanya di memori (ringan). */
private object BtopHistory {
    val cpu = mutableStateListOf<Float>()
    val ram = mutableStateListOf<Float>()
    fun push(list: MutableList<Float>, v: Float) {
        list.add(v.coerceIn(0f, 100f))
        while (list.size > BTOP_HISTORY) list.removeAt(0)
    }
}

internal val BtopBg = Color(0xFF0B0F14)
internal val BtopLine = Color(0xFF1F2933)
internal val BtopText = Color(0xFFD6DEE6)
internal val BtopDim = Color(0xFF7B8794)
private val BtopGreen = Color(0xFF3BE07A)
private val BtopYellow = Color(0xFFFFD54F)
private val BtopRed = Color(0xFFFF5252)

/** Gradasi hijau, kuning, merah menurut beban 0..1 (seperti meter btop). */
private fun btopColor(f: Float): Color {
    val x = f.coerceIn(0f, 1f)
    return if (x < 0.5f) lerp(BtopGreen, BtopYellow, x / 0.5f) else lerp(BtopYellow, BtopRed, (x - 0.5f) / 0.5f)
}

private fun fmt1(v: Float): String = String.format(BtopLocale, "%.1f", v)

/** KB -> teks ringkas: 512K, 123M, 1,23G. */
private fun memCompact(kb: Long): String = when {
    kb < 0L -> "\u2014"
    kb >= 1024L * 1024L -> String.format(BtopLocale, "%.2fG", kb / 1048576.0)
    kb >= 1024L -> String.format(BtopLocale, "%.0fM", kb / 1024.0)
    else -> "${kb}K"
}

private fun stateText(c: Char): String = when (c) {
    'R' -> "R (berjalan)"
    'S' -> "S (tidur)"
    'D' -> "D (menunggu I/O)"
    'T', 't' -> "T (dihentikan)"
    'Z' -> "Z (zombie)"
    else -> c.toString()
}

/** Grafik riwayat ala btop: satu batang per sampel (terbaru di kanan), warna menurut beban, kisi 25/50/75%. */
@Composable
private fun BtopGraph(values: List<Float>, modifier: Modifier = Modifier) {
    val s = BtopProbe.summarize(values)
    Canvas(modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(4.dp)).background(BtopPlot).border(1.dp, BtopLine, RoundedCornerShape(4.dp))) {
        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(BtopLine, Offset(0f, y), Offset(size.width, y), 1f)
        }
        val bw = size.width / BTOP_HISTORY
        val n = values.size
        for (i in 0 until n) {
            val v = values[i].coerceIn(0f, 100f)
            if (v <= 0f) continue
            val h = (v / 100f * size.height).coerceAtLeast(1f)
            val x = size.width - (n - i) * bw
            drawRect(btopColor(v / 100f), Offset(x, size.height - h), Size((bw * 0.85f).coerceAtLeast(1f), h))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("-$BtopHistoryLabel", color = BtopDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text("rata-rata ${fmt1(s.avg)}% \u00B7 puncak ${fmt1(s.peak)}%", color = BtopText, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text("kini", color = BtopDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

/** Bilah bersegmen ala btop; tiap sel diwarnai menurut posisinya (hijau di kiri, merah di kanan). */
@Composable
private fun BtopBar(fraction: Float, modifier: Modifier = Modifier, cells: Int = 30, height: androidx.compose.ui.unit.Dp = 10.dp) {
    val f = fraction.coerceIn(0f, 1f)
    Canvas(modifier.fillMaxWidth().height(height)) {
        val gap = 1.5.dp.toPx()
        val cw = (size.width - gap * (cells - 1)) / cells
        val lit = (f * cells + 0.0001f).toInt().coerceIn(0, cells)
        for (i in 0 until cells) {
            val x = i * (cw + gap)
            val c = if (i < lit) btopColor(i / (cells - 1f).coerceAtLeast(1f)) else BtopLine
            drawRoundRect(c, Offset(x, 0f), Size(cw, size.height), CornerRadius(1.5.dp.toPx()))
        }
    }
}

@Composable
private fun BtopLabelRow(label: String, value: String, valueColor: Color = BtopText) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = BtopDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = valueColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun BtopPanel(title: String, right: String? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(BtopBg, shape).border(1.dp, BtopLine, shape).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = MolinaTheme.Brand, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            if (right != null) Text(right, color = BtopDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        content()
    }
}

@Composable
private fun BtopChip(label: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Text(
        label,
        color = if (on) BtopBg else BtopText,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(if (on) MolinaTheme.Brand else BtopLine, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

@Composable
private fun BtopHead(label: String, key: BtopSort, cur: BtopSort, desc: Boolean, modifier: Modifier, align: TextAlign, onSort: (BtopSort) -> Unit) {
    val arrow = if (key == cur) (if (desc) "\u25BC" else "\u25B2") else ""
    Text(
        label + arrow,
        modifier = modifier.clickable { onSort(key) },
        color = if (key == cur) MolinaTheme.Brand else BtopDim,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        textAlign = align,
        maxLines = 1
    )
}

@Composable
private fun BtopRowView(row: BtopRow, prefix: String, cpuShown: Float, selected: Boolean, onClick: () -> Unit) {
    val cpuColor = btopColor((cpuShown / 100f).coerceIn(0f, 1f))
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFF16323A) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).background(tmGroupColor(row.group), RoundedCornerShape(2.dp)))
        Text(row.pid.toString(), Modifier.width(44.dp).padding(start = 3.dp), color = BtopDim, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
        Text(
            prefix + row.name,
            Modifier.weight(1f).padding(start = 6.dp),
            color = BtopText, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(row.state.toString(), Modifier.width(12.dp), color = if (row.state == 'R') BtopGreen else BtopDim, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
        Text(row.threads.toString(), Modifier.width(26.dp), color = BtopDim, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
        Text(fmt1(cpuShown), Modifier.width(40.dp), color = cpuColor, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
        Text(memCompact(row.memKb), Modifier.width(52.dp), color = BtopText, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
    }
}

/** Tab "btop": satu aliran sampel, tiga panel ringkasan (CPU, Memori, Jaringan) dan tabel proses. */
@Composable
internal fun BtopTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var snap by remember { mutableStateOf(BtopSnapshot()) }
    var net by remember { mutableStateOf(StatsSnapshot()) }
    var key by remember { mutableStateOf(BtopSort.Cpu) }
    var desc by remember { mutableStateOf(true) }
    var treeMode by remember { mutableStateOf(false) }
    var perCore by remember { mutableStateOf(true) } // true: 100% = satu inti (gaya btop)
    var query by remember { mutableStateOf("") }
    var selPid by remember { mutableStateOf<Int?>(null) }
    var fd by remember { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf<Pair<BtopRow, Boolean>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ctx) {
        BtopMonitor.snapshots(ctx).collect { s ->
            snap = s
            BtopHistory.push(BtopHistory.cpu, s.appCpu / s.cpuCount.coerceAtLeast(1))
            s.mem?.let { m -> BtopHistory.push(BtopHistory.ram, m.usedKb * 100f / m.totalKb.coerceAtLeast(1L)) }
        }
    }
    LaunchedEffect(ctx) { SystemStats.shared(ctx).collect { net = it } }
    LaunchedEffect(selPid, snap) {
        fd = selPid?.let { p -> withContext(Dispatchers.IO) { BtopProbe.fdCount(p) } }
    }

    val cores = snap.cpuCount.coerceAtLeast(1)
    val scale = if (perCore) 1f else 1f / cores
    fun shown(r: BtopRow): Float = r.cpu * scale
    fun toggleSort(k: BtopSort) {
        if (k == key) desc = !desc else { key = k; desc = k != BtopSort.Name && k != BtopSort.Pid }
    }

    val filtered = remember(snap.rows, query) { BtopProbe.filter(snap.rows, query) }
    val lines = remember(filtered, key, desc, treeMode) {
        if (treeMode) BtopProbe.tree(filtered, key, desc)
        else BtopProbe.sort(filtered, key, desc).map { com.molina.core.common.BtopTreeLine(it, 0, "") }
    }
    val sel = snap.rows.firstOrNull { it.pid == selPid }
    val canEnd = sel != null && !sel.isSelf

    RgbFrame(Modifier.fillMaxWidth(), spot = RgbSpot.Monitor) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(BtopBg).padding(8.dp)) { // [BTOP-DARK]
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                W7IconMonitorGraph(18.dp)
                Spacer(Modifier.width(8.dp))
                Text("Monitor btop Molina", color = MolinaTheme.Brand, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            // ---- CPU
            val cpuTotalShown = snap.appCpu * scale
            BtopPanel("CPU", "Molina ${fmt1(cpuTotalShown)}% \u00B7 ${cores} inti") {
                if (snap.cores.isNotEmpty()) {
                    snap.cores.forEach { c ->
                        val frac = c.usage?.div(100f) ?: if (c.freqMhz != null && (c.maxMhz ?: 0) > 0) c.freqMhz.toFloat() / c.maxMhz!! else 0f
                        val right = buildString {
                            if (c.usage != null) append(fmt1(c.usage)).append('%')
                            if (c.freqMhz != null) { if (isNotEmpty()) append("  "); append(c.freqMhz).append("MHz") }
                            if (isEmpty()) append("\u2014")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("C${c.index}", Modifier.width(22.dp), color = BtopDim, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                            BtopBar(frac, Modifier.weight(1f))
                            Text(right, Modifier.width(96.dp), color = BtopText, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, maxLines = 1)
                        }
                    }
                    if (!snap.hasCoreUsage) {
                        Text(
                            "Pemakaian per inti diblokir Android (/proc/stat). Bilah menunjukkan frekuensi saat ini terhadap maksimum, bukan beban.",
                            color = Color(0xFFE0A800), fontSize = 10.sp
                        )
                    }
                }
                Text("Riwayat CPU Molina ($BtopHistoryLabel, % seluruh kapasitas)", color = BtopDim, fontSize = 10.sp)
                BtopGraph(BtopHistory.cpu)
                snap.load?.let { BtopLabelRow("Load avg", "${fmt1(it.one)}  ${fmt1(it.five)}  ${fmt1(it.fifteen)}") }
                    ?: Text("Load average tidak terbaca di perangkat ini.", color = BtopDim, fontSize = 10.sp)
                BtopLabelRow("Uptime", BtopProbe.runtimeText(snap.uptimeSec))
            }
            Spacer(Modifier.height(6.dp))
            // ---- Memori
            val mem = snap.mem
            BtopPanel("MEM", if (mem != null) "total ${memCompact(mem.totalKb)}" else null) {
                if (mem == null) {
                    Text("/proc/meminfo tidak terbaca.", color = BtopDim, fontSize = 10.sp)
                } else {
                    BtopLabelRow("Terpakai", "${memCompact(mem.usedKb)} (${(mem.usedKb * 100 / mem.totalKb.coerceAtLeast(1))}%)")
                    BtopBar(mem.usedKb.toFloat() / mem.totalKb.coerceAtLeast(1))
                    BtopLabelRow("Tersedia", memCompact(mem.availableKb))
                    BtopLabelRow("Cache + buffer", memCompact(mem.cachedKb + mem.buffersKb))
                    if (mem.swapTotalKb > 0L) {
                        BtopLabelRow("Swap", "${memCompact(mem.swapUsedKb)} / ${memCompact(mem.swapTotalKb)}")
                        BtopBar(mem.swapUsedKb.toFloat() / mem.swapTotalKb)
                    }
                    Text("Riwayat RAM perangkat ($BtopHistoryLabel)", color = BtopDim, fontSize = 10.sp)
                    BtopGraph(BtopHistory.ram)
                    if (snap.appPssKb >= 0L) {
                        BtopLabelRow("Molina (PSS)", "${memCompact(snap.appPssKb)} (${fmt1(snap.appPssKb * 100f / mem.totalKb.coerceAtLeast(1))}%)", MolinaTheme.Brand)
                        BtopBar(snap.appPssKb.toFloat() / mem.totalKb.coerceAtLeast(1))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // ---- Jaringan
            BtopPanel("NET", if (net.netPerUid) "hanya Molina" else "total perangkat") {
                BtopLabelRow("\u2193 Unduh", net.rxText, MolinaTheme.NetDown)
                BtopLabelRow("\u2191 Unggah", net.txText, MolinaTheme.NetUp)
            }
            Spacer(Modifier.height(6.dp))
            // ---- Proses
            BtopPanel("PROC", "${filtered.size}/${snap.rows.size} proses") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = BtopText, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(MolinaTheme.Brand),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box(
                                Modifier.fillMaxWidth().background(BtopLine, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                if (query.isEmpty()) Text("cari nama / cmdline / PID", color = BtopDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                inner()
                            }
                        }
                    )
                    BtopChip("pohon", treeMode) { treeMode = !treeMode }
                    BtopChip(if (perCore) "1 inti" else "semua", true) { perCore = !perCore }
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Spacer(Modifier.width(9.dp))
                    BtopHead("PID", BtopSort.Pid, key, desc, Modifier.width(44.dp), TextAlign.End, ::toggleSort)
                    BtopHead("Nama", BtopSort.Name, key, desc, Modifier.weight(1f).padding(start = 6.dp), TextAlign.Start, ::toggleSort)
                    Text("S", Modifier.width(12.dp), color = BtopDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                    BtopHead("THR", BtopSort.Threads, key, desc, Modifier.width(26.dp), TextAlign.End, ::toggleSort)
                    BtopHead("CPU%", BtopSort.Cpu, key, desc, Modifier.width(40.dp), TextAlign.End, ::toggleSort)
                    BtopHead("MEM", BtopSort.Memory, key, desc, Modifier.width(52.dp), TextAlign.End, ::toggleSort)
                }
                if (lines.isEmpty()) {
                    Text(if (snap.rows.isEmpty()) "Membaca daftar proses\u2026" else "Tidak ada proses yang cocok.", color = BtopDim, fontSize = 11.sp)
                }
                lines.forEach { l ->
                    BtopRowView(l.row, l.prefix, shown(l.row), l.row.pid == selPid) { selPid = l.row.pid }
                }
                BtopLabelRow(
                    "Total",
                    "${snap.rows.size} proses \u00B7 ${snap.rows.sumOf { it.threads }} thread \u00B7 ${fmt1(snap.appCpu * scale)}%"
                )
            }
            // ---- Detail proses terpilih
            if (sel != null) {
                Spacer(Modifier.height(6.dp))
                BtopPanel("DETAIL", "PID ${sel.pid}") {
                    BtopLabelRow("Nama", sel.name)
                    BtopLabelRow("PID / PPID", "${sel.pid} / ${sel.ppid}")
                    BtopLabelRow("Status", stateText(sel.state))
                    BtopLabelRow("Thread / nice", "${sel.threads} / ${sel.nice}")
                    BtopLabelRow("CPU", "${fmt1(sel.cpu)}% (1 inti) \u00B7 ${fmt1(sel.cpu / cores)}% (semua)")
                    BtopLabelRow("RSS", memCompact(sel.rssKb))
                    BtopLabelRow("PSS", if (sel.pssKb >= 0L) memCompact(sel.pssKb) else "mengukur\u2026")
                    BtopLabelRow("Berjalan", BtopProbe.runtimeText(sel.runtimeSec))
                    BtopLabelRow("File terbuka", fd?.toString() ?: "\u2014")
                    BtopLabelRow("Grup", sel.group.label)
                    if (sel.cmdline.isNotEmpty()) Text(sel.cmdline, color = BtopDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Win7Button("Akhiri Proses", Modifier.weight(1f), enabled = canEnd, compact = true) { confirm = sel to false }
                        Win7Button("Akhiri Pohon", Modifier.weight(1f), enabled = canEnd, compact = true) { confirm = sel to true }
                    }
                    if (sel.isSelf) Text("Proses inti aplikasi dilindungi dan tidak dapat diakhiri.", color = Color(0xFFE0A800), fontSize = 10.sp)
                    msg?.let { Text(it, color = BtopText, fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TaskGroup.entries.forEach { g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(tmGroupColor(g), RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(4.dp))
                        Text(g.label, color = Win7.TextDim, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
            Text(
                "Hanya proses milik Molina yang terlihat (aturan Android; proses aplikasi lain tidak bisa dibaca tanpa root). " +
                    "CPU: 100% = satu inti penuh (ubah ke \"semua\" untuk skala seluruh kapasitas). " +
                    "MEM memakai PSS (bagian bersama dibagi adil, diukur tiap ~5 dtk), RSS bila belum terukur. " +
                    "Data perangkat (per inti, RAM, load) hanya tampil bila diizinkan Android.",
                color = Win7.TextDim,
                fontSize = 10.sp
            )
        }
    }

    confirm?.let { (row, tree) ->
        TaskConfirm(
            title = if (tree) "Akhiri pohon proses?" else "Akhiri proses?",
            body = "${row.name} (PID ${row.pid})\n" +
                if (tree) "Seluruh proses turunannya ikut dihentikan." else "Hanya proses ini yang dihentikan; proses anaknya tetap berjalan.",
            onConfirm = {
                confirm = null
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        if (tree) TaskActions.endTree(row.pid) else TaskActions.endProcess(row.pid)
                    }
                    msg = tmReapText(row.name, res)
                }
            },
            onDismiss = { confirm = null }
        )
    }
}

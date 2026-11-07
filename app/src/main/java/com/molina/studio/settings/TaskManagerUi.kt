package com.molina.studio.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.core.common.AppVisibility
import com.molina.core.common.ReapResult
import com.molina.core.common.Refreshers
import com.molina.core.common.TaskGroup
import com.molina.core.common.TaskRow
import com.molina.core.common.TaskSort
import com.molina.core.common.TaskTable
import com.molina.studio.cinema.Win7
import com.molina.studio.cinema.Win7Button
import com.molina.studio.system.TaskActions
import com.molina.studio.system.TaskMonitor
import com.molina.studio.ui.MolinaTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [TASK-MANAGER] Pengelola Tugas ala Task Manager desktop (Windows 7): tab Aplikasi, Proses, Kinerja. */

private val TmLocale: Locale = Locale("id", "ID")

internal fun tmGroupColor(g: TaskGroup): Color = when (g) {
    TaskGroup.App -> Color(0xFFE0A800)
    TaskGroup.Terminal -> Color(0xFF3BB33B)
    TaskGroup.Unduhan -> Color(0xFF2D6DB5)
    TaskGroup.Code -> Color(0xFF8E5BD0)
    TaskGroup.Lainnya -> Color(0xFF8A8A8A)
}

private fun tmMemText(kb: Long): String = when {
    kb < 0L -> "\u2014"
    kb >= 1024L * 1024L -> String.format(TmLocale, "%.1f GB", kb / 1048576.0)
    else -> String.format(TmLocale, "%.1f MB", kb / 1024.0)
}

private fun tmCpuColor(p: Int): Color = when {
    p >= 50 -> MolinaTheme.Bad
    p >= 20 -> Color(0xFFE0A800)
    else -> Win7.Ink
}

internal fun tmReapText(name: String, r: ReapResult): String =
    if (r.found == 0) "$name: tidak ada proses yang bisa diakhiri (sudah selesai atau dilindungi)."
    else "$name: ${r.total} diakhiri (${r.terminated} bersih, ${r.killed} paksa)" +
        if (r.remaining > 0) ", ${r.remaining} masih hidup." else "."

/** Halaman Monitor Sistem: Aplikasi | Proses | Kinerja, seperti tab Task Manager desktop. */
@Composable
internal fun SystemMonitorPage() {
    var tab by rememberSaveable { mutableStateOf(1) }
    Spacer(Modifier.height(4.dp))
    TaskTabStrip(listOf("Aplikasi", "Proses", "Kinerja", "btop"), tab) { tab = it } // [BTOP-MONITOR]
    when (tab) {
        0 -> AppsTab()
        1 -> ProcessesTab()
        2 -> PerformanceTab()
        else -> BtopTab() // [BTOP-MONITOR]
    }
}

@Composable
private fun TaskTabStrip(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        tabs.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(shape)
                    .background(if (on) Win7.SelFill else Win7.NavBg, shape)
                    .border(1.dp, if (on) Win7.SelLine else Win7.Line, shape)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (on) Win7.Ink else Win7.TextDim,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
internal fun TaskConfirm(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Win7.Face,
        titleContentColor = Win7.Ink,
        textContentColor = Win7.Ink,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Akhiri", color = MolinaTheme.Bad) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

@Composable
private fun HeaderCell(
    label: String,
    key: TaskSort?,
    cur: TaskSort,
    desc: Boolean,
    modifier: Modifier,
    align: TextAlign,
    onSort: (TaskSort) -> Unit
) {
    val arrow = if (key != null && key == cur) (if (desc) " \u25BC" else " \u25B2") else ""
    Text(
        label + arrow,
        modifier = modifier.then(if (key != null) Modifier.clickable { onSort(key) } else Modifier),
        color = Win7.NavHeader,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = align,
        maxLines = 1
    )
}

@Composable
private fun ProcessRowView(r: TaskRow, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Win7.SelFill else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, Win7.SelLine) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(tmGroupColor(r.group), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text(
            r.name,
            modifier = Modifier.weight(1f),
            color = Win7.Ink,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(r.pid.toString(), Modifier.width(48.dp), color = Win7.TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
        Text("${r.cpuPercent}%", Modifier.width(40.dp), color = tmCpuColor(r.cpuPercent), fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
        Text(tmMemText(r.rssKb), Modifier.width(68.dp), color = Win7.Ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
    }
}

@Composable
private fun ProcessesTab() {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<TaskRow>>(emptyList()) }
    var key by remember { mutableStateOf(TaskSort.Cpu) }
    var desc by remember { mutableStateOf(true) }
    var selPid by remember { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf<Pair<TaskRow, Boolean>?>(null) } // true = seluruh pohon
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { TaskMonitor.rows().collect { rows = it } }

    val sorted = remember(rows, key, desc) { TaskTable.sort(rows, key, desc) }
    val sel = sorted.firstOrNull { it.pid == selPid }
    val canEnd = sel != null && !sel.isSelf

    RgbFrame(Modifier.fillMaxWidth(), spot = RgbSpot.Monitor) {
        Win7GlassWindow("Pengelola Tugas Molina", icon = { W7IconMonitor(18.dp) }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Spacer(Modifier.width(14.dp))
                HeaderCell("Nama Gambar", TaskSort.Name, key, desc, Modifier.weight(1f), TextAlign.Start) { k ->
                    if (k == key) desc = !desc else { key = k; desc = false }
                }
                HeaderCell("PID", TaskSort.Pid, key, desc, Modifier.width(48.dp), TextAlign.End) { k ->
                    if (k == key) desc = !desc else { key = k; desc = false }
                }
                HeaderCell("CPU", TaskSort.Cpu, key, desc, Modifier.width(40.dp), TextAlign.End) { k ->
                    if (k == key) desc = !desc else { key = k; desc = true }
                }
                HeaderCell("Memori", TaskSort.Memory, key, desc, Modifier.width(68.dp), TextAlign.End) { k ->
                    if (k == key) desc = !desc else { key = k; desc = true }
                }
            }
            if (sorted.isEmpty()) {
                Text("Membaca daftar proses\u2026", color = Win7.TextDim, fontSize = 12.sp)
            }
            sorted.forEach { r ->
                ProcessRowView(r, selected = r.pid == selPid) { selPid = r.pid }
            }
            Text(
                "Proses: ${rows.size}   CPU: ${TaskTable.totalCpu(rows)}%   Memori (RSS): ${tmMemText(TaskTable.totalRssKb(rows))}",
                color = Win7.TextDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Win7Button("Akhiri Proses", Modifier.weight(1f), enabled = canEnd, compact = true) {
                    sel?.let { confirm = it to false }
                }
                Win7Button("Akhiri Pohon", Modifier.weight(1f), enabled = canEnd, compact = true) {
                    sel?.let { confirm = it to true }
                }
            }
            if (sel?.isSelf == true) {
                Text("Proses inti aplikasi dilindungi dan tidak dapat diakhiri.", color = Color(0xFFE0A800), fontSize = 11.sp)
            }
            msg?.let { Text(it, color = Win7.Ink, fontSize = 12.sp) }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TaskGroup.entries.forEach { g ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(tmGroupColor(g), RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(4.dp))
                Text(g.label, color = Win7.TextDim, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
    Text(
        "Hanya proses milik aplikasi ini yang terlihat (aturan Android). CPU dihitung per inti dari selisih 2 detik. " +
            "Pengelompokan berdasarkan nama proses; mpv berjalan di dalam proses aplikasi, jadi dikelola dari tab Aplikasi.",
        color = Win7.TextDim,
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
    )

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

private data class ModRow(val id: String, val label: String, val count: Int, val memKb: Long)

@Composable
private fun AppsTab() {
    val scope = rememberCoroutineScope()
    var mods by remember { mutableStateOf<List<ModRow>>(emptyList()) }
    var selId by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) } // id modul, atau "*" untuk semua
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            AppVisibility.foreground.first { it }
            mods = withContext(Dispatchers.IO) {
                Refreshers.all().map {
                    ModRow(
                        it.id,
                        it.label,
                        runCatching { it.activeCount() }.getOrDefault(0),
                        runCatching { it.memoryKb() }.getOrDefault(-1L)
                    )
                }
            }
            delay(2000L)
        }
    }
    val sel = mods.firstOrNull { it.id == selId }

    RgbFrame(Modifier.fillMaxWidth(), spot = RgbSpot.Monitor) {
        Win7GlassWindow("Aplikasi Molina", icon = { W7IconMonitor(18.dp) }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text("Tugas", Modifier.weight(1f), color = Win7.NavHeader, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text("Status", Modifier.width(76.dp), color = Win7.NavHeader, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text("Proses", Modifier.width(48.dp), color = Win7.NavHeader, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                Text("Memori", Modifier.width(68.dp), color = Win7.NavHeader, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
            }
            if (mods.isEmpty()) Text("Belum ada modul terdaftar.", color = Win7.TextDim, fontSize = 12.sp)
            mods.forEach { m ->
                val on = m.id == selId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (on) Win7.SelFill else Color.Transparent)
                        .then(if (on) Modifier.border(1.dp, Win7.SelLine) else Modifier)
                        .clickable { selId = m.id }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(m.label, Modifier.weight(1f), color = Win7.Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (m.count > 0) "Berjalan" else "Tidak aktif",
                        Modifier.width(76.dp),
                        color = if (m.count > 0) Color(0xFF3BB33B) else Win7.TextDim,
                        fontSize = 11.sp
                    )
                    Text(m.count.toString(), Modifier.width(48.dp), color = Win7.Ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
                    Text(tmMemText(m.memKb), Modifier.width(68.dp), color = Win7.Ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
                }
            }
            Text(
                "Tugas: ${mods.size}   Berjalan: ${mods.count { it.count > 0 }}",
                color = Win7.TextDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Win7Button("Akhiri Tugas", Modifier.weight(1f), enabled = sel != null, compact = true) { confirm = sel?.id }
                Win7Button("Akhiri Semua", Modifier.weight(1f), enabled = mods.any { it.count > 0 }, compact = true) { confirm = "*" }
            }
            msg?.let { Text(it, color = Win7.Ink, fontSize = 12.sp) }
        }
    }
    Text(
        "Tiap tugas dimatikan lewat jalur resmi modulnya (sesi, layanan, mpv), lalu SIGTERM/SIGKILL bila perlu. " +
            "Aplikasi tetap hidup.",
        color = Win7.TextDim,
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
    )

    confirm?.let { id ->
        val all = id == "*"
        val label = if (all) "semua tugas" else mods.firstOrNull { it.id == id }?.label ?: id
        TaskConfirm(
            title = "Akhiri $label?",
            body = "Semua proses latar milik $label akan dihentikan. Sesi yang sedang berjalan akan terputus.",
            onConfirm = {
                confirm = null
                scope.launch {
                    val reports = withContext(Dispatchers.IO) {
                        if (all) Refreshers.refreshAll() else listOf(Refreshers.refresh(id))
                    }
                    msg = reports.joinToString("\n") { r ->
                        val name = mods.firstOrNull { it.id == r.id }?.label ?: r.id
                        "$name: dihentikan ${r.stopped}, paksa ${r.killed}, tersisa ${r.remaining}" +
                            (r.note?.let { " ($it)" } ?: "")
                    }
                }
            },
            onDismiss = { confirm = null }
        )
    }
}

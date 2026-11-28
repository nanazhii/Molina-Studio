package com.molina.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File
import com.molina.studio.ui.MolinaTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.molina.core.ui.Win7
import com.molina.core.ui.Win7Button

fun w7Names(l: List<File>): String = if (l.size == 1) l[0].name else l.size.toString() + " item"

val W7_ID = Locale("id", "ID")

val W7_LISTS = com.molina.core.files.FileTypes.LISTS // [FILE-CONTRACT]

val W7_MEDIA = com.molina.core.files.FileTypes.MEDIA

fun w7Date(ms: Long) = SimpleDateFormat("dd/MM/yyyy HH.mm", W7_ID).format(Date(ms))

/** [FEATURE-EXPLORER] Tempat/drive di panel navigasi Penjelajah (label, folder, penanda folder Molina). */
class W7Place(val label: String, val dir: File, val molina: Boolean = false)

fun w7Crumbs(dir: File, drives: List<W7Place>): List<Pair<String, File>> {
    val base = drives.filter { dir.path == it.dir.path || dir.path.startsWith(it.dir.path + "/") }
        .maxByOrNull { it.dir.path.length } ?: W7Place(dir.name.ifEmpty { "/" }, dir)
    val out = mutableListOf(Pair(base.label, base.dir))
    var cur = base.dir
    dir.path.removePrefix(base.dir.path).split('/').filter { it.isNotEmpty() }.forEach {
        cur = File(cur, it)
        out.add(Pair(it, cur))
    }
    return out
}

/** [HELP-PANEL] Tombol info ringkas: lingkaran biru tebal berisi "i" (hemat tempat di bilah alat). */
private val W7_HELP_BLUE = MolinaTheme.Link // biru tautan #6CB4FF, sama dengan tombol aksi Paket

@Composable
fun W7HelpIcon(onClick: () -> Unit) {
    Box(Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 5.dp)) {
        Box(
            Modifier.size(17.dp).border(2.dp, W7_HELP_BLUE, RoundedCornerShape(9.dp)),
            Alignment.Center
        ) { Text("i", color = W7_HELP_BLUE, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold) }
    }
}

/** [HELP-PANEL] Layar bantuan: judul, ringkasan, lalu daftar istilah-penjelasan yang bisa digulir. */
@Composable
fun W7HelpPanel(
    title: String, summary: String, items: List<Pair<String, String>>, onDismiss: () -> Unit
) {
    val th = LocalExplorerTheme.current // [EXPLORER-THEME-2]
    W7Panel(title, onDismiss) {
        Text(summary, color = Win7.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            items.forEach { (term, desc) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        term, color = th.brand, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(84.dp)
                    )
                    Text(desc, color = Win7.Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Win7Button("Tutup", isDefault = true) { onDismiss() }
        }
    }
}

@Composable
fun W7Panel(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(shape)
                .background(Brush.verticalGradient(listOf(Win7.FrameTop, Win7.FrameBottom)), shape)
                .border(1.dp, Win7.FrameLine, shape).padding(14.dp)
        ) {
            Text(title, color = Win7.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

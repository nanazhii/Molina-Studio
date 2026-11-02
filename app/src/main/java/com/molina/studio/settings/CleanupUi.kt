package com.molina.studio.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dialog Settings > Penyimpanan > "Pusat Pembersihan Cerdas": pilih kategori, lihat estimasi, konfirmasi, bersihkan. */
@Composable
internal fun CleanupDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<CleanupItem>?>(null) }
    var picked by remember { mutableStateOf<Set<CleanupId>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick) {
        items = null
        val r = withContext(Dispatchers.IO) { CleanupManager.scan(ctx) { !isActive } }
        items = r
        picked = r.filter { it.defaultOn && it.blockedReason == null && it.bytes > 0 }.map { it.id }.toSet()
        confirm = false
    }

    val list = items
    val shown = list.orEmpty().filter { it.bytes > 0 || it.blockedReason != null }
    val estimate = shown.filter { it.id in picked }.sumOf { it.bytes }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    fun toggle(id: CleanupId) {
        picked = if (id in picked) picked - id else picked + id
        confirm = false
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Pusat Pembersihan Cerdas") },
        text = {
            Column {
                when {
                    list == null -> Text("Menghitung ukuran (rootfs besar, mohon tunggu)...", color = dim, fontSize = 13.sp)
                    shown.isEmpty() -> Text("Tidak ada yang perlu dibersihkan.", fontSize = 13.sp)
                    else -> Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                        shown.forEach { it ->
                            val blocked = it.blockedReason != null
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !blocked && !busy) { toggle(it.id) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = it.id in picked,
                                    enabled = !blocked && !busy,
                                    onCheckedChange = { _ -> toggle(it.id) }
                                )
                                Column {
                                    Text(it.title + "  " + fmtBytes(it.bytes), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text(it.blockedReason ?: it.detail, color = dim, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Estimasi dibebaskan: " + fmtBytes(estimate), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (confirm) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (CleanupId.TrashBin in picked) "Recycle Bin dihapus permanen dan tidak bisa dipulihkan. Lanjutkan?"
                        else "Item terpilih akan dihapus. Lanjutkan?",
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && list != null && picked.isNotEmpty(),
                onClick = {
                    if (!confirm) {
                        confirm = true
                    } else {
                        busy = true
                        scope.launch {
                            val freed = withContext(Dispatchers.IO) { CleanupManager.clean(ctx, picked) }
                            Toast.makeText(ctx, "Dibersihkan: " + fmtBytes(freed) + " dibebaskan", Toast.LENGTH_LONG).show()
                            busy = false
                            tick++
                        }
                    }
                }
            ) { Text(if (busy) "Membersihkan..." else if (confirm) "Ya, bersihkan" else "Bersihkan") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Tutup") } }
    )
}

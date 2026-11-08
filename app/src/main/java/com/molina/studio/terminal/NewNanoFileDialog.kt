package com.molina.studio.terminal

import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.cinema.ExplorerRecent
import com.molina.studio.settings.MolinaExplorer

/** [NANO-EXT] Pecah/gabung nama berkas + daftar ekstensi umum. Dotfile (.bashrc) dianggap tanpa ekstensi. */
internal object NanoName {
    val EXTENSIONS = listOf( // [NANO-TEMPLATE] urutan: skrip di atas; md/txt di bawah (sudah dipakai code editor)
        "sh", "py", "kt", "kts", "java", "json", "xml", "yml", "yaml", "toml", "ini", "conf",
        "log", "csv", "html", "css", "js", "c", "cpp", "h", "gradle", "properties", "md", "txt"
    )
    const val NONE = "(tanpa)"
    /** [NANO-TEMPLATE] Nama bawaan "Berkas baru" dari Terminal: tetap, tidak mengikuti berkas terakhir di Penjelajah. */
    const val TEMPLATE = "skrip.sh"

    /** "skrip.tar.gz" -> ("skrip.tar", "gz"); ".bashrc" -> (".bashrc", ""); "README" -> ("README", ""). */
    fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) name to "" else name.substring(0, dot) to name.substring(dot + 1)
    }

    fun join(stem: String, ext: String): String {
        val e = ext.trim().trimStart('.')
        return if (e.isEmpty() || e == NONE) stem.trim() else stem.trim() + "." + e
    }

    /** Nama bawaan: mengikuti berkas terakhir di Penjelajah bila ada, bila tidak "catatan.txt". */
    fun initial(last: String?): Pair<String, String> = split(last ?: TEMPLATE)
}

/**
 * [NANO-NEW] Dialog "Berkas baru (nano)" dari drawer Terminal. Folder bawaan = folder terakhir yang dibuka di Files
 * (bila tak ada: akar penyimpanan internal). Nama + ekstensi bawaan = template (skrip.sh), TIDAK mengikuti berkas Penjelajah;
 * ekstensi dipilih lewat dropdown atau diketik bebas, tidak dikunci ke .txt.
 */
@Composable
internal fun NewNanoFileDialog(onDismiss: () -> Unit, onOpened: () -> Unit) {
    val ctx = LocalContext.current
    val dir = remember { ExplorerRecent.last(ctx) ?: Environment.getExternalStorageDirectory() }
    val init = remember { NanoName.initial(null) } // [NANO-TEMPLATE] selalu template, bukan nama berkas terakhir
    var stem by remember { mutableStateOf(init.first) }
    var ext by remember { mutableStateOf(init.second) }
    var menu by remember { mutableStateOf(false) }
    val full = NanoName.join(stem, ext)
    fun go() {
        if (stem.isNotBlank() && MolinaExplorer.newInTerminal(ctx, dir, full)) onOpened()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Berkas baru (nano)") },
        text = {
            Column {
                Text("Folder: " + MolinaExplorer.debianPath(dir), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = stem,
                        onValueChange = { stem = it },
                        singleLine = true,
                        label = { Text("Nama berkas") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { go() })
                    )
                    Box {
                        OutlinedTextField(
                            value = ext,
                            onValueChange = { ext = it.filter { c -> c != '/' && c != '\\' && !c.isWhitespace() } },
                            singleLine = true,
                            label = { Text("Ekstensi") },
                            prefix = { Text(".") },
                            trailingIcon = { TextButton(onClick = { menu = true }) { Text("▾") } },
                            modifier = Modifier.width(132.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { go() })
                        )
                        // [NANO-EXT-SCROLL] Tinggi menu dibatasi (~6 item terlihat); sisanya di-scroll, tidak memanjang satu layar.
                        DropdownMenu(
                            expanded = menu,
                            onDismissRequest = { menu = false },
                            modifier = Modifier.heightIn(max = 240.dp)
                        ) {
                            DropdownMenuItem(text = { Text(NanoName.NONE) }, modifier = Modifier.height(40.dp), onClick = { ext = ""; menu = false })
                            NanoName.EXTENSIONS.forEach { e ->
                                DropdownMenuItem(text = { Text(".$e", fontFamily = FontFamily.Monospace) }, modifier = Modifier.height(40.dp), onClick = { ext = e; menu = false })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Hasil: " + full, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { go() }, enabled = stem.isNotBlank()) { Text("Buka") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

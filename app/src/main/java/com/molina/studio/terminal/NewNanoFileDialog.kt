package com.molina.studio.terminal

import android.os.Environment
import android.widget.Toast
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import com.molina.studio.ui.MolinaTheme
import androidx.compose.runtime.rememberCoroutineScope
import com.molina.studio.cinema.ExplorerRecent
import com.molina.studio.cinema.FileOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
internal fun NewNanoFileDialog(
    onDismiss: () -> Unit, onOpened: () -> Unit, baseDir: java.io.File? = null,
    onCreated: (java.io.File) -> Unit = {} // [DRAWER-MERGE] "Buat" = berkas kosong tanpa membuka nano
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope() // [DRAWER-BOTTOM]
    val dir = remember { baseDir ?: ExplorerRecent.last(ctx) ?: Environment.getExternalStorageDirectory() } // [FILE-DRAWER3] folder aktif drawer
    val init = remember { NanoName.initial(null) } // [NANO-TEMPLATE] selalu template, bukan nama berkas terakhir
    var stem by remember { mutableStateOf(init.first) }
    var ext by remember { mutableStateOf(init.second) }
    var menu by remember { mutableStateOf(false) }
    val full = NanoName.join(stem, ext)
    fun create() { // [DRAWER-MERGE] berkas kosong, tanpa membuka nano
        if (stem.isBlank()) return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { FileOps.touch(dir, full) } }
            r.onSuccess { onCreated(it) }
                .onFailure { Toast.makeText(ctx, it.message ?: "Gagal membuat berkas", Toast.LENGTH_SHORT).show() }
        }
    }
    fun go() { // buka di nano (juga aksi tombol Enter di keyboard)
        if (stem.isBlank()) return
        if (MolinaExplorer.newInTerminal(ctx, dir, full)) onOpened()
    }
    val frameShape = RoundedCornerShape(10.dp)
    Dialog(onDismissRequest = onDismiss) { // [NANO-DIALOG-BLACK] hitam pekat, bingkai putih keabuan, tinggi dipadatkan
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.Black, frameShape)
                .border(1.dp, NanoDialogFrame, frameShape)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                "Berkas baru", // [DRAWER-MERGE]
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "Folder: " + MolinaExplorer.debianPath(dir),
                color = NanoDialogDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NanoField(
                    value = stem, onValueChange = { stem = it }, hint = "Nama berkas",
                    modifier = Modifier.weight(1f), onDone = { go() }
                )
                Box {
                    NanoField(
                        value = ext,
                        onValueChange = { ext = it.filter { c -> c != '/' && c != '\\' && !c.isWhitespace() } },
                        hint = "ekstensi", modifier = Modifier.width(124.dp), prefix = ".", onDone = { go() },
                        trailing = {
                            Text(
                                "\u25BE", color = NanoDialogFrame, fontSize = 16.sp,
                                modifier = Modifier.clickable { menu = true }.padding(start = 6.dp)
                            )
                        }
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
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onDismiss, modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) { Text("Batal", color = NanoDialogDim) }
                TextButton(
                    onClick = { create() }, enabled = stem.isNotBlank(), modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) { // [DRAWER-MERGE]
                    Text("Buat", color = if (stem.isNotBlank()) Color.White else NanoDialogDim.copy(alpha = 0.5f))
                }
                TextButton(
                    onClick = { go() }, enabled = stem.isNotBlank(), modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text(
                        "Buka di nano",
                        color = if (stem.isNotBlank()) MolinaTheme.Brand else NanoDialogDim.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// [NANO-DIALOG-BLACK] warna dialog nano: bingkai putih keabuan (sama dengan bingkai tombol nano), teks sekunder abu terang
private val NanoDialogFrame = Color(0xFFD0D4DB)
private val NanoDialogDim = Color(0xFFA3A8B0)

/** [NANO-DIALOG-BLACK] Kolom isian padat (38dp) berbingkai tipis; pengganti OutlinedTextField 56dp agar dialog tidak tinggi. */
@Composable
private fun NanoField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onDone: () -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(NanoDialogFrame),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .border(1.dp, NanoDialogFrame.copy(alpha = 0.55f), shape)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (prefix != null) Text(prefix, color = NanoDialogDim, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(hint, color = NanoDialogDim.copy(alpha = 0.6f), fontSize = 14.sp)
                    inner()
                }
                if (trailing != null) trailing()
            }
        }
    )
}

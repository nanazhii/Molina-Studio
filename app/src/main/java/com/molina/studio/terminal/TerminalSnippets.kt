package com.molina.studio.terminal

import android.content.Context
import android.widget.Toast
import com.molina.core.common.MolinaDir
import com.molina.core.common.MolinaPaths
import com.molina.studio.AppPaths
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.studio.cinema.Win7
import com.molina.studio.cinema.Win7Button
import com.molina.studio.settings.Win7GlassWindow
import org.json.JSONArray
import org.json.JSONObject

/** Snippet perintah Terminal (SharedPreferences molina_ui/term_snippets). Bawaan dipakai sampai pengguna mengubah daftar. */
internal object TerminalSnippets {
    class Snippet(val name: String, val cmd: String)

    private const val PREFS = "molina_ui"
    private const val KEY = "term_snippets"
    private var loaded = false

    val items = mutableStateListOf<Snippet>()

    private fun defaults() = listOf(
        Snippet("Perbarui paket", "apt update && apt upgrade -y"),
        Snippet("Status git", "git status -sb"),
        Snippet("Log git ringkas", "git log --oneline -10"),
        Snippet("Ukuran folder", "du -sh * | sort -h"),
        Snippet("Port terbuka", "ss -tulpn")
    )

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val raw = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val list = if (raw == null) restoreOrDefaults(ctx) else try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Snippet(o.getString("n"), o.getString("c"))
            }
        } catch (e: Exception) {
            defaults()
        }
        items.clear()
        items.addAll(list)
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("n", it.name).put("c", it.cmd)) }
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
        autoBackup(ctx)
    }

    fun add(ctx: Context, name: String, cmd: String) {
        items.add(Snippet(name.trim(), cmd.trim()))
        save(ctx)
    }

    /** Dari menu Code (seleksi/baris kursor): simpan sebagai snippet. Mengembalikan pesan untuk toast. */
    fun addFromCode(ctx: Context, name: String, cmd: String): String {
        load(ctx)
        val c = cmd.trim()
        if (c.isEmpty()) return "Tidak ada teks untuk disimpan"
        if (items.any { it.cmd == c }) return "Snippet sudah ada"
        val n = name.trim().ifEmpty { "Snippet" }
        add(ctx, n, c)
        return "Snippet disimpan: $n"
    }

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun snapshotJson(): String {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("n", it.name).put("c", it.cmd)) }
        return JSONObject().put("app", "molina-terminal").put("version", 1).put("snippets", arr).toString(2) + "\n"
    }

    /** Cadangan otomatis tiap daftar berubah, ke berkas yang sama dengan Ekspor; ditulis di thread latar. */
    private fun autoBackup(ctx: Context) {
        val json = snapshotJson()
        val f = backupFile(ctx)
        io.execute {
            try {
                f.parentFile?.mkdirs()
                f.writeText(json)
            } catch (e: Exception) {
                // izin penyimpanan belum ada: abaikan, Ekspor manual tetap tersedia
            }
        }
    }

    /** Prefs kosong (pasang baru / data aplikasi dihapus): pulihkan dari cadangan bila ada, selain itu bawaan. */
    private fun restoreOrDefaults(ctx: Context): List<Snippet> {
        return try {
            val f = readableBackup(ctx) ?: return defaults()
            val arr = JSONObject(f.readText()).getJSONArray("snippets")
            val out = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val n = o.optString("n").trim().take(60)
                val c = o.optString("c").trim()
                if (n.isEmpty() || c.isEmpty() || c.length > 4000) null else Snippet(n, c)
            }.take(200)
            if (out.isEmpty()) defaults() else out
        } catch (e: Exception) {
            defaults()
        }
    }

    /** Berkas cadangan di folder aplikasi: /sdcard/Molina/molina-snippets.json (terlihat juga dari Debian). */
    private fun backupFile(ctx: Context): File = File(AppPaths.get(ctx).dir(MolinaDir.Backup), "molina-snippets.json")

    /** Lokasi lama (sebelum folder Backup): tetap dibaca bila berkas baru belum ada. */
    private fun legacyBackupFile(ctx: Context): File = File(AppPaths.get(ctx).appRoot, "molina-snippets.json")

    private fun readableBackup(ctx: Context): File? =
        backupFile(ctx).takeIf { it.isFile } ?: legacyBackupFile(ctx).takeIf { it.isFile }

    fun exportToFile(ctx: Context): String {
        load(ctx)
        return try {
            val f = backupFile(ctx)
            f.parentFile?.mkdirs()
            val arr = JSONArray()
            items.forEach { arr.put(JSONObject().put("n", it.name).put("c", it.cmd)) }
            val root = JSONObject().put("app", "molina-terminal").put("version", 1).put("snippets", arr)
            f.writeText(root.toString(2) + "\n")
            "Diekspor ${items.size} snippet ke ${MolinaDir.Backup.folderName}/${f.name}"
        } catch (e: Exception) {
            "Gagal mengekspor snippet"
        }
    }

    /** Daftar snippet sebagai larik JSON (untuk cadangan gabungan). */
    fun snapshot(ctx: Context): JSONArray {
        load(ctx)
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("n", it.name).put("c", it.cmd)) }
        return arr
    }

    /** Gabungkan snippet dari [arr]; perintah yang sudah ada dilewati. Maks. 200 snippet, 4000 karakter per perintah. */
    fun mergeFrom(ctx: Context, arr: JSONArray): Int {
        load(ctx)
        var added = 0
        for (i in 0 until arr.length()) {
            if (items.size >= 200) break
            val o = arr.getJSONObject(i)
            val n = o.optString("n").trim().take(60)
            val c = o.optString("c").trim()
            if (n.isEmpty() || c.isEmpty() || c.length > 4000) continue
            if (items.any { it.cmd == c }) continue
            items.add(Snippet(n, c))
            added++
        }
        if (added > 0) save(ctx)
        return added
    }

    fun importFromFile(ctx: Context): String {
        val f = readableBackup(ctx)
            ?: return "Berkas tidak ditemukan: ${MolinaDir.Backup.folderName}/molina-snippets.json"
        return try {
            val added = mergeFrom(ctx, JSONObject(f.readText()).getJSONArray("snippets"))
            "Diimpor $added snippet baru"
        } catch (e: Exception) {
            "Berkas cadangan tidak valid"
        }
    }

    /** Pindahkan snippet dari posisi [from] ke [to] (urut ulang daftar). */
    fun move(ctx: Context, from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices || from == to) return
        items.add(to, items.removeAt(from))
        save(ctx)
    }

    fun removeAt(ctx: Context, index: Int) {
        if (index in items.indices) {
            items.removeAt(index)
            save(ctx)
        }
    }
}

@Composable
private fun SnipBtn(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier.padding(start = 4.dp).height(28.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(Win7.BtnTop, Win7.BtnBottom)), shape)
            .border(1.dp, Win7.BtnLine, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        Alignment.Center
    ) { Text(label, color = Win7.Ink, fontSize = 12.sp) }
}

/**
 * Dialog snippet: "Sisip" menulis perintah ke prompt tanpa Enter (bisa diedit dulu), "Jalan" menulis lalu Enter.
 * Daftar bisa ditambah dan dihapus (tombol x).
 */
@Composable
internal fun SnippetDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    remember(ctx) { TerminalSnippets.load(ctx) }
    var name by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Win7GlassWindow(
                title = "Snippet perintah",
                icon = { Text("\u276F", color = Color.White, fontSize = 12.sp) }
            ) {
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    TerminalSnippets.items.forEachIndexed { i, s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, color = Win7.Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    s.cmd, color = Win7.TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                            }
                            SnipBtn("Sisip") { TerminalActions.insert(s.cmd); onDismiss() }
                            SnipBtn("Jalan") { TerminalActions.insert(s.cmd); TerminalActions.send("\r"); onDismiss() }
                            Column {
                                Text(
                                    "\u25B2", color = if (i > 0) Win7.TextDim else Color.Transparent, fontSize = 11.sp,
                                    modifier = Modifier.clickable(enabled = i > 0) { TerminalSnippets.move(ctx, i, i - 1) }
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                                Text(
                                    "\u25BC",
                                    color = if (i < TerminalSnippets.items.lastIndex) Win7.TextDim else Color.Transparent,
                                    fontSize = 11.sp,
                                    modifier = Modifier.clickable(enabled = i < TerminalSnippets.items.lastIndex) {
                                        TerminalSnippets.move(ctx, i, i + 1)
                                    }.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            Text(
                                "\u2715", color = Win7.TextDim, fontSize = 13.sp,
                                modifier = Modifier.clickable { TerminalSnippets.removeAt(ctx, i) }.padding(8.dp)
                            )
                        }
                    }
                }
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Win7.Ink,
                    unfocusedTextColor = Win7.Ink,
                    focusedBorderColor = Win7.DefaultLine,
                    unfocusedBorderColor = Win7.Line,
                    cursorColor = Win7.DefaultLine,
                    focusedPlaceholderColor = Win7.TextDim,
                    unfocusedPlaceholderColor = Win7.TextDim,
                    focusedContainerColor = Win7.Input,
                    unfocusedContainerColor = Win7.Input
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(3.dp),
                    placeholder = { Text("Nama snippet", fontSize = 13.sp) }, colors = fieldColors
                )
                OutlinedTextField(
                    value = cmd, onValueChange = { cmd = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(3.dp),
                    placeholder = { Text("Perintah", fontSize = 13.sp) }, colors = fieldColors
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start)) {
                    Win7Button("Ekspor") { Toast.makeText(ctx, TerminalSnippets.exportToFile(ctx), Toast.LENGTH_LONG).show() }
                    Win7Button("Impor") { Toast.makeText(ctx, TerminalSnippets.importFromFile(ctx), Toast.LENGTH_LONG).show() }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    Win7Button("Tutup", onClick = onDismiss)
                    Win7Button("Tambah", isDefault = true, enabled = name.isNotBlank() && cmd.isNotBlank()) {
                        TerminalSnippets.add(ctx, name, cmd)
                        name = ""
                        cmd = ""
                    }
                }
            }
        }
    }
}

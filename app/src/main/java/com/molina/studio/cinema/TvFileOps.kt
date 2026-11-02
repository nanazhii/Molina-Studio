package com.molina.studio.cinema

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.molina.core.common.ChannelTidy
import com.molina.core.common.LogLevel
import com.molina.core.common.ResumeMark
import com.molina.core.common.TrashBin
import com.molina.core.common.TrashItem
import com.molina.core.common.TvGuide
import com.molina.studio.log.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Volume penyimpanan (internal + kartu SD/OTG) untuk Recycle Bin; sama dengan daftar drive penjelajah Win7. */
internal fun tvVolumes(): List<File> {
    val out = mutableListOf(Environment.getExternalStorageDirectory())
    File("/storage").listFiles()?.forEach {
        if (it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self") out.add(it)
    }
    return out
}

internal sealed class TvSheet {
    class Rename(val file: File) : TvSheet()
    class Delete(val file: File) : TvSheet()
    class Dest(val file: File, val move: Boolean) : TvSheet()
    class Tidy(val plan: ChannelTidy.Plan) : TvSheet()
}

/**
 * Operasi berkas Mode TV (SIDE-5, tahap 5) di atas FileOps/TrashBin yang sama dengan penjelajah Win7:
 * ganti nama, salin, pindah, dan hapus (selalu ke Recycle Bin, bisa diurungkan). Jalur di [guard]
 * (akar volume, Recycle Bin, akar Molina, folder saluran) tidak bisa diubah namanya/dipindah/dihapus.
 * Operasi jalan di Dispatchers.IO, satu per satu, dengan progres dan pembatalan.
 */
internal class TvOps(
    private val ctx: Context,
    private val scope: CoroutineScope,
    val volumes: List<File>,
    val guard: Set<String>,
    private val resumeDirs: List<File>,
    private val onChanged: () -> Unit,
) {
    var busy by mutableStateOf(false)
        private set
    var progress by mutableStateOf<Float?>(null)
        private set
    var message by mutableStateOf("")
        private set
    var ok by mutableStateOf(false)
        private set
    var undo by mutableStateOf<List<TrashItem>?>(null)
        private set
    var tidyUndo by mutableStateOf<ChannelTidy.Result?>(null)
        private set

    private val cancel = AtomicBoolean(false)
    private var clearJob: Job? = null

    fun cancelOp() { cancel.set(true) }

    private fun say(text: String, good: Boolean) {
        clearJob?.cancel()
        message = text
        ok = good
    }

    /** Pesan akhir hilang sendiri; yang punya "Urungkan" bertahan lebih lama. */
    private fun finish(text: String, good: Boolean) {
        say(text, good)
        clearJob = scope.launch {
            delay(if (undo != null || tidyUndo != null) 9000L else 5000L)
            if (!busy) { message = ""; undo = null; tidyUndo = null }
        }
    }

    private fun protect(f: File) {
        if (!TvGuide.canModify(f, guard)) throw IOException("Folder ini dilindungi: " + f.name)
    }

    private fun run(op: (FileOps.Ctl) -> String, onDone: () -> Unit = {}) {
        if (busy) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            finish("Izin tulis penyimpanan belum diberikan. Aktifkan izin Penyimpanan di Setelan Android.", false)
            return
        }
        busy = true
        progress = null
        cancel.set(false)
        undo = null
        tidyUndo = null
        say("Memproses\u2026", true)
        scope.launch {
            val ctl = FileOps.Ctl(cancel) { p -> progress = p }
            val r = withContext(Dispatchers.IO) { runCatching { op(ctl) } }
            busy = false
            progress = null
            r.onSuccess { onDone(); finish(it, true) }
                .onFailure {
                    finish(it.message ?: ("Operasi gagal (" + it.javaClass.simpleName + ")"), false)
                    if (it.message != "Dibatalkan") {
                        LogBus.log("penjelajah", LogLevel.Error, "FileOps", android.util.Log.getStackTraceString(it))
                    }
                }
            onChanged()
        }
    }

    fun rename(f: File, newName: String) = run(op = {
        protect(f)
        val nf = FileOps.rename(f, newName)
        if (nf.path != f.path) ResumeMark.move(resumeDirs, f.path, nf.path)
        "Nama diubah: " + nf.name
    })

    fun copy(f: File, dest: File) = run(op = { ctl ->
        val made = FileOps.copyAll(listOf(f), dest, ctl)
        "Disalin ke " + dest.name + ": " + (made.firstOrNull()?.name ?: f.name)
    })

    fun move(f: File, dest: File) = run(op = { ctl ->
        protect(f)
        val made = FileOps.moveAll(listOf(f), dest, ctl)
        made.firstOrNull()?.let { ResumeMark.move(resumeDirs, f.path, it.path) }
        "Dipindah ke " + dest.name + ": " + (made.firstOrNull()?.name ?: f.name)
    })

    fun trash(f: File) {
        val moved = arrayOfNulls<List<TrashItem>>(1)
        run(
            op = { _ ->
                protect(f)
                val r = TrashBin.trash(listOf(f), volumes, guard)
                moved[0] = r
                "Dipindahkan ke Recycle Bin: " + r.size + " item"
            },
            onDone = { undo = moved[0] }
        )
    }

    /** [CINEMA-TIDY] Pindahkan video di [plan] ke folder kanalnya (bisa diurungkan lewat [undoTidy]). */
    fun tidy(plan: ChannelTidy.Plan) {
        val res = arrayOfNulls<ChannelTidy.Result>(1)
        run(
            op = { _ ->
                val r = ChannelTidy.apply(plan, resumeDirs) { cancel.get() }
                res[0] = r
                val folders = r.moved.mapNotNull { it.to.parentFile?.path }.toSet().size
                "Dirapikan: " + r.videos + " video ke " + folders + " folder kanal" + (if (r.failed > 0) ", " + r.failed + " gagal" else "")
            },
            onDone = { tidyUndo = res[0]?.takeIf { it.moved.isNotEmpty() } }
        )
    }

    fun undoTidy() {
        val r = tidyUndo ?: return
        run(op = { _ -> "Dipulihkan: " + ChannelTidy.undo(r, resumeDirs) + " berkas" })
    }

    fun undoTrash() {
        val items = undo ?: return
        run(op = { _ -> "Dipulihkan: " + TrashBin.restore(items, volumes).size + " item" })
    }
}

/** Dialog rename / konfirmasi hapus / pilih tujuan untuk [sheet]; null = tidak ada dialog. */
@Composable
internal fun TvOpsDialogs(sheet: TvSheet?, ops: TvOps, channels: List<TvGuide.Channel>, onClose: () -> Unit) {
    when (sheet) {
        null -> Unit
        is TvSheet.Rename -> TvRenameDialog(sheet.file, onClose) { name -> onClose(); ops.rename(sheet.file, name) }
        is TvSheet.Delete -> W7Panel("Recycle Bin", onClose) {
            val f = sheet.file
            Text(
                "Pindahkan ke Recycle Bin: \"" + f.name + "\"" + (if (f.isDirectory) " beserta seluruh isinya?" else "?"),
                color = Win7.Ink, fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            Text("Bisa dipulihkan dari Recycle Bin selama " + TrashBin.RETAIN_DAYS + " hari.", color = Win7.TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Win7Button("Pindahkan", isDefault = true) { onClose(); ops.trash(f) }
                Spacer(Modifier.width(8.dp))
                Win7Button("Batal", onClick = onClose)
            }
        }
        is TvSheet.Tidy -> W7Panel("Rapikan ke folder kanal", onClose) {
            val p = sheet.plan
            Text(
                p.items.size.toString() + " video (beserta poster, info, dan subtitle) dipindah ke " + p.channels + " folder kanal di \"" + p.dir.name + "\".",
                color = Win7.Ink, fontSize = 13.sp
            )
            val skipped = p.noInfo + p.conflicts
            if (skipped > 0) {
                Spacer(Modifier.height(6.dp))
                Text("Dilewati: " + p.noInfo + " tanpa info kanal, " + p.conflicts + " nama bentrok.", color = Win7.TextDim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("Tidak menimpa berkas apa pun. Bisa diurungkan setelahnya; penanda \"dilanjutkan\" ikut pindah.", color = Win7.TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Win7Button("Rapikan", isDefault = true) { onClose(); ops.tidy(p) }
                Spacer(Modifier.width(8.dp))
                Win7Button("Batal", onClick = onClose)
            }
        }
        is TvSheet.Dest -> W7Panel(if (sheet.move) "Pindah ke" else "Salin ke", onClose) {
            val options = remember(sheet) { TvGuide.destinations(channels, sheet.file, sheet.move) }
            if (options.isEmpty()) {
                Text("Tidak ada folder tujuan lain.", color = Win7.TextDim, fontSize = 13.sp)
            } else {
                Text("\"" + sheet.file.name + "\"", color = Win7.TextDim, fontSize = 12.sp, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                options.forEach { ch ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onClose()
                            if (sheet.move) ops.move(sheet.file, ch.dir) else ops.copy(sheet.file, ch.dir)
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(ch.number.toString(), color = com.molina.studio.ui.MolinaTheme.Gold, fontSize = 13.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(ch.label, color = Win7.Ink, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Win7Button("Batal", onClick = onClose) }
        }
    }
}

@Composable
private fun TvRenameDialog(file: File, onCancel: () -> Unit, onOk: (String) -> Unit) {
    val stemEnd = if (file.isDirectory) file.name.length else file.name.lastIndexOf('.').takeIf { it > 0 } ?: file.name.length
    var value by remember { mutableStateOf(TextFieldValue(file.name, TextRange(0, stemEnd))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    W7Panel("Ganti nama", onCancel) {
        OutlinedTextField(
            value = value, onValueChange = { value = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Win7Button("Ubah", isDefault = true) { onOk(value.text) }
            Spacer(Modifier.width(8.dp))
            Win7Button("Batal", onClick = onCancel)
        }
    }
}

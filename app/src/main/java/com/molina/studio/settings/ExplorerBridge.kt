package com.molina.studio.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import com.molina.studio.terminal.TerminalActions
import com.molina.studio.terminal.TerminalPrefs
import com.molina.studio.terminal.TerminalSessions
import com.molina.cinema.MpvFolder
import com.molina.code.run.ScriptShare
import com.molina.studio.cinema.CinemaActions
import com.molina.studio.code.CodeNav
import com.molina.studio.code.ScriptNav
import com.molina.studio.code.ScriptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Jembatan Penjelajah Molina: path versi Debian, salin ke clipboard, kirim ke terminal. */
internal object MolinaExplorer {
    /** [START-DIR] true: ketuk Terminal di folder yang sudah punya sesi aktif memilih sesi itu, bukan membuat baru. */
    private const val REUSE_SESSION_BY_START_DIR = true

    private const val SDCARD = "/sdcard"

    private fun base(): String = Environment.getExternalStorageDirectory().absolutePath

    /** Lokasi rootfs Debian di sisi Android; diisi saat Penjelajah dibuka. */
    @Volatile private var rootfsPath: String? = null

    fun attach(ctx: Context) {
        rootfsPath = com.molina.studio.terminal.RootfsManager.rootfsDir(ctx).absolutePath
    }

    /** Path di dalam Debian PRoot; penyimpanan internal di-bind ke /sdcard. */
    fun debianPath(f: File): String {
        val b = base()
        val p = f.absolutePath
        rootfsPath?.let { r ->
            if (p == r) return "/"
            if (p.startsWith("$r/")) return p.substring(r.length)
        }
        return when {
            p == b -> SDCARD
            p.startsWith("$b/") -> SDCARD + p.substring(b.length)
            else -> p
        }
    }

    /**
     * Terlihat di Debian: seluruh penyimpanan internal bila "Akses penyimpanan penuh" aktif,
     * selain itu hanya folder kerja dan folder aplikasi Molina (sesuai bind PRoot). Kartu SD/USB tidak pernah terlihat.
     */
    fun visibleInDebian(f: File, forNewSession: Boolean = false): Boolean {
        // Bind PRoot tetap selama sesi hidup: ikuti sesi aktif, bukan hanya setelan terbaru
        val full = if (forNewSession) TerminalPrefs.fullStorageAccess
            else TerminalSessions.fullAccessOf(TerminalSessions.currentSession) ?: TerminalPrefs.fullStorageAccess
        val roots = if (full) listOf(base()) else listOf(
            base() + "/" + TerminalSessions.WORKSPACE_DIR,
            base() + "/" + com.molina.core.common.MolinaPaths.APP_FOLDER,
        )
        val p = f.absolutePath
        if (rootfsPath?.let { r -> p == r || p.startsWith("$r/") } == true) return true
        return roots.any { p == it || p.startsWith("$it/") }
    }

    fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    /**
     * Putar di Molina Player (mpv): satu berkas, beberapa berkas (playlist memory://), atau satu folder
     * (playlist folder). Pemindaian folder berjalan di IO; peluncuran activity di thread pemanggil.
     */
    suspend fun play(ctx: Context, files: List<File>): Boolean {
        val dir = files.singleOrNull()?.takeIf { it.isDirectory }
        return when {
            dir != null -> {
                val first = withContext(Dispatchers.IO) { MpvFolder.firstMediaIn(dir) } ?: return false
                CinemaActions.playFolder(ctx, first)
            }
            files.size == 1 -> CinemaActions.playPath(ctx, files[0].absolutePath)
            files.isNotEmpty() -> {
                val uri = withContext(Dispatchers.IO) { MpvFolder.memoryPlaylist(files.map { it.absolutePath }) } ?: return false
                CinemaActions.playTree(ctx, uri)
            }
            else -> false
        }
    }

    /** Buka berkas teks di editor Code (tab Code): dikonsumsi MainActivity lewat [CodeNav.pendingFiles]. */
    fun openInCode(ctx: Context, files: List<File>) {
        val ok = files.filter { it.isFile }.take(10)
        if (ok.isEmpty()) {
            Toast.makeText(ctx, "Pilih berkas teks untuk dibuka di Code.", Toast.LENGTH_SHORT).show()
            return
        }
        CodeNav.pendingFiles = ok
    }

    /** Kirim isi berkas teks ke Script execution. Sumber = path berkas, sama dengan kiriman dari editor Code (tidak menggandakan). */
    fun sendToScripts(f: File): Pair<Boolean, String> {
        if (!f.isFile) return Pair(false, "Pilih satu berkas teks")
        if (f.length() > 1_000_000L) return Pair(false, "Berkas terlalu besar untuk Script execution")
        val text = try {
            f.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return Pair(false, "Gagal membaca " + f.name)
        }
        if (text.indexOf('\u0000') >= 0) return Pair(false, "Berkas biner tidak bisa dijadikan skrip")
        val r = ScriptStore.upsertFromEditor(ScriptShare(f.name, f.path, text))
        return Pair(r.ok, r.message)
    }

    fun copyPath(ctx: Context, f: File) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("molina-path", debianPath(f)))
        Toast.makeText(ctx, "Path disalin: " + debianPath(f), Toast.LENGTH_SHORT).show()
    }

    /**
     * [NANO-EDIT] Edit berkas teks di nano pada sesi terminal aktif, lalu pindah ke tab Terminal.
     * Menolak berkas non-teks, lokasi yang tak terlihat di Debian, dan terminal yang sedang menjalankan program layar penuh.
     */
    fun editInTerminal(ctx: Context, f: File): Boolean {
        if (!com.molina.studio.cinema.ExplorerList.looksText(f)) {
            Toast.makeText(ctx, "Hanya berkas teks (maks 5 MB) yang bisa diedit di nano.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!visibleInDebian(f)) {
            Toast.makeText(ctx, "Berkas ini tidak terlihat di Debian. Aktifkan \"Akses penyimpanan penuh\" di Setelan Terminal, atau pindahkan ke folder Molina.", Toast.LENGTH_LONG).show()
            return false
        }
        val path = debianPath(f)
        if ('\n' in path || '\r' in path) {
            Toast.makeText(ctx, "Nama berkas mengandung baris baru; buka lewat terminal.", Toast.LENGTH_LONG).show()
            return false
        }
        return launchNano(ctx, path)
    }

    /** [NANO-NEW] Jalankan nano pada [path] (path Debian) di sesi aktif, lalu pindah ke tab Terminal. */
    private fun launchNano(ctx: Context, path: String): Boolean {
        val em = TerminalSessions.currentSession?.emulator
        if (em == null) {
            Toast.makeText(ctx, "Belum ada sesi terminal aktif. Buka Terminal terlebih dahulu.", Toast.LENGTH_LONG).show()
            return false
        }
        if (em.isAlternateBufferActive) {
            Toast.makeText(ctx, "Terminal sedang menjalankan program layar penuh. Keluar dulu, atau buka sesi baru.", Toast.LENGTH_LONG).show()
            return false
        }
        TerminalActions.send("nano " + quote(path) + "\r")
        ScriptNav.openTerminal = true
        return true
    }

    /**
     * [NANO-NEW] Berkas teks baru: buka nano pada [name] di folder [dir]. Nama divalidasi di sini. Berkas baru dibuat nano saat
     * disimpan, jadi keluar tanpa menyimpan tidak meninggalkan berkas kosong. Berkas yang sudah ada dibuka seperti "Edit".
     */
    fun newInTerminal(ctx: Context, dir: File, name: String): Boolean {
        val n = name.trim()
        val bad = when {
            n.isEmpty() -> "Nama berkas kosong."
            n == "." || n == ".." -> "Nama berkas tidak valid."
            n.any { it == '/' || it == '\\' || it.code < 32 || it.code == 127 } -> "Nama berkas tidak boleh memuat / \\ atau karakter kontrol."
            n.toByteArray(Charsets.UTF_8).size > 200 -> "Nama berkas terlalu panjang."
            else -> null
        }
        if (bad != null) {
            Toast.makeText(ctx, bad, Toast.LENGTH_SHORT).show()
            return false
        }
        if (!dir.isDirectory) {
            Toast.makeText(ctx, "Folder tidak ditemukan.", Toast.LENGTH_SHORT).show()
            return false
        }
        val f = File(dir, n)
        if (f.isDirectory) {
            Toast.makeText(ctx, "Sudah ada folder bernama \"" + n + "\". Pakai nama lain.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (f.isFile) return editInTerminal(ctx, f)
        if (!dir.canWrite()) {
            Toast.makeText(ctx, "Folder ini tidak bisa ditulis.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!visibleInDebian(dir)) {
            Toast.makeText(ctx, "Folder ini tidak terlihat di Debian. Aktifkan \"Akses penyimpanan penuh\" di Setelan Terminal, atau pilih folder Molina.", Toast.LENGTH_LONG).show()
            return false
        }
        return launchNano(ctx, debianPath(f))
    }

    /** Sisipkan path ter-quote (satu atau banyak, dipisah spasi) ke input shell sesi aktif, tanpa Enter. */
    fun insertPaths(ctx: Context, files: List<File>) {
        if (files.isEmpty()) return
        val em = TerminalSessions.currentSession?.emulator
        if (em == null) {
            Toast.makeText(ctx, "Belum ada sesi terminal aktif. Buka Terminal terlebih dahulu.", Toast.LENGTH_LONG).show()
            return
        }
        em.paste(files.joinToString(" ") { quote(debianPath(it)) } + " ")
        val note = if (files.all { visibleInDebian(it) }) "Path dikirim ke Terminal" else "Path dikirim, tetapi lokasi ini tidak terlihat di Debian"
        Toast.makeText(ctx, note, Toast.LENGTH_SHORT).show()
    }

    /**
     * Buka Terminal di folder [f] (folder induk bila berkas): sesi Debian baru dengan cwd di sana,
     * lalu minta MainActivity pindah ke tab Terminal. Bila batas sesi penuh, `cd` di sesi aktif.
     * Mengembalikan true bila Terminal ditampilkan (pemanggil boleh menutup Penjelajah).
     */
    fun openTerminalHere(ctx: Context, f: File): Boolean {
        val dir = (if (f.isDirectory) f else f.parentFile)
        if (dir == null || !dir.isDirectory) {
            Toast.makeText(ctx, "Folder tidak ditemukan.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!visibleInDebian(dir, forNewSession = true)) {
            Toast.makeText(
                ctx,
                "Folder ini tidak terlihat di Debian. Aktifkan \"Akses penyimpanan penuh\" di Setelan Terminal, atau pindahkan ke folder Molina.",
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        val target = debianPath(dir)
        val created = try { TerminalSessions.newSession(ctx, target, target.substringAfterLast('/').ifEmpty { "sdcard" }) } catch (e: Exception) { null }
        if (created != null) {
            Toast.makeText(ctx, "Terminal dibuka di " + target, Toast.LENGTH_SHORT).show()
        } else if (TerminalSessions.currentSession?.emulator != null && visibleInDebian(dir)) {
            TerminalActions.send("cd " + quote(target) + "\r")
            Toast.makeText(ctx, "Batas sesi penuh: cd di sesi aktif ke " + target, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(ctx, "Batas sesi penuh (" + TerminalSessions.MAX_SESSIONS + "). Tutup salah satu sesi terlebih dahulu.", Toast.LENGTH_LONG).show()
            return false
        }
        ScriptNav.openTerminal = true
        return true
    }

    /**
     * [TERMINAL-CD] Ketuk Terminal di dock Penjelajah: langsung berada di folder tujuan tanpa perintah `cd`
     * yang tampil. Bila sudah ada sesi hidup yang dibuka di folder yang sama, sesi itu yang dipilih (tidak
     * menumpuk sesi); bila tidak, buka sesi baru dengan `--cwd` PRoot lewat [openTerminalHere]. Bila batas
     * sesi penuh, [openTerminalHere] jatuh ke `cd` di sesi aktif sebagai cadangan.
     */
    fun cdHere(ctx: Context, f: File): Boolean {
        val dir = (if (f.isDirectory) f else f.parentFile)
        if (dir != null && dir.isDirectory && REUSE_SESSION_BY_START_DIR && visibleInDebian(dir, forNewSession = true)) {
            val target = debianPath(dir)
            val idx = TerminalSessions.indexOfStartDir(target)
            if (idx >= 0) {
                TerminalSessions.select(idx)
                Toast.makeText(ctx, "Terminal dibuka di " + target, Toast.LENGTH_SHORT).show()
                ScriptNav.openTerminal = true
                return true
            }
        }
        return openTerminalHere(ctx, f)
    }

    /** [SHARE-GUARD] Kunci tanda tangan dan kredensial tidak boleh keluar dari Molina lewat FileProvider. */
    private fun blockedSensitive(ctx: Context, files: List<File>): Boolean {
        val bad = files.any { f ->
            com.molina.core.common.SensitivePaths.isSensitive(f.absolutePath) ||
                (try { com.molina.core.common.SensitivePaths.isSensitive(f.canonicalPath) } catch (e: Exception) { false })
        }
        if (bad) Toast.makeText(ctx, "Berkas kunci atau kredensial tidak boleh dibagikan keluar Molina.", Toast.LENGTH_LONG).show()
        return bad
    }

    /** Buka berkas dengan aplikasi lain lewat chooser sistem (FileProvider, izin baca sementara). */
    fun openWith(ctx: Context, f: File): Boolean {
        if (blockedSensitive(ctx, listOf(f))) return false
        if (!f.isFile || !f.canRead()) {
            Toast.makeText(ctx, "Berkas tidak bisa dibaca.", Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Buka dengan")
            if (ctx !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(ctx, "Tidak ada aplikasi yang bisa membuka jenis berkas ini.", Toast.LENGTH_LONG).show()
            false
        } catch (e: IllegalArgumentException) {
            Toast.makeText(ctx, "Lokasi berkas ini tidak bisa dibuka dengan aplikasi lain.", Toast.LENGTH_LONG).show()
            false
        } catch (e: Exception) {
            Toast.makeText(ctx, "Gagal membuka: " + (e.message ?: "tidak diketahui"), Toast.LENGTH_LONG).show()
            false
        }
    }

    /** Bagikan berkas (bukan folder) lewat share sheet Android memakai FileProvider. */
    fun share(ctx: Context, files: List<File>): Boolean {
        if (blockedSensitive(ctx, files)) return false
        val fs = files.filter { it.isFile && it.canRead() }.take(50)
        if (fs.isEmpty()) {
            Toast.makeText(ctx, "Pilih berkas (bukan folder) untuk dibagikan.", Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val auth = ctx.packageName + ".fileprovider"
            val uris = ArrayList(fs.map { androidx.core.content.FileProvider.getUriForFile(ctx, auth, it) })
            val intent = if (uris.size == 1) {
                val mime = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(fs[0].extension.lowercase()) ?: "application/octet-stream"
                Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uris[0]) }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(intent, "Bagikan")
            if (ctx !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
            true
        } catch (e: IllegalArgumentException) {
            Toast.makeText(ctx, "Lokasi berkas ini tidak bisa dibagikan (di luar penyimpanan internal).", Toast.LENGTH_LONG).show()
            false
        } catch (e: Exception) {
            Toast.makeText(ctx, "Gagal membagikan: " + (e.message ?: "tidak diketahui"), Toast.LENGTH_LONG).show()
            false
        }
    }

    /** Jalankan `cd` ke folder di sesi aktif (langsung dieksekusi). Hanya untuk folder yang terlihat di Debian. */
    fun changeDir(ctx: Context, dir: File) {
        if (TerminalSessions.currentSession?.emulator == null) {
            Toast.makeText(ctx, "Belum ada sesi terminal aktif. Buka Terminal terlebih dahulu.", Toast.LENGTH_LONG).show()
            return
        }
        if (!dir.isDirectory) {
            Toast.makeText(ctx, "Pilih sebuah folder untuk cd.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!visibleInDebian(dir)) {
            Toast.makeText(
                ctx,
                if (TerminalPrefs.fullStorageAccess && TerminalSessions.fullAccessOf(TerminalSessions.currentSession) == false)
                    "Sesi terminal ini dibuat sebelum Akses penyimpanan penuh aktif. Buka sesi baru (NEW SESSION)."
                else
                    "Folder ini tidak terlihat di Debian. Aktifkan \"Akses penyimpanan penuh\" di Setelan Terminal (berlaku di sesi baru), atau pindahkan ke folder Molina.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        TerminalActions.send("cd " + quote(debianPath(dir)) + "\r")
        Toast.makeText(ctx, "cd " + debianPath(dir), Toast.LENGTH_SHORT).show()
    }
}

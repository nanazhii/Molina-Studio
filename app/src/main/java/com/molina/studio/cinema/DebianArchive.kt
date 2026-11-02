package com.molina.studio.cinema

import android.content.Context
import com.molina.core.common.AppLog
import com.molina.core.common.LogLevel
import com.molina.core.common.SevenZipCommand
import com.molina.core.common.YtdlpCommand
import com.molina.studio.system.AndroidProcessOps
import com.molina.studio.terminal.RootfsManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [ARCHIVE4] Membuka arsip yang tidak dikuasai [ArchiveEngine] (RAR, ISO, CAB, multi-bagian, ...) lewat `7z` di Debian
 * PRoot milik Molina Terminal. Blocking: panggil dari Dispatchers.IO. Tidak menyentuh UI/Compose.
 *
 * Aturan:
 * - HANYA dua folder yang terlihat oleh 7z: folder arsip (-> [SevenZipCommand.GUEST_SRC]) dan folder staging
 *   (-> [SevenZipCommand.GUEST_OUT]). Lingkup akses penyimpanan Terminal tidak dilonggarkan.
 * - Hasil ditulis ke staging `.molina-stage-*` di folder tujuan, diaudit, lalu dipindah ([DebianArchiveStaging]).
 *   Gagal/batal = tujuan tidak berubah. Berkas yang ada tidak ditimpa.
 * - Sandi hanya lewat stdin, tidak pernah di argumen proses atau log.
 * - Pembatalan mematikan seluruh pohon proses (proot + 7z).
 * - 7z keluar 0 walau nama yang diminta tidak ada (nol berkas); itu dianggap galat di sini.
 * - [ARCHIVE6] [create] membuat 7z BERSANDI (isi saja, atau isi + nama berkas dengan -mhe=on). Folder sumber di-bind per
 *   folder induk ([SevenZipCommand.GUEST_IN]/0..n) dan staging keluaran ke [SevenZipCommand.GUEST_OUT]. Hasil hanya muncul
 *   di folder tujuan bila 7z keluar 0 (exit 1 = sumber tak terbaca = gagal, arsip dibuang); tidak pernah menimpa berkas.
 *
 * Catatan: logika proses disalin dari ProotScriptExecutor.newProotProcess (lingkungan bersih proot). Refactor menjadi
 * satu pembangun proses bersama sengaja ditunda agar jalur Code/Terminal yang sudah stabil tidak tersentuh.
 */
internal class DebianArchive(context: Context) {
    private val app = context.applicationContext

    sealed class Probe {
        class Ready(val binary: String) : Probe()
        class Missing(val hint: String) : Probe()
    }

    /** Siap bila Debian terpasang dan `7z` ada di dalamnya. Murah: hanya memeriksa berkas. */
    fun probe(): Probe {
        val rootfs = RootfsManager.rootfsDir(app)
        if (!File(rootfs, "usr/bin/env").exists()) {
            return Probe.Missing("Debian belum terpasang. Buka Settings > Terminal untuk menyiapkannya.")
        }
        val bin = SevenZipCommand.GUEST_BINARIES.firstOrNull { guestHas(rootfs, it) }
            ?: return Probe.Missing(SevenZipCommand.HINT_INSTALL)
        return Probe.Ready(bin)
    }

    private fun guestHas(rootfs: File, guestPath: String): Boolean {
        val f = File(rootfs, guestPath.removePrefix("/"))
        return f.exists() || Files.isSymbolicLink(f.toPath())
    }

    // ------------------------------------------------------------------ daftar isi

    /**
     * Daftar isi tanpa mengekstrak. Bentuk hasil sama dengan [ArchiveEngine.list] supaya dialog bisa memakainya.
     * Tautan dilewati (seperti tar). Arsip dengan nama terenkripsi tanpa [password] melempar [FileOps.NeedPassword].
     */
    fun list(file: File, password: String? = null, cancelled: () -> Boolean = { false }): FileOps.ZipListing {
        DebianFormats.detect(file.name)?.let { if (!it.firstVolume) throw IOException(DebianFormats.NOT_FIRST_HINT) }
        val bin = readyBinary()
        val srcDir = sourceDir(file)
        val rootfs = RootfsManager.rootfsDir(app)
        ensureMountPoints(rootfs)

        val items = ArrayList<FileOps.ZipEntryInfo>()
        var files = 0
        var folders = 0
        var total = 0L
        var enc = false
        var overflow = false
        val args = try {
            SevenZipCommand.listArgs(SevenZipCommand.GUEST_SRC + "/" + file.name)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message ?: "Jalur tidak valid")
        }
        val out = run(
            rootfs, listOf(srcDir.path to SevenZipCommand.GUEST_SRC), bin, args,
            SevenZipCommand.stdinFor(password), cancelled, LIST_TIMEOUT_MS,
        ) { reader ->
            SevenZipCommand.parseListStream(java.io.BufferedReader(reader), LIST_MAX_ENTRIES) { e ->
                if (e.isLink) return@parseListStream
                if (e.encrypted) enc = true
                if (e.isDir) folders++ else { files++; if (e.size > 0) total += e.size }
                if (items.size < FileOps.ZIP_LIST_MAX) {
                    items.add(FileOps.ZipEntryInfo(if (e.isDir) e.path.trimEnd('/') + "/" else e.path, e.size, e.isDir))
                } else overflow = true
            }
        }
        if (!out.stoppedEarly) {
            val ok = out.exit == 0 || (out.exit == 1 && (files + folders) > 0)
            if (!ok) throw fail(SevenZipCommand.classify(out.exit, out.output, !password.isNullOrEmpty()))
        }
        return FileOps.ZipListing(items, files, folders, total, enc, overflow || out.stoppedEarly)
    }

    // ------------------------------------------------------------------ ekstrak

    /**
     * Ekstrak [file] ke [destDir] ([ExtractTarget.HERE] atau subfolder bernama arsip). [only] = nama entri terpilih
     * (folder berakhiran "/"), null = semua. [expectedBytes] > 0 mengaktifkan pemeriksaan ruang kosong di awal.
     */
    fun extract(
        file: File, destDir: File, target: ExtractTarget, ctl: FileOps.Ctl,
        password: String? = null, only: Set<String>? = null, expectedBytes: Long = 0L,
    ): ExtractResult {
        val info = DebianFormats.detect(file.name) ?: throw IOException("Format arsip tidak didukung lewat Debian: " + file.name)
        if (!info.firstVolume) throw IOException(DebianFormats.NOT_FIRST_HINT)
        if (!destDir.isDirectory || !destDir.canWrite()) throw IOException("Folder tujuan tidak bisa ditulisi")
        val bin = readyBinary()
        val srcDir = sourceDir(file)
        val rootfs = RootfsManager.rootfsDir(app)
        DebianArchiveStaging.roomError(destDir, expectedBytes)?.let { throw IOException(it) }
        ensureMountPoints(rootfs)
        DebianArchiveStaging.sweepStale(destDir)

        val stage = DebianArchiveStaging.create(destDir)
        try {
            if (!SevenZipCommand.bindHostOk(stage.canonicalPath)) throw IOException("Jalur tujuan memuat karakter yang tidak didukung")
            val guestArchive = SevenZipCommand.GUEST_SRC + "/" + file.name
            val args = try {
                if (only.isNullOrEmpty()) SevenZipCommand.extractArgs(guestArchive, SevenZipCommand.GUEST_OUT)
                else SevenZipCommand.extractArgs(guestArchive, SevenZipCommand.GUEST_OUT, only.toList())
            } catch (e: IllegalArgumentException) {
                throw IOException(e.message ?: "Argumen tidak valid")
            }

            ctl.total = 100L // progres 7z (-bsp1) berupa persen; Ctl melaporkannya sebagai persen penuh
            var last = 0
            val started = System.nanoTime()
            val out = run(
                rootfs,
                listOf(srcDir.path to SevenZipCommand.GUEST_SRC, stage.canonicalPath to SevenZipCommand.GUEST_OUT),
                bin, args, SevenZipCommand.stdinFor(password), { ctl.cancelled() }, 0L,
            ) { reader ->
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    val chunk = String(buf, 0, n)
                    SevenZipCommand.parseProgress(chunk)?.let { p ->
                        if (p > last) { ctl.add((p - last).toLong()); last = p }
                    }
                }
                false
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            val pw = !password.isNullOrEmpty()
            if (out.exit != 0 && out.exit != 1) throw fail(SevenZipCommand.classify(out.exit, out.output, pw))
            if (ctl.cancelled()) throw IOException("Dibatalkan")
            if (last < 100) ctl.add((100 - last).toLong()) // 7z sering berhenti mencetak di 98-99%

            val audit = DebianArchiveStaging.audit(stage)
            if (out.exit == 1 && audit.files == 0 && audit.dirs == 0) throw fail(SevenZipCommand.classify(out.exit, out.output, pw))
            if (!only.isNullOrEmpty() && audit.files == 0) throw IOException("Tidak ada entri terpilih yang cocok di arsip")
            AppLog.log(
                "archive", LogLevel.Info, "7z",
                "ekstrak selesai ${ms}ms exit=${out.exit} berkas=${audit.files} folder=${audit.dirs} tautan_dibuang=${audit.removed}" +
                    (if (out.exit == 1) " (peringatan)" else ""),
            )

            val named = if (target == ExtractTarget.NAMED_FOLDER) DebianFormats.folderName(file.name) else null
            val c = DebianArchiveStaging.commit(stage, destDir, named) { ctl.cancelled() }
            return ExtractResult(c.base, c.files, c.renamed)
        } finally {
            DebianArchiveStaging.deleteTree(stage)
        }
    }

    // ------------------------------------------------------------------ kompres 7z bersandi [ARCHIVE6]

    /**
     * Buat arsip 7z bersandi dari [files] di [destDir]; nama dari [CompressOptions.baseName] + ".7z" (bentrok = " (1)").
     * [CompressOptions.encryptNames] true = `-mhe=on` (daftar isi pun butuh sandi). Sandi lewat stdin, tidak pernah di argumen/log.
     * Semua tautan simbolik dilewati (seperti [ArchiveEngine]): yang tingkat atas disaring di sini, yang di dalam folder lewat `-x!`.
     * Blocking: panggil dari Dispatchers.IO. Gagal/batal = folder tujuan tidak berubah.
     */
    fun create(files: List<File>, destDir: File, opt: CompressOptions, ctl: FileOps.Ctl): File {
        val fmt = CompressFormat.SEVEN_Z
        if (opt.format != fmt) throw IOException("Hanya 7z yang dibuat lewat Debian")
        val pw = opt.password?.takeIf { it.isNotEmpty() } ?: throw IOException("Kata sandi wajib untuk 7z lewat Debian")
        val stdin = try { SevenZipCommand.stdinFor(pw) } catch (e: IllegalArgumentException) { throw IOException(e.message ?: "Kata sandi tidak valid") }
        if (files.isEmpty()) throw IOException("Tidak ada yang dikompres")
        for (f in files) if (!f.exists() && !isLink(f)) throw IOException("Sumber tidak ditemukan: " + f.name)
        if (!destDir.isDirectory || !destDir.canWrite()) throw IOException("Folder tujuan tidak bisa ditulisi")
        files.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }?.let {
            throw IOException("Nama bentrok di dalam arsip: \"" + it.key + "\" ada di lebih dari satu folder. Kompres terpisah atau ubah namanya.")
        }
        CompressNames.baseNameError(opt.baseName, fmt)?.let { throw IOException(it) }
        val sources = files.filter { !isLink(it) }
        if (sources.isEmpty()) throw IOException("Tidak ada berkas yang bisa dikompres (tautan simbolik dilewati)")

        // Folder induk berbeda -> satu bind per folder (/mnt/molina-in/<i>); jalur di arsip tetap hanya nama item.
        val parents = LinkedHashMap<String, Int>()
        val guestSources = ArrayList<String>()
        for (f in sources) {
            val parent = (f.absoluteFile.parentFile ?: throw IOException("Folder sumber tidak ditemukan")).canonicalFile
            if (!SevenZipCommand.bindHostOk(parent.path)) throw IOException("Jalur sumber memuat karakter yang tidak didukung")
            val idx = parents.getOrPut(parent.path) { parents.size }
            guestSources.add(SevenZipCommand.GUEST_IN + "/" + idx + "/" + f.name)
        }
        if (parents.size > SevenZipCommand.MAX_SOURCE_DIRS) throw IOException("Sumber berasal dari terlalu banyak folder berbeda")
        val destCanon = destDir.canonicalFile
        for (f in sources) {
            if (!f.isDirectory) continue
            val c = f.canonicalFile
            if (destCanon.path == c.path || destCanon.path.startsWith(c.path + File.separator)) {
                throw IOException("Folder tujuan berada di dalam folder yang dikompres. Pilih folder tujuan lain.")
            }
        }

        val links = DebianArchiveStaging.symlinkExcludes(sources) { ctl.cancelled() }
        val bin = readyBinary()
        val rootfs = RootfsManager.rootfsDir(app)
        DebianArchiveStaging.roomError(destDir, 0L)?.let { throw IOException(it) }
        ensureCreateMountPoints(rootfs, parents.size)
        DebianArchiveStaging.sweepStale(destDir)

        val stage = DebianArchiveStaging.create(destDir)
        try {
            if (!SevenZipCommand.bindHostOk(stage.canonicalPath)) throw IOException("Jalur tujuan memuat karakter yang tidak didukung")
            val level = fmt.clampLevel(opt.level)
            val args = try {
                SevenZipCommand.createArgs(
                    SevenZipCommand.GUEST_OUT + "/" + SevenZipCommand.OUT_FILE, guestSources, level,
                    hasPassword = true, encryptNames = opt.encryptNames, excludes = links,
                )
            } catch (e: IllegalArgumentException) {
                throw IOException(e.message ?: "Argumen tidak valid")
            }
            val binds = parents.entries.map { it.key to (SevenZipCommand.GUEST_IN + "/" + it.value) } +
                (stage.canonicalPath to SevenZipCommand.GUEST_OUT)

            ctl.total = 100L // progres 7z (-bsp1) berupa persen
            var last = 0
            val started = System.nanoTime()
            val out = run(rootfs, binds, bin, args, stdin, { ctl.cancelled() }, 0L) { reader ->
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    SevenZipCommand.parseProgress(String(buf, 0, n))?.let { p ->
                        if (p > last) { ctl.add((p - last).toLong()); last = p }
                    }
                }
                false
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            if (out.exit != 0) throw fail(SevenZipCommand.classifyCreate(out.exit, out.output, true))
            if (ctl.cancelled()) throw IOException("Dibatalkan")
            if (last < 100) ctl.add((100 - last).toLong())

            val made = DebianArchiveStaging.commitFile(
                stage, SevenZipCommand.OUT_FILE, destDir, CompressNames.cleanBaseName(opt.baseName, fmt), fmt.ext,
            ) { ctl.cancelled() }
            AppLog.log(
                "archive", LogLevel.Info, "7z",
                "kompres selesai ${ms}ms sumber=${sources.size} tautan_dilewati=${links.size} level=$level nama_terenkripsi=${opt.encryptNames} ukuran=${made.length()}",
            )
            return made
        } finally {
            DebianArchiveStaging.deleteTree(stage)
        }
    }

    // ------------------------------------------------------------------ infrastruktur proses

    private fun readyBinary(): String = when (val p = probe()) {
        is Probe.Ready -> p.binary
        is Probe.Missing -> throw IOException(p.hint)
    }

    private fun sourceDir(file: File): File {
        if (!file.isFile) throw IOException("Berkas arsip tidak ditemukan")
        if (file.length() == 0L) throw IOException("Berkas arsip kosong (0 byte)")
        val dir = file.canonicalFile.parentFile ?: throw IOException("Folder arsip tidak ditemukan")
        if (!SevenZipCommand.bindHostOk(dir.path)) throw IOException("Jalur arsip memuat karakter yang tidak didukung")
        return dir
    }

    /** Titik mount harus ada di rootfs sebelum proot dijalankan (sama seperti StorageBinds). */
    private fun ensureMountPoints(rootfs: File) {
        for (g in listOf(SevenZipCommand.GUEST_SRC, SevenZipCommand.GUEST_OUT)) File(rootfs, g.removePrefix("/")).mkdirs()
    }

    /** [ARCHIVE6] Titik mount kompres: satu `/mnt/molina-in/<i>` per folder sumber, plus [SevenZipCommand.GUEST_OUT]. */
    private fun ensureCreateMountPoints(rootfs: File, sourceDirs: Int) {
        File(rootfs, SevenZipCommand.GUEST_OUT.removePrefix("/")).mkdirs()
        for (i in 0 until sourceDirs) File(rootfs, (SevenZipCommand.GUEST_IN + "/" + i).removePrefix("/")).mkdirs()
    }

    private fun isLink(f: File): Boolean = try { Files.isSymbolicLink(f.toPath()) } catch (e: Exception) { false }

    private fun fail(f: SevenZipCommand.Failure): IOException = when (f) {
        SevenZipCommand.Failure.NEED_PASSWORD -> FileOps.NeedPassword()
        SevenZipCommand.Failure.WRONG_PASSWORD -> FileOps.WrongPassword()
        SevenZipCommand.Failure.CANCELLED -> IOException("Dibatalkan")
        else -> IOException(f.hint)
    }

    private class Outcome(val exit: Int, val output: String, val stoppedEarly: Boolean)

    /** Ekor keluaran terbatas (cukup untuk klasifikasi galat; tidak menampung seluruh keluaran). */
    private class Tail(private val max: Int) {
        private val sb = StringBuilder()
        @Synchronized fun add(s: String) { sb.append(s); if (sb.length > max) sb.delete(0, sb.length - max) }
        @Synchronized override fun toString(): String = sb.toString()
    }

    /** Meneruskan bacaan ke [consume] sambil menyalin ke [tail], supaya prompt/galat 7z tetap bisa diklasifikasi. */
    private class TeeReader(private val inner: Reader, private val tail: Tail) : Reader() {
        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            val n = inner.read(cbuf, off, len)
            if (n > 0) tail.add(String(cbuf, off, n))
            return n
        }
        override fun close() = inner.close()
    }

    private fun readPidLine(i: InputStream): Int {
        val sb = StringBuilder()
        while (sb.length < 24) {
            val b = i.read()
            if (b < 0 || b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.toString().trim().toIntOrNull() ?: -1
    }

    /**
     * Jalankan `7z` di proot. Baris pertama stdout adalah PID (SH_WRAPPER). [consume] membaca sisa stdout dan
     * mengembalikan true bila proses boleh dihentikan lebih awal (mis. daftar terpotong). Bacaan disalin ke ekor keluaran. [timeoutMs] 0 = tanpa batas.
     */
    private fun run(
        rootfsDir: File,
        binds: List<Pair<String, String>>,
        binary: String,
        args: List<String>,
        stdin: ByteArray?,
        cancelled: () -> Boolean,
        timeoutMs: Long,
        consume: (Reader) -> Boolean,
    ): Outcome {
        val argv = try {
            SevenZipCommand.prootArgsScoped(rootfsDir.absolutePath, binds, binary, args)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message ?: "Argumen proot tidak valid")
        }
        val nativeDir = app.applicationInfo.nativeLibraryDir
        val tmp = File(app.cacheDir, "proot-tmp").apply { mkdirs() }
        val pb = ProcessBuilder(YtdlpCommand.SH_WRAPPER + "$nativeDir/libproot.so" + argv).directory(app.filesDir)
        pb.environment().apply {
            clear()
            put("PROOT_LOADER", "$nativeDir/libproot_loader.so")
            put("PROOT_TMP_DIR", tmp.absolutePath)
            put("TMPDIR", tmp.absolutePath)
            put("PATH", "/system/bin")
            put("HOME", app.filesDir.absolutePath)
            System.getenv("ANDROID_ROOT")?.let { put("ANDROID_ROOT", it) }
            System.getenv("ANDROID_DATA")?.let { put("ANDROID_DATA", it) }
        }
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            throw IOException("Gagal memulai proot: ${e.javaClass.simpleName}")
        }

        if (stdin == null) {
            try { proc.outputStream.close() } catch (e: Exception) { }
        } else {
            val writer = Thread({
                try { proc.outputStream.use { it.write(stdin) } } catch (e: Exception) { /* proses mati lebih dulu */ }
            }, "molina-7z-in")
            writer.isDaemon = true
            writer.start()
        }

        val pid = AtomicInteger(-1)
        val stopEarly = AtomicBoolean(false)
        val outError = AtomicReference<Exception?>(null)
        val outTail = Tail(TAIL_CHARS)
        val errTail = Tail(TAIL_CHARS)

        val outThread = Thread({
            try {
                val raw = proc.inputStream
                pid.set(readPidLine(raw))
                val reader = TeeReader(InputStreamReader(raw, Charsets.UTF_8), outTail)
                if (consume(reader)) {
                    stopEarly.set(true)
                } else {
                    val buf = CharArray(4096)
                    while (reader.read(buf) >= 0) { /* kuras sisa agar proses tidak buntu */ }
                }
            } catch (e: Exception) {
                outError.set(e)
            }
        }, "molina-7z-out")
        val errThread = Thread({
            try {
                val reader = InputStreamReader(proc.errorStream, Charsets.UTF_8)
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    errTail.add(String(buf, 0, n))
                }
            } catch (e: Exception) { }
        }, "molina-7z-err")
        outThread.isDaemon = true
        errThread.isDaemon = true
        outThread.start()
        errThread.start()

        var finished = false
        var wasCancelled = false
        var timedOut = false
        val deadline = if (timeoutMs > 0) System.nanoTime() + timeoutMs * 1_000_000 else Long.MAX_VALUE
        try {
            while (true) {
                if (proc.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) { finished = true; break }
                if (cancelled()) { wasCancelled = true; break }
                if (stopEarly.get()) break
                if (deadline != Long.MAX_VALUE && System.nanoTime() > deadline) { timedOut = true; break }
            }
        } catch (e: InterruptedException) {
            wasCancelled = true
        } finally {
            if (!finished) {
                val root = pid.get()
                if (root > 1) {
                    try { AndroidProcessOps.reaper().reapTree(root) } catch (e: Exception) { }
                }
                proc.destroyForcibly()
            }
        }
        try {
            outThread.join(1500)
            errThread.join(1500)
        } catch (e: InterruptedException) { }
        Thread.interrupted()

        if (wasCancelled) throw IOException("Dibatalkan")
        if (timedOut) throw IOException("7z melewati batas waktu")
        if (stopEarly.get()) return Outcome(0, "", true)
        outError.get()?.let { throw IOException("Gagal membaca keluaran 7z: ${it.javaClass.simpleName}") }
        val exit = try { proc.exitValue() } catch (e: IllegalThreadStateException) { -1 }
        return Outcome(exit, outTail.toString() + "\n" + errTail.toString(), false)
    }

    private companion object {
        const val POLL_MS = 150L
        const val TAIL_CHARS = 64 * 1024
        const val LIST_MAX_ENTRIES = 200_000
        const val LIST_TIMEOUT_MS = 10L * 60 * 1000
    }
}

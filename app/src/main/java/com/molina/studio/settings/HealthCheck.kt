package com.molina.studio.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.StatFs
import com.molina.core.common.YtdlpCommand
import com.molina.core.common.YtdlpInstall
import com.molina.studio.AppPaths
import com.molina.studio.cinema.MpvVersionBridge
import com.molina.studio.script.ScriptCommand
import com.molina.studio.terminal.RootfsManager
import com.molina.studio.terminal.RootfsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.TimeUnit

/** [DIAG-CHECK] Tingkat hasil pemeriksaan; urutan ordinal dipakai untuk mencari yang terburuk. */
internal enum class CheckLevel(val glyph: String) { Ok("\u2713"), Warn("\u26A0"), Bad("\u2717") }

/** [DIAG-ACTION] Tindakan perbaikan yang bisa ditawarkan di samping hasil bermasalah. */
internal enum class CheckAction { ManageDebian, GrantPermission, ViewMpvLog }

internal class CheckResult(val name: String, val level: CheckLevel, val detail: String, val action: CheckAction? = null)

/**
 * Pemeriksaan nyata (bukan sekadar pengumpulan data): PRoot, rootfs Debian, Debian benar-benar bisa dijalankan,
 * izin dan folder /sdcard/Molina, ruang bebas, libmpv. Tidak mengubah apa pun selain menjalankan satu proses proot
 * ringan (echo) dengan batas waktu. Operasi disk dan proses: panggil di luar main thread.
 *
 * [deep] = false melewati pemeriksaan "Debian jalan" (dipakai lencana halaman Info agar tidak memulai proot tiap kali).
 */
internal object HealthCheck {
    private const val DEBIAN_TIMEOUT_S = 8L
    private const val NET_TIMEOUT_S = 4L
    private const val PTY_TIMEOUT_MS = 3000L
    private const val CINEMA_LOG_WINDOW_MS = 5 * 60 * 1000L
    private const val LOW_FREE_MB = 500L
    private const val CRITICAL_FREE_MB = 100L
    private const val DEEP_TTL_MS = 10 * 60 * 1000L

    private val _last = MutableStateFlow<CheckLevel?>(null)

    /** Kondisi terburuk dari pemeriksaan terakhir; dibaca lencana di halaman Info. */
    val last: StateFlow<CheckLevel?> = _last.asStateFlow()
    @Volatile private var deepAt = 0L
    @Volatile private var deepLevel: CheckLevel? = null

    fun run(ctx: Context, deep: Boolean = true): List<CheckResult> {
        val app = ctx.applicationContext
        val list = buildList {
            add(proot(app))
            add(rootfs(app))
            if (deep) {
                add(debianRuns(app))
                add(terminal())
                add(cinema(app))
                add(cinemaLog())
                decoder(app)?.let { add(it) }
                add(network())
            }
            add(storage(app))
            add(freeSpace(app))
            add(mpv(app))
        }
        publish(worst(list), deep)
        return list
    }

    /** Hasil pemeriksaan mendalam tetap dipakai lencana selama 10 menit, kecuali pemeriksaan cepat lebih buruk. */
    private fun publish(level: CheckLevel, deep: Boolean) {
        val now = System.currentTimeMillis()
        if (deep) {
            deepAt = now
            deepLevel = level
            _last.value = level
            return
        }
        val d = deepLevel
        _last.value = if (d != null && now - deepAt < DEEP_TTL_MS && d.ordinal > level.ordinal) d else level
    }

    fun worst(list: List<CheckResult>): CheckLevel = list.maxByOrNull { it.level.ordinal }?.level ?: CheckLevel.Ok

    fun toText(list: List<CheckResult>): String =
        list.joinToString("\n") { "[${it.level.glyph}] ${it.name.padEnd(12)}: ${it.detail}" }

    private fun missing(dir: String, vararg names: String): List<String> = names.filter { !File(dir, it).isFile }

    private fun proot(ctx: Context): CheckResult {
        val miss = missing(ctx.applicationInfo.nativeLibraryDir, "libproot.so", "libproot_loader.so")
        return if (miss.isEmpty()) CheckResult("PRoot", CheckLevel.Ok, "libproot.so dan loader ada")
        else CheckResult("PRoot", CheckLevel.Bad, "tidak ada: ${miss.joinToString()}")
    }

    private fun rootfs(ctx: Context): CheckResult {
        RootfsManager.refresh(ctx)
        return when (val st = RootfsManager.state.value) {
            RootfsState.Ready ->
                if (File(RootfsManager.rootfsDir(ctx), "usr/bin").isDirectory)
                    CheckResult("Debian", CheckLevel.Ok, "rootfs siap, isi lengkap")
                else
                    CheckResult("Debian", CheckLevel.Bad, "penanda siap, tetapi usr/bin tidak ada; pulihkan atau pasang ulang", CheckAction.ManageDebian)
            RootfsState.Missing -> CheckResult("Debian", CheckLevel.Warn, "belum terpasang", CheckAction.ManageDebian)
            is RootfsState.Failed -> CheckResult("Debian", CheckLevel.Bad, "gagal: ${st.message.take(120)}", CheckAction.ManageDebian)
            else -> CheckResult("Debian", CheckLevel.Warn, "sedang diproses atau dikelola")
        }
    }

    /** Menjalankan `/bin/sh -c "echo molina-ok"` di dalam rootfs lewat proot, dengan argumen yang sama seperti Code/Terminal. */
    private fun debianRuns(ctx: Context): CheckResult {
        val name = "Debian jalan"
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val root = RootfsManager.rootfsDir(ctx)
        if (RootfsManager.state.value != RootfsState.Ready || !File(nativeDir, "libproot.so").isFile) {
            return CheckResult(name, CheckLevel.Warn, "dilewati: Debian atau PRoot belum siap")
        }
        val tmp = File(ctx.cacheDir, "proot-tmp").apply { mkdirs() }
        val argv = ScriptCommand.prootArgv(root.absolutePath, "/", emptyList(), listOf("/bin/sh", "-c", "echo molina-ok"))
        val pb = ProcessBuilder(YtdlpCommand.SH_WRAPPER + "$nativeDir/libproot.so" + argv).directory(ctx.filesDir)
        pb.redirectErrorStream(true)
        pb.environment().apply {
            clear()
            put("PROOT_LOADER", "$nativeDir/libproot_loader.so")
            put("PROOT_TMP_DIR", tmp.absolutePath)
            put("TMPDIR", tmp.absolutePath)
            put("PATH", "/system/bin")
            put("HOME", ctx.filesDir.absolutePath)
            System.getenv("ANDROID_ROOT")?.let { put("ANDROID_ROOT", it) }
            System.getenv("ANDROID_DATA")?.let { put("ANDROID_DATA", it) }
        }
        val t0 = System.nanoTime()
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            return CheckResult(name, CheckLevel.Bad, "gagal memulai proot: ${e.javaClass.simpleName}", CheckAction.ManageDebian)
        }
        try { proc.outputStream.close() } catch (e: Exception) { }
        val out = StringBuilder()
        val reader = Thread({
            try {
                proc.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(512)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(out) { if (out.length < 2000) out.append(buf, 0, n) }
                    }
                }
            } catch (e: Exception) { }
        }, "molina-health-proot")
        reader.isDaemon = true
        reader.start()
        val done = try { proc.waitFor(DEBIAN_TIMEOUT_S, TimeUnit.SECONDS) } catch (e: InterruptedException) { false }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (!done) {
            proc.destroyForcibly()
            return CheckResult(name, CheckLevel.Bad, "proot tidak merespons dalam $DEBIAN_TIMEOUT_S dtk (dihentikan)", CheckAction.ManageDebian)
        }
        reader.join(500)
        val text = synchronized(out) { out.toString() }
        val (_, rest) = YtdlpCommand.splitPidLine(text)
        val code = proc.exitValue()
        return if (code == 0 && rest.contains("molina-ok")) {
            CheckResult(name, CheckLevel.Ok, "proot menjalankan /bin/sh ($ms ms)")
        } else {
            CheckResult(name, CheckLevel.Bad, "keluar kode $code: ${rest.trim().lineSequence().firstOrNull().orEmpty().take(100)}", CheckAction.ManageDebian)
        }
    }

    /** [DIAG-NET] Pemetaan DNS host unduhan yt-dlp (tanpa koneksi data). Gagal hanya memberi peringatan: bisa saja offline. */
    private fun network(): CheckResult {
        val name = "Jaringan"
        val host = runCatching { java.net.URI(YtdlpInstall.BASE).host }.getOrNull() ?: "github.com"
        val ex = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "molina-health-dns").apply { isDaemon = true } }
        val t0 = System.nanoTime()
        return try {
            ex.submit<Boolean> { java.net.InetAddress.getByName(host); true }.get(NET_TIMEOUT_S, TimeUnit.SECONDS)
            CheckResult(name, CheckLevel.Ok, "DNS $host berhasil (${(System.nanoTime() - t0) / 1_000_000} ms)")
        } catch (e: java.util.concurrent.TimeoutException) {
            CheckResult(name, CheckLevel.Warn, "DNS $host lambat (lebih dari $NET_TIMEOUT_S dtk)")
        } catch (e: Exception) {
            CheckResult(name, CheckLevel.Warn, "tidak bisa memetakan $host (offline?)")
        } finally {
            ex.shutdownNow()
        }
    }

    /** [DIAG-PTY] Membuka PTY sesaat lewat libmolina_pty dan menjalankan echo di /system/bin/sh. Gagal berarti Terminal tidak bisa membuka sesi. */
    private fun terminal(): CheckResult {
        val r = com.molina.terminal.PtyProbe.run(PTY_TIMEOUT_MS)
        return CheckResult("Terminal jalan", if (r.ok) CheckLevel.Ok else CheckLevel.Bad, r.detail)
    }

    /**
     * [DIAG-CINEMA] Memuat libmpv dan libplayer lewat jalur yang sama dengan pemutar (init MPVLib), tanpa memanggil
     * create(). Pemuatan hanya terjadi sekali per proses; pemutaran berikutnya memakai pustaka yang sudah termuat.
     */
    private fun cinema(ctx: Context): CheckResult {
        val name = "Cinema siap"
        if (missing(ctx.applicationInfo.nativeLibraryDir, "libmpv.so", "libplayer.so").isNotEmpty()) {
            return CheckResult(name, CheckLevel.Warn, "dilewati: libmpv atau libplayer tidak ada")
        }
        val t0 = System.nanoTime()
        return try {
            Class.forName(com.molina.cinema.MPVLib::class.java.name)
            CheckResult(name, CheckLevel.Ok, "libmpv dan libplayer termuat (${(System.nanoTime() - t0) / 1_000_000} ms)")
        } catch (e: LinkageError) {
            val why = (e.cause ?: e).message?.lineSequence()?.firstOrNull().orEmpty().take(100)
            CheckResult(name, CheckLevel.Bad, "gagal dimuat (${e.javaClass.simpleName}): $why")
        } catch (e: ClassNotFoundException) {
            CheckResult(name, CheckLevel.Bad, "kelas pemutar tidak ditemukan")
        }
    }

    /**
     * [DIAG-CINLOG] Error mpv di log aplikasi (LogBus, hanya sejak proses ini hidup) dalam 5 menit terakhir.
     * Hanya ⚠: error bisa berasal dari stream yang mati, bukan kerusakan aplikasi.
     */
    private fun cinemaLog(): CheckResult {
        val name = "Log Cinema"
        val since = System.currentTimeMillis() - CINEMA_LOG_WINDOW_MS
        val errs = com.molina.studio.log.LogBus.entries.value.filter {
            it.source == "mpv" && it.level == com.molina.core.common.LogLevel.Error && it.time >= since
        }
        if (errs.isEmpty()) return CheckResult(name, CheckLevel.Ok, "tidak ada error mpv dalam 5 menit terakhir")
        val last = errs.last()
        val tag = if (last.tag.isEmpty()) "mpv" else last.tag
        return CheckResult(name, CheckLevel.Warn, "${errs.sumOf { it.count }} error mpv dalam 5 menit terakhir; terakhir [$tag] ${last.message.take(80)}", CheckAction.ViewMpvLog)
    }

    /**
     * [DIAG-DECODER] Dekoder aktif terakhir (dicatat pemutar) dibanding preferensi "hardware_decoding". Null bila pemutar
     * belum pernah mencatat dekoder. Hanya ⚠: SW bisa sah (codec tidak didukung mediacodec, atau dipilih manual).
     */
    private fun decoder(ctx: Context): CheckResult? {
        val active = MpvVersionBridge.decoder(ctx) ?: return null
        val wantHw = runCatching {
            ctx.getSharedPreferences(ctx.packageName + "_preferences", Context.MODE_PRIVATE).getBoolean("hardware_decoding", true)
        }.getOrDefault(true)
        val label = decoderLabel(active)
        return when {
            !wantHw -> CheckResult("Dekoder", CheckLevel.Ok, "$label; sesuai preferensi (dekode perangkat keras dimatikan)")
            active == "no" -> CheckResult("Dekoder", CheckLevel.Warn, "terakhir SW padahal preferensi HW aktif; codec mungkin tidak didukung mediacodec, atau dipilih manual")
            else -> CheckResult("Dekoder", CheckLevel.Ok, "$label; sesuai preferensi HW")
        }
    }

    private fun storage(ctx: Context): CheckResult {
        val granted = ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!granted) return CheckResult("Penyimpanan", CheckLevel.Bad, "izin penyimpanan belum diberikan", CheckAction.GrantPermission)
        val root = AppPaths.get(ctx).appRoot
        return when {
            !root.isDirectory -> CheckResult("Penyimpanan", CheckLevel.Warn, "${root.path} belum dibuat")
            !root.canWrite() -> CheckResult("Penyimpanan", CheckLevel.Bad, "${root.path} tidak bisa ditulis")
            else -> CheckResult("Penyimpanan", CheckLevel.Ok, "${root.path} dapat ditulis")
        }
    }

    private fun fmtBytes(b: Long?): String = when {
        b == null -> "?"
        b >= 1_073_741_824L -> "%.1f GB".format(b / 1_073_741_824.0)
        else -> "${b / 1_048_576L} MB"
    }

    /** Ruang bebas penyimpanan bersama (laporan, ekspor) dan data aplikasi (rootfs Debian); yang terkecil menentukan. */
    private fun freeSpace(ctx: Context): CheckResult {
        fun free(f: File): Long? = runCatching { StatFs(f.path).availableBytes }.getOrNull()
        val root = AppPaths.get(ctx).appRoot
        val shared = free(if (root.exists()) root else Environment.getExternalStorageDirectory())
        val data = free(ctx.filesDir)
        val minMb = (listOfNotNull(shared, data).minOrNull() ?: return CheckResult("Ruang bebas", CheckLevel.Warn, "tidak bisa dibaca")) / 1_048_576L
        val where = "penyimpanan ${fmtBytes(shared)}, data aplikasi ${fmtBytes(data)}"
        return when {
            minMb < CRITICAL_FREE_MB -> CheckResult("Ruang bebas", CheckLevel.Bad, "hampir habis: $where")
            minMb < LOW_FREE_MB -> CheckResult("Ruang bebas", CheckLevel.Warn, "di bawah $LOW_FREE_MB MB: $where")
            else -> CheckResult("Ruang bebas", CheckLevel.Ok, "bebas: $where")
        }
    }

    private fun mpv(ctx: Context): CheckResult {
        val miss = missing(ctx.applicationInfo.nativeLibraryDir, "libmpv.so", "libplayer.so")
        if (miss.isNotEmpty()) return CheckResult("libmpv", CheckLevel.Bad, "tidak ada: ${miss.joinToString()}")
        val v = MpvVersionBridge.mpv(ctx)
        return CheckResult("libmpv", CheckLevel.Ok, if (v != null) "terpasang, versi $v" else "terpasang, versi belum tercatat (putar video sekali)")
    }
}

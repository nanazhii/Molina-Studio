package com.molina.studio.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.StatFs
import com.molina.core.common.YtdlpCommand
import com.molina.studio.AppPaths
import com.molina.studio.cinema.MpvVersionBridge
import com.molina.studio.code.ScriptCommand
import com.molina.studio.terminal.RootfsManager
import com.molina.studio.terminal.RootfsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.TimeUnit

/** [DIAG-CHECK] Tingkat hasil pemeriksaan; urutan ordinal dipakai untuk mencari yang terburuk. */
internal enum class CheckLevel(val glyph: String) { Ok("\u2713"), Warn("\u26A0"), Bad("\u2717") }

internal class CheckResult(val name: String, val level: CheckLevel, val detail: String)

/**
 * Pemeriksaan nyata (bukan sekadar pengumpulan data): PRoot, rootfs Debian, Debian benar-benar bisa dijalankan,
 * izin dan folder /sdcard/Molina, ruang bebas, libmpv. Tidak mengubah apa pun selain menjalankan satu proses proot
 * ringan (echo) dengan batas waktu. Operasi disk dan proses: panggil di luar main thread.
 *
 * [deep] = false melewati pemeriksaan "Debian jalan" (dipakai lencana halaman Info agar tidak memulai proot tiap kali).
 */
internal object HealthCheck {
    private const val DEBIAN_TIMEOUT_S = 8L
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
            if (deep) add(debianRuns(app))
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
                    CheckResult("Debian", CheckLevel.Bad, "penanda siap, tetapi usr/bin tidak ada; pulihkan atau pasang ulang")
            RootfsState.Missing -> CheckResult("Debian", CheckLevel.Warn, "belum terpasang")
            is RootfsState.Failed -> CheckResult("Debian", CheckLevel.Bad, "gagal: ${st.message.take(120)}")
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
            return CheckResult(name, CheckLevel.Bad, "gagal memulai proot: ${e.javaClass.simpleName}")
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
            return CheckResult(name, CheckLevel.Bad, "proot tidak merespons dalam $DEBIAN_TIMEOUT_S dtk (dihentikan)")
        }
        reader.join(500)
        val text = synchronized(out) { out.toString() }
        val (_, rest) = YtdlpCommand.splitPidLine(text)
        val code = proc.exitValue()
        return if (code == 0 && rest.contains("molina-ok")) {
            CheckResult(name, CheckLevel.Ok, "proot menjalankan /bin/sh ($ms ms)")
        } else {
            CheckResult(name, CheckLevel.Bad, "keluar kode $code: ${rest.trim().lineSequence().firstOrNull().orEmpty().take(100)}")
        }
    }

    private fun storage(ctx: Context): CheckResult {
        val granted = ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!granted) return CheckResult("Penyimpanan", CheckLevel.Bad, "izin penyimpanan belum diberikan")
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

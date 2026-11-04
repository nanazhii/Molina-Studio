package com.molina.studio.settings

import androidx.compose.runtime.Composable
import com.molina.studio.packages.StarterProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.molina.studio.stream.YtdlpInstallState
import com.molina.studio.stream.YtdlpInstaller
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pintasan perawatan Debian di Settings > Sistem. Dijalankan lewat [ToolRunDialog] di Debian PRoot. */
internal enum class AptJob(val title: String, val command: String, val timeoutMs: Long, val note: String) {
    UpdateUpgrade(
        "Perbarui semua program",
        "apt update -o APT::Sandbox::User=root && " +
            "apt upgrade -y -o APT::Sandbox::User=root -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-confdef",
        50 * 60_000L,
        "Mengecek program terbaru di Debian, lalu memperbarui semuanya tanpa bertanya lagi. " +
            "Butuh internet, bisa memakai banyak kuota dan memakan beberapa menit. " +
            "Pembaruan hanya berjalan jika pengecekan berhasil. Bila yt-dlp punya rilis baru, ikut diperbarui setelahnya."
    ),
    CheckUpdates(
        "Cek pembaruan program",
        "apt update -o APT::Sandbox::User=root && (apt list --upgradable 2>/dev/null | grep -c 'upgradable from' || true)",
        10 * 60_000L,
        "Mengecek ada berapa program yang bisa diperbarui. Tidak ada yang dipasang atau diubah."
    ),
    CleanUp(
        "Bersihkan sisa program yang tidak terpakai",
        "apt-get autoremove -y -o APT::Sandbox::User=root && apt-get clean",
        10 * 60_000L,
        "Menghapus program yang tidak lagi dibutuhkan dan sisa file unduhan lama. Aman dijalankan kapan saja dan menambah ruang kosong."
    ),
    InstallStarter(
        "Pasang paket dasar (Starter)",
        StarterProfile.SCRIPT,
        40 * 60_000L,
        "Memasang perangkat dasar pilihan: ${StarterProfile.DISPLAY.joinToString(", ")} (dari sumber resmi Debian dan rilis resmi yt-dlp; keasliannya diperiksa otomatis). " +
            "Butuh internet dan ruang kosong minimal 1 GB; yang sudah terpasang dilewati. " +
            "yt-dlp dipasang otomatis setelahnya bila belum ada. ffmpeg tidak ikut; tambahkan dari Setelan > Paket > Alat bila perlu."
    )
}

/** Baris kunci keluaran apt yang disimpan dialog untuk ringkasan (aman walau log terpotong). */
private val APT_KEY_LINES = Regex("""(?i)(\d+ upgraded, \d+ newly installed|packages? can be upgraded|all packages are up to date)""")
private val APT_COUNTS = Regex("""(\d+) upgraded, (\d+) newly installed, (\d+) to remove and (\d+) not upgraded""")

/** Ringkasan satu baris untuk status dialog; null bila keluaran tidak dikenali (mis. bahasa lain). */
private fun aptSummary(lines: List<String>): String? {
    val m = lines.mapNotNull { APT_COUNTS.find(it) }.lastOrNull()
    if (m != null) {
        val (up, added, removed, held) = m.destructured
        return if (up == "0" && added == "0") "Sistem sudah terbaru."
        else buildString {
            append("Ringkasan: $up diperbarui, $added baru dipasang, $removed dihapus")
            if (held != "0") append(", $held ditunda")
            append(".")
        }
    }
    return if (lines.any { it.contains("up to date", ignoreCase = true) }) "Sistem sudah terbaru." else null
}

/** Ringkasan khusus profil Starter: "0 newly installed" berarti semuanya sudah ada, bukan "sistem terbaru". */
private fun starterSummary(lines: List<String>): String? {
    val m = lines.mapNotNull { APT_COUNTS.find(it) }.lastOrNull() ?: return null
    val added = m.groupValues[2]
    return if (added == "0") "Semua paket dasar sudah terpasang." else "$added paket baru dipasang (termasuk pendukungnya)."
}

private val COUNT_LINE = Regex("""^\d+$""")

private fun countSummary(lines: List<String>): String? =
    lines.lastOrNull()?.toIntOrNull()?.let { if (it == 0) "Sistem sudah terbaru." else "$it program dapat diperbarui." }

@Composable
internal fun DebianAptDialog(job: AptJob, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val check = job == AptJob.CheckUpdates
    val summarize: (List<String>) -> String? = when (job) {
        AptJob.CheckUpdates -> ::countSummary
        AptJob.InstallStarter -> ::starterSummary
        else -> ::aptSummary
    }
    ToolRunDialog(
        ToolRun("Debian: ${job.title}", job.command, job.timeoutMs, job.note, !check, if (check) COUNT_LINE else APT_KEY_LINES, summarize),
        onClose = onClose
    ) { code, out ->
        if (code != 0) return@ToolRunDialog
        when (job) {
            AptJob.UpdateUpgrade -> { // [PAKET-TERPUSAT]
                AptHistory.mark(ctx); AptHistory.setPending(ctx, 0)
                com.molina.studio.automation.AutoPrefs.init(ctx)
                if (com.molina.studio.automation.AutoPrefs.state.value.ytdlpOutdated) {
                    YtdlpInstaller.install(ctx)
                    Handler(Looper.getMainLooper()).post { Toast.makeText(ctx.applicationContext, "Memperbarui yt-dlp di latar belakang\u2026", Toast.LENGTH_LONG).show() }
                }
            }
            AptJob.CheckUpdates -> out.lineSequence().map { it.trim() }.lastOrNull { COUNT_LINE.matches(it) }
                ?.toIntOrNull()?.let { AptHistory.setPending(ctx, it) }
            AptJob.CleanUp -> Unit
            AptJob.InstallStarter -> YtdlpInstaller.installIfMissing(ctx) { r -> // [STARTER-YTDLP]
                val msg = when (r) {
                    is YtdlpInstallState.Done -> if (r.version != null) "yt-dlp terpasang, versi ${r.version}." else "yt-dlp sudah terpasang."
                    is YtdlpInstallState.Failed -> "yt-dlp gagal dipasang: ${r.message}. Coba lagi lewat kartu yt-dlp di Setelan > Paket."
                    else -> null
                }
                if (msg != null) Handler(Looper.getMainLooper()).post { Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show() }
            }
        }
    }
}

/** Waktu terakhir "perbarui dan tingkatkan" berhasil (SharedPreferences), ditampilkan di menu Debian. */
internal object AptHistory {
    private const val PREFS = "molina_apt"
    private const val KEY = "last_ok"
    private val _last = MutableStateFlow(0L)
    val last: StateFlow<Long> = _last.asStateFlow()
    private const val KEY_PENDING = "pending"
    private val _pending = MutableStateFlow(-1)
    /** Jumlah paket yang bisa ditingkatkan dari pemeriksaan terakhir; -1 = belum pernah diperiksa. */
    val pending: StateFlow<Int> = _pending.asStateFlow()
    private var loaded = false

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _last.value = sp.getLong(KEY, 0L)
        _pending.value = sp.getInt(KEY_PENDING, -1)
    }

    @Synchronized
    fun mark(ctx: Context) {
        val now = System.currentTimeMillis()
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, now).apply()
        _last.value = now
    }

    @Synchronized
    fun setPending(ctx: Context, n: Int) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_PENDING, n).apply()
        _pending.value = n
    }

    fun checkSubtitle(n: Int): String = when {
        n < 0 -> "Cek apakah ada program Debian yang bisa diperbarui"
        n == 0 -> "Sistem terbaru"
        else -> "$n pembaruan menunggu dipasang"
    }

    fun subtitle(at: Long): String =
        if (at <= 0L) "Belum pernah diperbarui di sini"
        else "Terakhir berhasil: " + SimpleDateFormat("d MMM HH.mm", Locale("id", "ID")).format(Date(at))
}

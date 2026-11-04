package com.molina.studio.terminal

import com.molina.core.common.LogLevel
import com.molina.studio.log.LogBus
import com.molina.studio.ui.MolinaTheme
import android.content.Context
import android.os.Environment
import java.io.File
import java.util.Locale

/** Identitas visual shell Molina: banner di sini; warna prompt bersumber dari [PromptTheme]. */
object MolinaShell {
    const val BRAND = "Welcome Molina Terminal"
    /** Cadangan warna banner (hijau Molina, tebal) bila warna tema prompt belum tersedia (prompt.env belum ada). */
    const val BRAND_SGR = "1;${MolinaTheme.GOOD_SGR}"
    /** Akar data aplikasi di dalam Debian (bind appRoot; lihat StorageBinds). */
    const val APP_ROOT = "/sdcard/Molina"
    /** Folder kerja terminal = akar Molina; dipakai bashrc untuk warna prompt (MOLINA_HOME). */
    const val HOME_DIR = APP_ROOT
    const val REDRAW_SEQ = "\u001b[99~"       // dipetakan ke redraw-current-line di bashrc

    /** Banner untuk tema prompt yang sedang aktif (warna = warna home tema; dipakai tombol CLEAR). */
    val banner: String get() = compose(TerminalPrefs.bannerStyle, "\r\n")

    /**
     * Banner berbingkai (3 baris, dipisah \r\n untuk ditulis langsung ke emulator) dengan warna teks [textSgr]
     * (mis. "1;32", tebal); bingkai memakai warna yang sama tanpa tebal. HARUS sama dengan __molina_banner di
     * assets/molina/bashrc: sudut membulat, teks diberi 2 spasi di kiri dan kanan. Fungsi murni.
     */
    fun bannerFor(textSgr: String): String {
        val bar = "\u2500".repeat(BRAND.length + 4)
        val f = "\u001b[${textSgr.removePrefix("1;")}m"
        val b = "\u001b[${textSgr}m"
        val r = "\u001b[0m"
        return listOf(
            "$f\u256D$bar\u256E$r",
            "$f\u2502$r  $b$BRAND$r  $f\u2502$r",
            "$f\u2570$bar\u256F$r",
        ).joinToString("\r\n")
    }

    // ---- Banner logo (BannerStyle.Logo): satu sumber kebenaran untuk sesi baru (lewat berkas) dan tombol CLEAR ----
    private val LOGO = listOf(
        "█▄ ▄█ █▀█ █   ▀█▀ █▄ █ ▄▀▄",
        "█ ▀ █ █ █ █    █  █ ▀█ █▀█",
        "▀   ▀ ▀▀▀ █▄▄ ▄█▄ ▀  ▀ ▀ ▀",
    )
    /** Gradasi emas per baris: terang, emas merek, pekat. */
    private val LOGO_SGR = listOf("38;2;245;208;122", MolinaTheme.GOLD_SGR, "38;2;184;137;58")
    private const val MUTED_SGR = "38;2;154;160;166"
    private val TIPS = listOf(
        "Cubit dua jari: ukuran font",
        "molina-git-secure: amankan token git",
        "CLEAR: bersihkan layar + banner",
        "Banner bisa dimatikan di Setelan",
        "molina: jembatan Debian ke aplikasi",
    )
    private val ID = Locale("id", "ID")
    @Volatile private var rootfs: File? = null

    /** Banner sesuai [style]; baris dipisah [nl] (\r\n ke emulator, \n ke berkas). Tanpa pemisah di ujung. */
    fun compose(style: BannerStyle, nl: String): String = when (style) {
        BannerStyle.Off -> ""
        BannerStyle.Compact -> bannerFor(TerminalPrefs.promptTheme.homeSgr).replace("\r\n", nl)
        BannerStyle.Logo -> logoBanner(nl)
    }

    private fun logoBanner(nl: String): String {
        val r = "\u001b[0m"
        val lines = LOGO.mapIndexed { i, row -> "\u001b[${LOGO_SGR[i]}m$row$r" }.toMutableList()
        statusLine()?.let { lines += "\u001b[${MUTED_SGR}m$it$r" }
        lines += "\u001b[2;${MUTED_SGR}mtip: ${TIPS.random()}$r"
        return lines.joinToString(nl)
    }

    private fun statusLine(): String? =
        listOfNotNull(debianLabel(), ramText(), storageText())
            .takeIf { it.isNotEmpty() }?.joinToString(" \u00B7 ")

    private fun debianLabel(): String? {
        val root = rootfs ?: return null
        val v = runCatching { File(root, "etc/debian_version").readText().trim() }.getOrNull()
        return if (v.isNullOrEmpty()) "Debian" else "Debian $v"
    }

    private fun ramText(): String? = runCatching {
        var total = 0L
        var avail = 0L
        File("/proc/meminfo").forEachLine { l ->
            if (l.startsWith("MemTotal:")) total = l.filter(Char::isDigit).toLong()
            else if (l.startsWith("MemAvailable:")) avail = l.filter(Char::isDigit).toLong()
        }
        if (total <= 0L) null
        else String.format(ID, "RAM %.1f/%.1f GB", (total - avail) / 1048576.0, total / 1048576.0)
    }.getOrNull()

    private fun storageText(): String? = runCatching {
        String.format(ID, "%.0f GB bebas", Environment.getExternalStorageDirectory().usableSpace / 1073741824.0)
    }.getOrNull()

    /**
     * Rakit banner sesi baru ke etc/molina/banner.txt; bashrc hanya menampilkan isinya (kosong = banner mati).
     * Hanya menulis bila isinya berbeda; gagal tulis dicatat, tidak menghentikan sesi.
     */
    fun writeBanner(rootfsDir: File) {
        rootfs = rootfsDir
        val body = compose(TerminalPrefs.bannerStyle, "\n")
        val text = if (body.isEmpty()) "" else body + "\n"
        val f = File(rootfsDir, "etc/molina/banner.txt")
        runCatching {
            f.parentFile?.mkdirs()
            if (!f.isFile || f.readText() != text) f.writeText(text)
        }.onFailure {
            LogBus.log("sistem", LogLevel.Warn, "Shell", "Gagal menulis banner Molina: ${it.message}")
        }
    }

    /** Tulis warna prompt aktif; dibaca bashrc di setiap prompt (perubahan tema langsung berlaku). */
    fun writePromptEnv(rootfsDir: File, theme: PromptTheme) {
        val f = File(rootfsDir, "etc/molina/prompt.env")
        val text = "MOLINA_PS_HOME='${theme.homeSgr}'\nMOLINA_PS_OTHER='${theme.otherSgr}'\n" +
            "MOLINA_PS_USER='${theme.userSgr}'\nMOLINA_PS_BRANCH='${theme.branchSgr}'\n"
        f.parentFile?.mkdirs()
        if (!f.isFile || f.readText() != text) f.writeText(text)
    }

    /** Salin bashrc Molina dari assets ke rootfs; hanya menulis bila isinya berbeda. */
    fun installRc(ctx: Context, target: File) {
        val text = ctx.assets.open("molina/bashrc").bufferedReader().use { it.readText() }
        target.parentFile?.mkdirs()
        if (!target.isFile || target.readText() != text) target.writeText(text)
    }

    /**
     * Pasang CLI `molina` di Debian (jembatan Debian -> aplikasi, lihat cmd/CommandServer).
     * Hanya menulis bila isinya berbeda; gagal tulis dicatat, tidak menghentikan sesi.
     */
    fun installCli(ctx: Context, rootfsDir: File) {
        val files = listOf(
            Triple("molina/molina-cli.sh", "usr/local/bin/molina", true),
            Triple("molina/molina-cli.py", "usr/local/lib/molina/molina_cli.py", false),
        )
        for ((asset, rel, exec) in files) {
            runCatching {
                val data = ctx.assets.open(asset).use { it.readBytes() }
                val f = File(rootfsDir, rel)
                f.parentFile?.mkdirs()
                if (!f.isFile || !f.readBytes().contentEquals(data)) f.writeBytes(data)
                if (exec && !f.canExecute()) f.setExecutable(true, false)
            }.onFailure {
                LogBus.log("sistem", LogLevel.Warn, "Shell", "Gagal memasang CLI molina ($rel): ${it.message}")
            }
        }
    }

    private const val APT_CONF = """// Molina: default apt/dpkg yang aman dan hemat (dibuat otomatis, ditimpa tiap sesi).
Acquire::AllowInsecureRepositories "false";
Acquire::AllowDowngradeToInsecureRepositories "false";
Acquire::Languages "none";
APT::Install-Recommends "false";
APT::Install-Suggests "false";
Dpkg::Options {
  "--force-confdef";
  "--force-confold";
};
"""

    /**
     * Default apt/dpkg guest. Aman: repo tak bertanda tangan ditolak, konfigurasi lokal tidak
     * ditimpa paket. Hemat: tanpa Recommends/Suggests dan tanpa indeks Translation.
     * Hanya menulis bila isinya berbeda; gagal tulis dicatat, tidak menghentikan sesi.
     */
    fun installAptConf(rootfsDir: File) {
        val f = File(rootfsDir, "etc/apt/apt.conf.d/02-molina-defaults")
        runCatching {
            f.parentFile?.mkdirs()
            if (!f.isFile || f.readText() != APT_CONF) f.writeText(APT_CONF)
        }.onFailure {
            LogBus.log("sistem", LogLevel.Warn, "Shell", "Gagal menulis apt.conf Molina: ${it.message}")
        }
    }
}

package com.molina.studio.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.molina.studio.AppPaths
import com.molina.studio.cinema.MpvVersionBridge
import com.molina.studio.terminal.RootfsManager
import com.molina.studio.terminal.RootfsState
import java.io.File

/** [DIAG-CHECK] Tingkat hasil pemeriksaan; urutan ordinal dipakai untuk mencari yang terburuk. */
internal enum class CheckLevel(val glyph: String) { Ok("\u2713"), Warn("\u26A0"), Bad("\u2717") }

internal class CheckResult(val name: String, val level: CheckLevel, val detail: String)

/**
 * Pemeriksaan nyata (bukan sekadar pengumpulan data): PRoot, rootfs Debian, izin dan folder /sdcard/Molina, libmpv.
 * Hanya membaca status; tidak mengubah apa pun. Operasi disk: panggil di luar main thread.
 */
internal object HealthCheck {
    fun run(ctx: Context): List<CheckResult> {
        val app = ctx.applicationContext
        return listOf(proot(app), rootfs(app), storage(app), mpv(app))
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

    private fun mpv(ctx: Context): CheckResult {
        val miss = missing(ctx.applicationInfo.nativeLibraryDir, "libmpv.so", "libplayer.so")
        if (miss.isNotEmpty()) return CheckResult("libmpv", CheckLevel.Bad, "tidak ada: ${miss.joinToString()}")
        val v = MpvVersionBridge.mpv(ctx)
        return CheckResult("libmpv", CheckLevel.Ok, if (v != null) "terpasang, versi $v" else "terpasang, versi belum tercatat (putar video sekali)")
    }
}

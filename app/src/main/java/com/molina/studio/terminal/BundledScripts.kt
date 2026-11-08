package com.molina.studio.terminal

import android.content.Context
import com.molina.core.common.BundledScriptPolicy
import com.molina.core.common.BundledScriptPolicy.Action
import com.molina.core.common.LogLevel
import com.molina.core.common.MolinaDir
import com.molina.studio.AppPaths
import com.molina.studio.log.LogBus
import java.io.File
import java.security.MessageDigest

/**
 * Skrip bawaan di assets/molina/ yang disalin ke /sdcard/Molina/Scripts agar terlihat di Debian.
 * Berkas yang diedit pengguna tidak pernah ditimpa. Salinan yang masih persis bawaan lama diperbarui
 * otomatis (dibandingkan lewat hash SHA-256); bila diedit atau asalnya tidak diketahui, versi baru
 * ditulis berdampingan sebagai `nama.new` agar bisa dibandingkan dan diganti manual.
 */
internal object BundledScripts {
    private val NAMES = listOf("molina_dl.py")
    private const val PREFS = "molina_bundled_scripts"

    /** Operasi disk: panggil di luar main thread, setelah izin storage ada. Gagal tidak melempar. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val dir = AppPaths.get(app).dir(MolinaDir.Scripts)
        if (!dir.isDirectory && !dir.mkdirs()) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (name in NAMES) {
            val bundled = runCatching { app.assets.open("molina/$name").use { it.readBytes() } }.getOrNull()
            if (bundled == null) {
                LogBus.log("sistem", LogLevel.Warn, "Skrip", "Skrip bawaan tidak ada di aplikasi: $name")
                continue
            }
            val bundledHash = sha256(bundled)
            val target = File(dir, name)
            val targetHash = if (target.isFile) runCatching { sha256(target.readBytes()) }.getOrNull() else null
            if (target.exists() && targetHash == null) continue // tidak terbaca: jangan disentuh
            val recorded = prefs.getString("hash.$name", null)
            val staged = prefs.getString("staged.$name", null)
            when (BundledScriptPolicy.decide(targetHash, recorded, staged, bundledHash)) {
                Action.INSTALL, Action.UPDATE -> {
                    val verb = if (targetHash == null) "disalin" else "diperbarui"
                    if (atomicWrite(File(dir, name), bundled)) {
                        prefs.edit().putString("hash.$name", bundledHash).apply()
                        LogBus.log("sistem", LogLevel.Info, "Skrip", "Skrip bawaan $verb: Scripts/$name")
                    } else {
                        LogBus.log("sistem", LogLevel.Warn, "Skrip", "Skrip bawaan tidak bisa ditulis: $name")
                    }
                }
                Action.RECORD -> prefs.edit().putString("hash.$name", bundledHash).apply()
                Action.STAGE_NEW -> {
                    if (atomicWrite(File(dir, "$name.new"), bundled)) {
                        prefs.edit().putString("staged.$name", bundledHash).apply()
                        LogBus.log(
                            "sistem", LogLevel.Info, "Skrip",
                            "Versi baru $name tersedia sebagai Scripts/$name.new (berkas Anda tidak diubah)",
                        )
                    }
                }
                Action.KEEP -> Unit
            }
        }
    }

    private fun atomicWrite(target: File, data: ByteArray): Boolean {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        return try {
            tmp.writeBytes(data)
            (tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))).also { if (!it) tmp.delete() }
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

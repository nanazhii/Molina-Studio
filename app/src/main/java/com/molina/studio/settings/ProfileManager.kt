package com.molina.studio.settings

import android.content.Context
import com.molina.core.common.MolinaDir
import com.molina.studio.AppPaths
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class SettingsProfile(val id: String, val name: String, val savedMs: Long)

/**
 * Profil setelan: satu berkas JSON per profil di /sdcard/Molina/Backup/profiles/<id>.json.
 * Isi hanya setelan (prefs yang diizinkan + Terminal), tidak menyentuh penanda, snippet, atau skrip.
 * Semua fungsi melakukan operasi disk: panggil di luar main thread.
 */
internal object ProfileManager {
    private const val VERSION = 1
    private const val MAX_NAME = 32
    private val ID_RE = Regex("[a-z0-9-]{1,40}")

    private fun dir(ctx: Context): File = File(AppPaths.get(ctx).dir(MolinaDir.Backup), "profiles")

    fun idOf(name: String): String =
        name.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)

    fun list(ctx: Context): List<SettingsProfile> {
        val files = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                val o = JSONObject(f.readText())
                if (o.optString("kind") != "profile") return@mapNotNull null
                SettingsProfile(f.nameWithoutExtension, o.optString("name", f.nameWithoutExtension), f.lastModified())
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun save(ctx: Context, rawName: String): String {
        val name = rawName.trim().take(MAX_NAME)
        val id = idOf(name)
        if (id.isEmpty()) return "Nama profil harus berisi huruf atau angka"
        return try {
            val root = BackupManager.settingsSnapshot(ctx)
                .put("app", "molina")
                .put("kind", "profile")
                .put("version", VERSION)
                .put("name", name)
                .put("created", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            val f = File(dir(ctx), "$id.json")
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            val text = root.toString(2) + "\n"
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
            "Profil \"$name\" disimpan"
        } catch (e: Exception) {
            "Gagal menyimpan profil (cek izin penyimpanan)"
        }
    }

    fun apply(ctx: Context, id: String): String {
        if (!ID_RE.matches(id)) return "Profil tidak valid"
        val f = File(dir(ctx), "$id.json")
        if (!f.isFile) return "Profil tidak ditemukan"
        return try {
            val root = JSONObject(f.readText())
            if (root.optString("kind") != "profile") return "Berkas profil tidak valid"
            val n = BackupManager.applySettingsSnapshot(ctx, root)
            "Profil \"${root.optString("name", id)}\" diterapkan ($n setelan). Mulai ulang aplikasi agar semua setelan berlaku."
        } catch (e: Exception) {
            "Gagal menerapkan profil: berkas tidak valid"
        }
    }

    fun delete(ctx: Context, id: String): String {
        if (!ID_RE.matches(id)) return "Profil tidak valid"
        val f = File(dir(ctx), "$id.json")
        return if (f.isFile && f.delete()) "Profil dihapus" else "Profil tidak ditemukan"
    }
}

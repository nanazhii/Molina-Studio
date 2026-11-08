package com.molina.studio.settings

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * MOLINA-H13-BRIDGE: pengirim impor ke Molina Vault (kontrak: docs/VAULT_INTENT.md).
 *
 * Keamanan berlapis:
 * 1. Tanda tangan Vault harus sama dengan Studio sebelum satu URI pun dikirim (mencegah aplikasi palsu ber-package sama).
 * 2. Komponen eksplisit + izin signature yang ditegakkan sistem pada activity-alias Vault.
 * 3. Berkas asli hanya boleh dihapus bila nama, ukuran, dan SHA-256 laporan Vault cocok dengan hash yang dihitung ulang
 *    dari berkas asli. Tanpa hasil, hasil rusak, atau tidak cocok: tidak ada yang dihapus.
 * Sengaja TANPA SHARE-GUARD: tujuannya Vault bertanda tangan sama dan terenkripsi, tempat yang tepat untuk berkas kunci.
 */
internal object VaultBridge {
    private const val PKG = "com.molina.vault"
    private const val ENTRY = "com.molina.vault.ImportEntry"
    private const val ACTION_IMPORT = "com.molina.vault.action.IMPORT"
    private const val PERMISSION = "com.molina.vault.permission.IMPORT"
    private const val EXTRA_SAVED_NAMES = "com.molina.vault.extra.SAVED_NAMES"
    private const val EXTRA_SAVED_SHA256 = "com.molina.vault.extra.SAVED_SHA256"
    private const val EXTRA_SAVED_SIZES = "com.molina.vault.extra.SAVED_SIZES"
    private const val MAX_FILES = 100
    private const val MAX_FILE_BYTES = 256L * 1024 * 1024

    class Request(val intent: Intent, val files: List<File>, val note: String)

    sealed class Prepared {
        class Ready(val request: Request) : Prepared()
        class Fail(val message: String) : Prepared()
    }

    /** [deletable] = berkas asli yang terbukti identik dengan salinan di Vault (boleh ditawarkan untuk dihapus). */
    class Outcome(val deletable: List<File>, val message: String)

    private class Saved(val name: String, val size: Long, val sha: String)

    fun prepare(ctx: Context, files: List<File>): Prepared {
        val pm = ctx.packageManager
        try {
            pm.getPackageInfo(PKG, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return Prepared.Fail("Molina Vault belum terpasang.")
        }
        if (pm.checkSignatures(ctx.packageName, PKG) != PackageManager.SIGNATURE_MATCH) {
            return Prepared.Fail("Tanda tangan Vault berbeda dari Molina Studio. Bangun keduanya dengan keystore yang sama (secret MOLINA_KEYSTORE_B64).")
        }
        if (ctx.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return Prepared.Fail("Izin impor Vault belum diberikan. Pasang ulang Molina Studio setelah Vault terpasang.")
        }
        var unreadable = 0
        var tooBig = 0
        var overLimit = 0
        val usable = ArrayList<File>()
        for (f in files) {
            if (!f.isFile || !f.canRead()) { unreadable++; continue }
            if (f.length() > MAX_FILE_BYTES) { tooBig++; continue }
            if (usable.size >= MAX_FILES) { overLimit++; continue }
            usable.add(f)
        }
        val auth = ctx.packageName + ".fileprovider"
        var outside = 0
        val ready = ArrayList<File>()
        val uris = ArrayList<Uri>()
        for (f in usable) {
            try {
                uris.add(FileProvider.getUriForFile(ctx, auth, f))
                ready.add(f)
            } catch (e: IllegalArgumentException) {
                outside++
            }
        }
        val skipped = ArrayList<String>()
        if (unreadable > 0) skipped.add("$unreadable tidak terbaca")
        if (tooBig > 0) skipped.add("$tooBig melebihi 256 MB")
        if (overLimit > 0) skipped.add("$overLimit melebihi batas $MAX_FILES berkas")
        if (outside > 0) skipped.add("$outside di luar penyimpanan internal")
        val note = if (skipped.isEmpty()) "" else " Dilewati: " + skipped.joinToString(", ") + "."
        if (ready.isEmpty()) return Prepared.Fail("Tidak ada berkas yang bisa dikirim ke Vault." + note)

        val clip = ClipData.newRawUri("Molina Vault", uris[0])
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        val intent = Intent(ACTION_IMPORT).setComponent(ComponentName(PKG, ENTRY))
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Prepared.Ready(Request(intent, ready, note))
    }

    /** Cocokkan laporan Vault dengan berkas asli. Hash dihitung ulang di thread IO. */
    suspend fun verify(req: Request, resultCode: Int, data: Intent?, offerDelete: Boolean = true): Outcome = withContext(Dispatchers.IO) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            return@withContext Outcome(emptyList(), "Vault tidak mengembalikan hasil (dibatalkan atau ditutup). Berkas asli tidak dihapus.")
        }
        val saved: List<Saved>? = runCatching {
            val n = data.getStringArrayListExtra(EXTRA_SAVED_NAMES)
            val h = data.getStringArrayListExtra(EXTRA_SAVED_SHA256)
            val s = data.getLongArrayExtra(EXTRA_SAVED_SIZES)
            if (n == null || h == null || s == null || n.size != h.size || n.size != s.size) null
            else n.indices.map { Saved(n[it], s[it], h[it].lowercase()) }
        }.getOrNull()
        if (saved == null) return@withContext Outcome(emptyList(), "Hasil Vault tidak sah. Berkas asli tidak dihapus.")
        if (saved.isEmpty()) return@withContext Outcome(emptyList(), "Tidak ada berkas yang tersimpan di Vault. Berkas asli tidak dihapus." + req.note)

        val remaining = ArrayList(saved)
        val ok = ArrayList<File>()
        for (f in req.files) {
            val len = f.length()
            if (remaining.none { it.name == f.name && it.size == len }) continue
            val h = sha256(f) ?: continue
            val j = remaining.indexOfFirst { it.name == f.name && it.size == len && it.sha == h }
            if (j >= 0) {
                remaining.removeAt(j)
                ok.add(f)
            }
        }
        val head = when {
            ok.isEmpty() -> "Vault melaporkan ${saved.size} berkas tersimpan, tetapi tidak ada yang cocok SHA-256 dengan berkas asli (atau berkas berubah). Tidak ada yang dihapus."
            ok.size < req.files.size -> "${ok.size} dari ${req.files.size} berkas terverifikasi di Vault (SHA-256). Sisanya dipertahankan."
            else -> "${ok.size} berkas tersimpan di Vault dan terverifikasi (SHA-256)." + (if (offerDelete) " Hapus berkas asli?" else "")
        }
        Outcome(ok, head + req.note)
    }

    private fun sha256(f: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: IOException) {
        null
    }
}

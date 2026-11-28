package com.molina.studio.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.molina.studio.common.AppFileProvider
import java.io.File

/**
 * [LOGCAT-FILE-SHARE] Bagikan logcat.log lewat chooser sistem. Memakai FileProvider yang sama dengan ZIP diagnostik
 * (external-path "." di molina_file_paths.xml sudah menjangkau <appRoot>/Logs/Logcat), jadi Manifest tidak berubah.
 * Isi berkas sudah disamarkan oleh LogFileWriter (URL dan token).
 */
internal object LogFileShare {
    /** [MPV-LOG-SHARE] [note] opsional: dikirim sebagai teks pesan (mis. ID sesi dan penyebab galat). */
    fun share(ctx: Context, f: File, note: String? = null) {
        try {
            val uri = FileProvider.getUriForFile(ctx, AppFileProvider.authority(ctx), f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Molina ${f.name}")
                if (!note.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, note) // [MPV-LOG-SHARE]
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Bagikan ${f.name}")
            if (ctx !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "Tidak ada aplikasi untuk membagikan berkas. Berkas ada di: ${f.path}", Toast.LENGTH_LONG).show()
        } catch (e: IllegalArgumentException) {
            Toast.makeText(ctx, "Berkas ada di: ${f.path}", Toast.LENGTH_LONG).show()
        }
    }
}

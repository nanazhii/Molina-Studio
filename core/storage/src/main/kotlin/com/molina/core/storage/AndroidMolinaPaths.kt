package com.molina.core.storage

import android.content.Context
import android.os.Environment
import com.molina.core.common.MolinaDir
import com.molina.core.common.MolinaPaths
import java.io.File

/** Implementasi Android untuk [MolinaPaths]. Butuh izin storage agar [ensureDirs] berhasil. */
class AndroidMolinaPaths(context: Context, workspaceFolder: String = MolinaPaths.DEV_FOLDER) : MolinaPaths {
    private val external: File = Environment.getExternalStorageDirectory()

    override val appRoot: File = File(external, MolinaPaths.APP_FOLDER)
    override val devProject: File = File(external, workspaceFolder)
    override val privateRoot: File = context.applicationContext.filesDir

    /** Membuat appRoot + semua subfolder. Operasi disk: panggil di luar main thread. */
    fun ensureDirs(): Boolean {
        var ok = appRoot.isDirectory || appRoot.mkdirs()
        for (kind in MolinaDir.entries) {
            val d = dir(kind)
            ok = (d.isDirectory || d.mkdirs()) && ok
        }
        return ok
    }
}

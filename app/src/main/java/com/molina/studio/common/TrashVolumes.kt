package com.molina.studio.common

import android.os.Environment
import com.molina.core.api.MolinaServices
import java.io.File

/** Akar yang punya Recycle Bin (.molina-trash): penyimpanan internal, kartu SD, dan beranda Debian. */
internal fun trashVolumes(ctx: android.content.Context): List<File> {
    val out = mutableListOf(Environment.getExternalStorageDirectory())
    File("/storage").listFiles()?.forEach {
        if (it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self") out.add(it)
    }
    val home = File(MolinaServices.terminal.rootfsDir(), "root")
    if (home.isDirectory) out.add(home)
    return out
}

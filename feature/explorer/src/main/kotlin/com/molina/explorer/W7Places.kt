package com.molina.explorer

import android.os.Environment
import java.io.File

/** [FEATURE-EXPLORER] Tempat favorit Penjelajah (Molina, Video, Unduhan, Musik, Kamera). */
fun w7Favorites(browse: Boolean = false): List<W7Place> = listOfNotNull(
    W7Place("Molina", File(Environment.getExternalStorageDirectory(), com.molina.core.system.MolinaPaths.APP_FOLDER), true),
    W7Place("Video", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)),
    W7Place("Unduhan", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)),
    W7Place("Musik", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)),
    W7Place("Kamera", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM))
).filter { it.dir.isDirectory }

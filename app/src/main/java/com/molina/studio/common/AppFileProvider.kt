package com.molina.studio.common

import android.content.Context

/** Authority FileProvider aplikasi: satu sumber. Harus sama dengan `android:authorities` di AndroidManifest.xml (`${applicationId}.fileprovider`). */
internal object AppFileProvider {
    private const val SUFFIX = ".fileprovider"

    fun authority(ctx: Context): String = ctx.packageName + SUFFIX
}

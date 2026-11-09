package com.molina.studio.terminal

import android.content.Context
import android.graphics.Typeface

/**
 * Font terminal Molina: JetBrains Mono (aset app, SIL OFL 1.1, lisensi sudah tercatat di
 * THIRD_PARTY_NOTICES.md). Gagal memuat -> Typeface.MONOSPACE, terminal tetap jalan.
 */
object MolinaFont {
    private const val ASSET = "fonts/JetBrainsMono-Regular.ttf"
    @Volatile private var cached: Typeface? = null

    fun typeface(ctx: Context): Typeface =
        cached ?: synchronized(this) {
            cached ?: (runCatching { Typeface.createFromAsset(ctx.applicationContext.assets, ASSET) }.getOrNull()
                ?: Typeface.MONOSPACE).also { cached = it }
        }
}

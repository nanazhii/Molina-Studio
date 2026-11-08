// Pengganti FileOps (yang butuh pustaka arsip) hanya untuk jalur zipImage; tidak dipakai pada uji poster.
package com.molina.studio.cinema

import java.io.File

internal object FileOps {
    fun readEntryBytes(zip: File, entryName: String, password: String?, maxBytes: Int): ByteArray? = null
}

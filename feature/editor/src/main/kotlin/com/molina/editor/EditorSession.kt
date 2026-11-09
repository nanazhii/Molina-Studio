package com.molina.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * [EDITOR-SESSION] Berkas yang sedang dibuka di editor. Hidup di luar komposisi: tab Berkas keluar dari
 * komposisi saat pindah tab (MainActivity memakai when(Tab)), jadi path disimpan di sini agar kembali ke tab
 * Berkas membuka editor di berkas yang sama. Isi diselamatkan oleh autosave/flush di [EditorScreen].
 */
object EditorSession {
    var file: File? by mutableStateOf(null)
        private set

    fun open(f: File) { file = f }
    fun close() { file = null }
}

package com.molina.studio.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** [CMD-MARK] Pemicu kilatan tepi bingkai Terminal setelah perintah selesai: hijau berhasil, merah gagal. */
object CommandFlash {
    /** Naik 1 tiap kilatan; UI mengamati nilai ini untuk memulai animasi. */
    var seq by mutableStateOf(0)
        private set
    var ok by mutableStateOf(true)
        private set

    fun fire(success: Boolean) {
        ok = success
        seq += 1
    }
}

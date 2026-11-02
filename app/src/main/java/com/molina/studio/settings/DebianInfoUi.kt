package com.molina.studio.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.molina.studio.terminal.RootfsManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Rilis Debian dan jumlah paket terpasang [DEBIAN-INFO]. */
internal class DebianInfo(val release: String, val packages: Int)

internal object DebianInfoReader {
    /**
     * Dibaca langsung dari berkas rootfs (etc/debian_version dan var/lib/dpkg/status) tanpa menjalankan PRoot,
     * jadi cepat dan aman dipanggil di latar. Mengembalikan null bila rootfs belum terpasang.
     */
    fun read(ctx: Context): DebianInfo? {
        val root = RootfsManager.rootfsDir(ctx)
        val ver = File(root, "etc/debian_version")
        if (!ver.isFile) return null
        val release = runCatching { ver.readText().trim() }.getOrDefault("").ifBlank { "?" }
        var count = 0
        runCatching {
            File(root, "var/lib/dpkg/status").bufferedReader().useLines { lines ->
                lines.forEach { if (it == "Status: install ok installed") count++ }
            }
        }
        return DebianInfo(release, count)
    }
}

/** Settings > Paket > Debian (apt): rilis Debian dan jumlah paket. [refreshKey] berubah setelah apt selesai agar angka dibaca ulang. */
@Composable
internal fun DebianInfoRow(refreshKey: Any?) {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<DebianInfo?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(refreshKey) {
        info = withContext(Dispatchers.IO) { DebianInfoReader.read(ctx) }
        loaded = true
    }
    val i = info
    SettingsItem(
        "Rilis Debian",
        when {
            !loaded -> "Membaca\u2026"
            i == null -> "Rootfs Debian belum terpasang"
            else -> "Debian ${i.release} \u00B7 ${i.packages} paket terpasang"
        }
    ) {}
}

package com.molina.studio.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.molina.preview.PreviewScreen

/** [NANO-PREVIEW] Jendela pratinjau layar penuh di atas aplikasi; Back/TUTUP kembali ke terminal (nano tetap berjalan). */
@Composable
fun PreviewHost(htmlWebView: Boolean) {
    val f = PreviewNav.pending ?: return
    Dialog(
        onDismissRequest = { PreviewNav.pending = null },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize()) {
            PreviewScreen(f, htmlWebView, onClose = { PreviewNav.pending = null })
        }
    }
}

package com.molina.studio.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Isi tab Berkas (nav bawah). Penjelajah Molina dibuka otomatis saat tab dipilih. [EXPLORER-ONE] Seluruh isi dan
 * aksinya ada di [MolinaExplorerHost] (sumber tunggal, dibagi dengan dialog Folder IPTV di Saluran Home MPV).
 */
@Composable
fun FilesTabScreen(modifier: Modifier = Modifier, onExit: () -> Unit = {}, onOpenEditor: (() -> Unit)? = null) {
    MolinaExplorerHost(
        onDismiss = onExit,
        modifier = modifier,
        asScreen = true,
        onOpenEditor = onOpenEditor
    )
}

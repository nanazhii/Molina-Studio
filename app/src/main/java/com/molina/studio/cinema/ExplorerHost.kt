package com.molina.studio.cinema

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.molina.core.api.ExplorerPickKind
import com.molina.core.api.MolinaServices
import com.molina.explorer.Win7OpenDialog

/** [EXPLORER-GW] Host UI tunggal: merender Penjelajah sebagai pemilih bila ada permintaan dari fitur lain lewat ExplorerGateway. */
@Composable
internal fun ExplorerHost() {
    val gw = MolinaServices.explorer
    val req by gw.request.collectAsState()
    val r = req ?: return
    key(r.id) {
        if (r.kind == ExplorerPickKind.FOLDER) Win7OpenDialog(
            onDismiss = { gw.cancel(r.id) },
            pickFolder = true,
            onPickFolder = { gw.complete(r.id, it) },
            startDir = r.startDir,
        env = rememberAppExplorerEnv()
    ) else Win7OpenDialog(
            onDismiss = { gw.cancel(r.id) },
            onPick = { gw.complete(r.id, it) },
            startDir = r.startDir,
        env = rememberAppExplorerEnv()
    )
    }
}

package com.molina.core.api

sealed interface RootfsState {
    data object Missing : RootfsState
    data class Downloading(val percent: Int) : RootfsState
    data object Verifying : RootfsState
    data class Extracting(val percent: Int) : RootfsState
    data object Ready : RootfsState
    data class Failed(val message: String) : RootfsState
    /** [TERM-ROOTFS2] backup/restore/reset sedang berjalan (RootfsAdmin); Terminal terkunci. */
    data class Maintenance(val label: String) : RootfsState
}

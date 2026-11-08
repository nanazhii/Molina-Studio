package com.molina.code.lsp

import com.molina.code.preview.GuestPaths

/**
 * [CODE-LSP2] Penerjemah path Android <-> path di dalam Debian. Aturan terlihat-tidaknya sama dengan
 * GuestPaths (dan bind PRoot di ProotScriptExecutor): default hanya folder kerja [workspace] dan [extraRoots];
 * [fullAccess] membuka seluruh storage. Guest melihat storage di /sdcard dan /storage/emulated/0.
 * Path di luar yang terlihat = null (tidak pernah ditebak).
 */
class LspPathMap(
    storageRoot: String,
    private val workspace: String,
    private val fullAccess: Boolean,
    private val extraRoots: List<String> = emptyList(),
) {
    private val root = storageRoot.trimEnd('/')

    fun hostToGuestPath(hostPath: String): String? = GuestPaths.of(hostPath, root, workspace, fullAccess, extraRoots)

    fun hostToGuestUri(hostPath: String): String? = hostToGuestPath(hostPath)?.let { LspUri.fromPath(it) }

    fun guestToHostPath(guestPath: String): String? {
        val rel = when {
            guestPath == "/sdcard" || guestPath.startsWith("/sdcard/") -> guestPath.removePrefix("/sdcard")
            guestPath == "/storage/emulated/0" || guestPath.startsWith("/storage/emulated/0/") ->
                guestPath.removePrefix("/storage/emulated/0")
            else -> return null
        }
        if (rel.split('/').any { it == ".." }) return null
        val host = root + rel
        return if (hostToGuestPath(host) != null) host else null
    }

    /** URI `file://` milik guest -> path Android; null bila bukan file://, rusak, atau tidak terlihat di host. */
    fun guestUriToHostPath(uri: String): String? = LspUri.toPath(uri)?.let { guestToHostPath(it) }
}

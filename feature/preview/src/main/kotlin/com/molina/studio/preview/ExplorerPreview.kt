package com.molina.studio.preview

import com.molina.code.preview.PreviewKind
import com.molina.code.preview.PreviewKinds
import java.io.File

/** [EXPLORER-PREVIEW] Penjelajah: hanya Markdown, HTML, dan JSON yang punya menu Pratinjau. */
object ExplorerPreview {
    fun canPreview(name: String): Boolean = when (PreviewKinds.of(name)) {
        PreviewKind.MARKDOWN, PreviewKind.HTML, PreviewKind.JSON -> true
        else -> false
    }

    fun canPreview(f: File): Boolean = f.isFile && canPreview(f.name)
}

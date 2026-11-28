package com.molina.studio.preview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** [NANO-PREVIEW] Berkas yang diminta untuk dipratinjau (molina preview / nano Alt+P); dikonsumsi PreviewHost. */
object PreviewNav {
    var pending by mutableStateOf<File?>(null)
}

package com.molina.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.core.files.PathCrumbs
import java.io.File

/**
 * [DRAWER-HEADER-SAMA] Kotak path berbingkai di header panel direktori: "Internal > Molina".
 * Tampilan sama persis dengan DrawerPathBar drawer Terminal (tinggi 30dp, isi #101010, garis #3A3A3A).
 * Segmen yang boleh dituju bisa diketuk; segmen terakhir = folder saat ini. Logika segmen: PathCrumbs (:core:files).
 */
@Composable
internal fun DrawerPathBar(path: String, canGo: (File) -> Boolean, onGo: (File) -> Unit, modifier: Modifier = Modifier) {
    val tail = PathCrumbs.tail(path)
    val dim = CodeColors.SecondaryNeutral
    Row(
        modifier.height(30.dp).background(CodeColors.PathField).border(1.dp, CodeColors.PathFieldBorder).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (tail.truncated) BasicText("…" + PathCrumbs.SEP, maxLines = 1, style = TextStyle(color = dim, fontSize = 12.sp))
        tail.crumbs.forEachIndexed { i, c ->
            val last = i == tail.crumbs.lastIndex
            val target = File(c.path)
            val tappable = !last && canGo(target)
            if (i > 0) BasicText(PathCrumbs.SEP, maxLines = 1, style = TextStyle(color = dim, fontSize = 12.sp))
            Box(Modifier.weight(1f, fill = false).then(if (tappable) Modifier.clickable { onGo(target) } else Modifier)) {
                BasicText(
                    c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = if (last) CodeColors.TextPrimary else dim, fontSize = 12.sp,
                        fontWeight = if (last) FontWeight.Bold else FontWeight.Normal, fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
    }
}

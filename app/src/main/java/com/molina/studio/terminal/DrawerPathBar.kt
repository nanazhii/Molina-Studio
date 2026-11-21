package com.molina.studio.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.core.files.PathCrumbs
import com.molina.studio.ui.MolinaTheme
import java.io.File

/**
 * [DRAWER-CRUMBS] Kotak path berbingkai di header drawer: "Internal > Molina". Segmen yang boleh dituju bisa diketuk
 * (folder utama pindah ke sana); segmen terakhir = folder saat ini. Logika segmen: PathCrumbs (:core:files).
 */
@Composable
internal fun DrawerPathBar(path: String, canGo: (File) -> Boolean, onGo: (File) -> Unit, dim: Color, modifier: Modifier = Modifier) {
    val tail = PathCrumbs.tail(path)
    Row(
        modifier.height(30.dp).background(Color(0xFF101010)).border(1.dp, Color(0xFF3A3A3A)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (tail.truncated) Text("\u2026" + PathCrumbs.SEP, color = dim, fontSize = 12.sp, maxLines = 1)
        tail.crumbs.forEachIndexed { i, c ->
            val last = i == tail.crumbs.lastIndex
            val target = File(c.path)
            val tappable = !last && canGo(target)
            if (i > 0) Text(PathCrumbs.SEP, color = dim, fontSize = 12.sp, maxLines = 1)
            Box(Modifier.weight(1f, fill = false).then(if (tappable) Modifier.clickable { onGo(target) } else Modifier)) {
                Text(
                    c.label, color = if (last) MolinaTheme.OnBackground else dim,
                    fontSize = 12.sp, fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

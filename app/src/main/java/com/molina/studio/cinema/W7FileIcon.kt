package com.molina.studio.cinema

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.molina.studio.ui.MolinaTheme

/** [FILES-ICONS] Bentuk lencana untuk media; jenis lain memakai label singkat (PY, XML, ZIP, ...). */
internal enum class W7Shape { NONE, IMAGE, VIDEO, AUDIO }

/** Warna + label/bentuk per ekstensi. Ekstensi tak dikenal: emas merek dengan 3 huruf pertama ekstensi. */
internal class W7Glyph(val color: Color, val label: String?, val shape: W7Shape = W7Shape.NONE) {
    companion object {
        private val MAP = HashMap<String, W7Glyph>().also { m ->
            fun put(exts: String, g: W7Glyph) {
                for (e in exts.split(" ")) m[e] = g
            }
            put("jpg jpeg png gif webp bmp heic heif ico avif", W7Glyph(Color(0xFF4DD0A1), null, W7Shape.IMAGE))
            put("mp4 mkv webm avi mov m4v 3gp ts m2ts mts flv wmv mpg mpeg ogv vob rmvb", W7Glyph(MolinaTheme.Cinema, null, W7Shape.VIDEO))
            put("mp3 m4a aac flac wav ogg oga opus wma ac3 dts mka", W7Glyph(Color(0xFFFF80AB), null, W7Shape.AUDIO))
            put("py pyw", W7Glyph(Color(0xFFFFD54F), "PY"))
            put("xml html htm xhtml plist", W7Glyph(Color(0xFFFF8A65), "XML"))
            put("svg", W7Glyph(Color(0xFFFFA726), "SVG"))
            put("json", W7Glyph(Color(0xFF4FC3F7), "{}"))
            put("yml yaml", W7Glyph(Color(0xFF4FC3F7), "YML"))
            put("ini conf cfg toml properties env", W7Glyph(Color(0xFF80DEEA), "CFG"))
            put("kt kts", W7Glyph(Color(0xFF7E8CFF), "KT"))
            put("java", W7Glyph(Color(0xFFE57373), "JV"))
            put("gradle", W7Glyph(Color(0xFF81C784), "GRD"))
            put("c h", W7Glyph(Color(0xFF64B5F6), "C"))
            put("cpp cc cxx hpp", W7Glyph(Color(0xFF64B5F6), "C++"))
            put("js mjs", W7Glyph(Color(0xFFFFEE58), "JS"))
            put("sh bash zsh", W7Glyph(Color(0xFF9CCC65), "SH"))
            put("md markdown", W7Glyph(Color(0xFFB0BEC5), "MD"))
            put("txt", W7Glyph(Color(0xFF90A4AE), "TXT"))
            put("log", W7Glyph(Color(0xFF90A4AE), "LOG"))
            put("csv", W7Glyph(Color(0xFF66BB6A), "CSV"))
            put("zip", W7Glyph(Color(0xFFFFB74D), "ZIP"))
            put("7z", W7Glyph(MolinaTheme.Gold, "7Z")) // [ARCHIVE] jenis arsip langsung terbaca di daftar
            put("tar", W7Glyph(MolinaTheme.Gold, "TAR"))
            put("gz tgz", W7Glyph(MolinaTheme.Gold, "GZ"))
            put("bz2 tbz2 tbz", W7Glyph(MolinaTheme.Gold, "BZ2"))
            put("xz txz", W7Glyph(MolinaTheme.Gold, "XZ"))
            put("rar", W7Glyph(MolinaTheme.Gold, "RAR"))
            put("iso", W7Glyph(MolinaTheme.Gold, "ISO"))
            put("pdf", W7Glyph(Color(0xFFEF5350), "PDF"))
            put("doc docx odt rtf", W7Glyph(Color(0xFF42A5F5), "DOC"))
            put("xls xlsx ods", W7Glyph(Color(0xFF66BB6A), "XLS"))
            put("ppt pptx odp", W7Glyph(Color(0xFFFF7043), "PPT"))
            put("apk aab xapk", W7Glyph(Color(0xFF81C784), "APK"))
            put("srt ass ssa vtt sub", W7Glyph(Color(0xFFFFCC80), "SUB"))
            put("m3u m3u8 pls cue", W7Glyph(MolinaTheme.Cinema, "LST"))
        }

        fun of(ext: String): W7Glyph {
            val e = ext.lowercase()
            return MAP[e] ?: W7Glyph(MolinaTheme.Gold, if (e.isEmpty()) null else e.uppercase().take(3))
        }
    }
}

/** Ikon berkas per ekstensi: halaman gelap beraksen warna jenisnya + lencana (gambar/video/audio) atau label. */
@Composable
internal fun W7FileIcon(ext: String, box: Dp) {
    val g = remember(ext) { W7Glyph.of(ext) }
    Box(Modifier.size(box)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val page = Path().apply {
                moveTo(w * 0.18f, h * 0.06f); lineTo(w * 0.62f, h * 0.06f); lineTo(w * 0.84f, h * 0.28f)
                lineTo(w * 0.84f, h * 0.94f); lineTo(w * 0.18f, h * 0.94f); close()
            }
            drawPath(page, Color(0xFF1C1C1C))
            drawPath(page, g.color.copy(alpha = 0.9f), style = Stroke(width = 1.4f))
            val fold = Path().apply {
                moveTo(w * 0.62f, h * 0.06f); lineTo(w * 0.62f, h * 0.28f); lineTo(w * 0.84f, h * 0.28f); close()
            }
            drawPath(fold, g.color.copy(alpha = 0.45f))
            when (g.shape) {
                W7Shape.VIDEO -> {
                    val badge = Path().apply {
                        moveTo(w * 0.36f, h * 0.46f); lineTo(w * 0.36f, h * 0.80f); lineTo(w * 0.68f, h * 0.63f); close()
                    }
                    drawPath(badge, g.color)
                }
                W7Shape.AUDIO -> {
                    drawCircle(g.color, w * 0.09f, Offset(w * 0.40f, h * 0.76f))
                    drawRect(g.color, Offset(w * 0.46f, h * 0.44f), Size(w * 0.05f, h * 0.34f))
                    drawRect(g.color, Offset(w * 0.46f, h * 0.44f), Size(w * 0.20f, h * 0.07f))
                }
                W7Shape.IMAGE -> {
                    val hill = Path().apply {
                        moveTo(w * 0.26f, h * 0.84f); lineTo(w * 0.44f, h * 0.56f); lineTo(w * 0.55f, h * 0.72f)
                        lineTo(w * 0.62f, h * 0.62f); lineTo(w * 0.76f, h * 0.84f); close()
                    }
                    drawPath(hill, g.color)
                    drawCircle(g.color, w * 0.07f, Offset(w * 0.38f, h * 0.42f))
                }
                W7Shape.NONE -> Unit
            }
        }
        val label = g.label
        if (label != null) {
            Box(
                Modifier.fillMaxSize().padding(start = box * 0.17f, end = box * 0.15f, bottom = box * 0.09f),
                Alignment.BottomCenter
            ) {
                Text(label, color = g.color, fontSize = (box.value * 0.29f).sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
    }
}

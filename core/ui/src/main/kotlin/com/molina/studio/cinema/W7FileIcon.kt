package com.molina.studio.cinema

import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.molina.studio.ui.MolinaTheme

/** [FILES-ICONS] Bentuk lencana untuk media; jenis lain memakai label singkat (PY, XML, ZIP, ...). */
enum class W7Shape { NONE, IMAGE, VIDEO, AUDIO, ARCHIVE } // [ICON-WIN] ARCHIVE = ritsleting

/** Warna + label/bentuk per ekstensi. Ekstensi tak dikenal: emas merek dengan 3 huruf pertama ekstensi. */
class W7Glyph(val color: Color, val label: String?, val shape: W7Shape = W7Shape.NONE) {
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
            put("zip", W7Glyph(Color(0xFFFFB74D), "ZIP", W7Shape.ARCHIVE))
            put("7z", W7Glyph(MolinaTheme.Brand, "7Z", W7Shape.ARCHIVE)) // [ARCHIVE] jenis arsip langsung terbaca di daftar
            put("tar", W7Glyph(MolinaTheme.Brand, "TAR", W7Shape.ARCHIVE))
            put("gz tgz", W7Glyph(MolinaTheme.Brand, "GZ", W7Shape.ARCHIVE))
            put("bz2 tbz2 tbz", W7Glyph(MolinaTheme.Brand, "BZ2", W7Shape.ARCHIVE))
            put("xz txz", W7Glyph(MolinaTheme.Brand, "XZ", W7Shape.ARCHIVE))
            put("rar", W7Glyph(MolinaTheme.Brand, "RAR", W7Shape.ARCHIVE))
            put("iso", W7Glyph(MolinaTheme.Brand, "ISO", W7Shape.ARCHIVE))
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
            return MAP[e] ?: W7Glyph(MolinaTheme.Brand, if (e.isEmpty()) null else e.uppercase().take(3))
        }
    }
}

/**
 * [ICON-WIN] Ikon berkas bergaya Windows: halaman terang berlipat sudut dengan pita warna jenis berkas + label
 * (dokumen/kode/arsip), langit-bukit untuk gambar, strip film untuk video, not musik untuk audio, ritsleting untuk arsip.
 */
@Composable
fun W7FileIcon(ext: String, box: Dp) {
    val g = remember(ext) { W7Glyph.of(ext) }
    val ink = if (g.color.luminance() > 0.6f) Color(0xFF263238) else Color.White
    Box(Modifier.size(box)) {
        // [PERF-ICON] Path/Brush/Stroke dibuat sekali per ukuran (drawWithCache), bukan di setiap penggambaran ulang baris.
        Box(Modifier.fillMaxSize().drawWithCache {
            val w = size.width
            val h = size.height
            val line = (w * 0.045f).coerceAtLeast(1.2f)
            val stroke = Stroke(width = line)
            val page = Path().apply {
                moveTo(w * 0.18f, h * 0.06f); lineTo(w * 0.62f, h * 0.06f); lineTo(w * 0.84f, h * 0.28f)
                lineTo(w * 0.84f, h * 0.94f); lineTo(w * 0.18f, h * 0.94f); close()
            }
            val fold = Path().apply {
                moveTo(w * 0.62f, h * 0.06f); lineTo(w * 0.62f, h * 0.28f); lineTo(w * 0.84f, h * 0.28f); close()
            }
            val dark = g.shape == W7Shape.VIDEO
            val bodyBrush = Brush.verticalGradient(
                when (g.shape) {
                    W7Shape.IMAGE -> listOf(Color(0xFFBFE6FF), Color(0xFFEAF7FF))
                    W7Shape.VIDEO -> listOf(Color(0xFF37474F), Color(0xFF1C262B))
                    W7Shape.AUDIO -> listOf(Color(0xFFFFFFFF), g.color.copy(alpha = 0.35f))
                    else -> listOf(Color(0xFFFFFFFF), Color(0xFFDDE1E6))
                }
            )
            val back = if (g.shape == W7Shape.IMAGE) Path().apply {
                moveTo(w * 0.18f, h * 0.94f); lineTo(w * 0.18f, h * 0.76f); lineTo(w * 0.40f, h * 0.54f)
                lineTo(w * 0.62f, h * 0.76f); lineTo(w * 0.74f, h * 0.64f); lineTo(w * 0.84f, h * 0.74f)
                lineTo(w * 0.84f, h * 0.94f); close()
            } else null
            val front = if (g.shape == W7Shape.IMAGE) Path().apply {
                moveTo(w * 0.18f, h * 0.94f); lineTo(w * 0.18f, h * 0.84f); lineTo(w * 0.34f, h * 0.70f)
                lineTo(w * 0.52f, h * 0.88f); lineTo(w * 0.60f, h * 0.80f); lineTo(w * 0.72f, h * 0.94f); close()
            } else null
            val play = if (g.shape == W7Shape.VIDEO) Path().apply {
                moveTo(w * 0.36f, h * 0.36f); lineTo(w * 0.36f, h * 0.78f); lineTo(w * 0.68f, h * 0.57f); close()
            } else null
            onDrawBehind {
                drawPath(page, bodyBrush)
                when (g.shape) {
                    W7Shape.IMAGE -> {
                        drawCircle(Color(0xFFFFC107), w * 0.07f, Offset(w * 0.38f, h * 0.38f))
                        back?.let { drawPath(it, Color(0xFF66BB6A)) }
                        front?.let { drawPath(it, Color(0xFF2E7D32)) }
                    }
                    W7Shape.VIDEO -> {
                        for (i in 0 until 5) {
                            drawRect(Color(0xFFB0BEC5), Offset(w * 0.21f, h * (0.12f + i * 0.17f)), Size(w * 0.05f, h * 0.08f))
                            if (i >= 2) drawRect(Color(0xFFB0BEC5), Offset(w * 0.76f, h * (0.12f + i * 0.17f)), Size(w * 0.05f, h * 0.08f))
                        }
                        play?.let { drawPath(it, g.color) }
                    }
                    W7Shape.AUDIO -> {
                        drawCircle(g.color, w * 0.10f, Offset(w * 0.42f, h * 0.74f))
                        drawRect(g.color, Offset(w * 0.49f, h * 0.34f), Size(w * 0.05f, h * 0.40f))
                        drawRect(g.color, Offset(w * 0.49f, h * 0.34f), Size(w * 0.20f, h * 0.09f))
                    }
                    W7Shape.ARCHIVE -> {
                        for (i in 0 until 6) {
                            drawRect(Color(0xFF6B7280), Offset(w * (if (i % 2 == 0) 0.45f else 0.51f), h * (0.07f + i * 0.075f)), Size(w * 0.05f, h * 0.045f))
                        }
                    }
                    W7Shape.NONE -> if (g.label == null) {
                        for (k in 0 until 4) drawRect(Color(0xFFB8BFC8), Offset(w * 0.26f, h * (0.38f + k * 0.12f)), Size(w * (0.50f - (k % 2) * 0.12f), h * 0.04f))
                    } else {
                        for (k in 0 until 2) drawRect(Color(0xFFB8BFC8), Offset(w * 0.26f, h * (0.34f + k * 0.09f)), Size(w * (0.48f - k * 0.14f), h * 0.035f))
                    }
                }
                if (g.label != null) drawRect(g.color, Offset(w * 0.18f, h * 0.54f), Size(w * 0.66f, h * 0.30f))
                drawPath(page, if (dark) g.color else Color(0xFF8A929C), style = stroke)
                drawPath(fold, if (dark) Color(0xFF5C6B73) else Color(0xFFC5CBD3))
                drawPath(fold, if (dark) g.color else Color(0xFF8A929C), style = stroke)
            }
        })
        val label = g.label
        if (label != null) {
            Box(
                Modifier.fillMaxSize().padding(top = box * 0.54f, bottom = box * 0.16f, start = box * 0.18f, end = box * 0.16f),
                Alignment.Center
            ) {
                Text(label, color = ink, fontSize = (box.value * 0.25f).sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
    }
}

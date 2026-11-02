package com.molina.studio.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.cinema.Win7

internal enum class RunState { Running, Ok, Failed, Stopped }

private val APT_TOTAL = Regex("""(\d+) upgraded, (\d+) newly installed""")
private val PERCENT = Regex("""(\d{1,3}(?:\.\d+)?)%""")

/**
 * Membaca keluaran skrip (apt/dpkg/yt-dlp) per baris dan menurunkan fase + fraksi progres.
 * Fraksi apt = (baris "Unpacking" + "Setting up") / (2 x total paket); bila tidak ada total tetapi ada
 * "NN%" (yt-dlp, git) memakai persen itu; selain itu null (bilah indeterminate).
 */
internal class RunProgressTracker {
    var phase: String = "Menyiapkan\u2026"
        private set
    var fraction: Float? = null
        private set
    private var total = 0
    private var steps = 0
    private val pending = StringBuilder()

    fun feed(text: String) {
        pending.append(text.replace("\r", "\n"))
        var i = pending.indexOf("\n")
        while (i >= 0) {
            val line = pending.substring(0, i).trim()
            pending.delete(0, i + 1)
            if (line.isNotEmpty()) line(line)
            i = pending.indexOf("\n")
        }
        if (pending.length > 4000) pending.clear()
    }

    private fun line(l: String) {
        APT_TOTAL.find(l)?.let { m ->
            total = m.groupValues[1].toInt() + m.groupValues[2].toInt()
        }
        when {
            l.startsWith("Hit:") || l.startsWith("Ign:") -> phase = "Memeriksa sumber paket"
            l.startsWith("Get:") -> phase = if (total == 0) "Memeriksa sumber paket" else "Mengunduh paket"
            l.startsWith("Reading package lists") -> phase = "Membaca daftar paket"
            l.startsWith("Building dependency tree") -> phase = "Menghitung dependensi"
            l.startsWith("Unpacking ") -> { steps++; phase = "Membongkar " + word(l, 1) }
            l.startsWith("Setting up ") -> { steps++; phase = "Mengonfigurasi " + word(l, 2) }
            l.startsWith("Processing triggers") -> phase = "Memproses pemicu"
            l.startsWith("[download]") -> phase = "Mengunduh"
        }
        if (total > 0) {
            fraction = (steps / (2f * total)).coerceIn(0f, 0.99f)
        } else {
            PERCENT.findAll(l).lastOrNull()?.let { m ->
                m.groupValues[1].toFloatOrNull()?.let { fraction = (it / 100f).coerceIn(0f, 0.99f) }
            }
        }
    }

    private fun word(l: String, idx: Int): String = l.split(' ').getOrNull(idx).orEmpty()
}

/** Panel progres ala Windows 7: fase, persen, bilah glossy berwarna sesuai status, lalu ringkasan status. */
@Composable
internal fun RunProgressPanel(phase: String, fraction: Float?, state: RunState, status: String, elapsedSec: Int = 0) {
    val (top, bottom, accent) = when (state) {
        RunState.Running -> Triple(Color(0xFF8FC4EE), Color(0xFF2D6DB5), Color(0xFF3C7FB1))
        RunState.Ok -> Triple(Color(0xFF7BE87A), Color(0xFF1E9E1A), Color(0xFF1E9E1A))
        RunState.Failed -> Triple(Color(0xFFFF8A80), Color(0xFFC4392B), Color(0xFFC4392B))
        RunState.Stopped -> Triple(Color(0xFFFFE680), Color(0xFFE0A800), Color(0xFFE0A800))
    }
    val bar = if (state == RunState.Running) fraction else 1f
    Column(Modifier.fillMaxWidth().win7Card(accent).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (state == RunState.Running) phase else status.lineSequence().firstOrNull().orEmpty(),
                color = Win7.Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (state == RunState.Running) {
                val pct = if (fraction != null) "${(fraction * 100).toInt()}% \u00B7 " else ""
                val clock = "${elapsedSec / 60}:${(elapsedSec % 60).toString().padStart(2, '0')}"
                Text(pct + clock, color = Win7.NavHeader, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(8.dp))
        GlossBar(bar, top, bottom, height = 16.dp)
        val extra = status.lineSequence().drop(1).joinToString("\n")
        if (state != RunState.Running && extra.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(extra, color = Win7.TextDim, fontSize = 12.sp)
        }
    }
}

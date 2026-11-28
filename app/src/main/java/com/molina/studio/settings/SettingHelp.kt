package com.molina.studio.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.ui.MolinaTheme
import com.molina.studio.ui.components.ActionChip
import com.molina.studio.ui.components.InfoIconButton
import com.molina.studio.ui.components.MolinaAlertDialog
import com.molina.studio.ui.components.PackageGroupCard
import com.molina.studio.ui.components.PackageSectionHeader

/**
 * [INFO-SCREEN] [INFO-SECTIONS] [INFO-DIALOG] Keterangan sebuah setelan lewat ikon (i).
 * Bagian yang null tidak ditampilkan. Dua cara tampil:
 *  - pola utama: `SettingHelp.show(HelpKey.X)`, teksnya di SettingHelpCatalog.kt;
 *  - keterangan dinamis, halaman Keterangan: `SettingHelp.show(HelpTopic("Judul", what = "..."))` (dari halaman Setelan);
 *  - dialog tumpuk: `HelpDialog(topic) { ... }` (dari dalam dialog lain, supaya dialog asal tidak hilang).
 *
 *  - what         -> "Apa ini"
 *  - ifOff        -> "Efek jika dimatikan"
 *  - whenApplies  -> "Kapan berlaku"
 *  - note         -> "Catatan" (batasan atau peringatan)
 */
internal class HelpTopic(
    val title: String,
    val what: String,
    val ifOff: String? = null,
    val whenApplies: String? = null,
    val note: String? = null,
    val icon: (@Composable () -> Unit)? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

internal object SettingHelp {
    var topic by mutableStateOf<HelpTopic?>(null)
        private set
    /** Dibaca SettingsScreen lalu dikembalikan ke false setelah layar keterangan dibuka. */
    var requested by mutableStateOf(false)

    fun show(t: HelpTopic) {
        topic = t
        requested = true
    }

    /** [INFO-CATALOG] Tampilkan keterangan dari katalog pusat. */
    fun show(key: HelpKey): Unit = show(SettingHelpCatalog.of(key))
}

@Composable
private fun HelpSection(label: String, text: String) {
    Text(
        label.uppercase(), color = MolinaTheme.InfoAccent, fontSize = 11.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp
    )
    Spacer(Modifier.height(4.dp))
    text.split("\n\n").forEachIndexed { i, para ->
        if (i > 0) Spacer(Modifier.height(8.dp))
        Text(para, color = MolinaTheme.Explorer.Ink, fontSize = 14.sp, lineHeight = 21.sp)
    }
}

/** Isi keterangan (bagian-bagian + tombol aksi opsional), dipakai halaman dan dialog. */
@Composable
private fun HelpSections(t: HelpTopic) {
    Column(Modifier.fillMaxWidth()) {
        val sections = listOfNotNull(
            "Apa ini" to t.what,
            t.ifOff?.let { "Efek jika dimatikan" to it },
            t.whenApplies?.let { "Kapan berlaku" to it },
            t.note?.let { "Catatan" to it },
        )
        sections.forEachIndexed { i, (label, text) ->
            if (i > 0) Spacer(Modifier.height(16.dp))
            HelpSection(label, text)
        }
        if (t.actionLabel != null && t.onAction != null) {
            Spacer(Modifier.height(18.dp))
            ActionChip(t.actionLabel, MolinaTheme.Explorer.Ink, t.onAction)
        }
    }
}

@Composable
internal fun SettingHelpPage(onBack: () -> Unit) {
    val t = SettingHelp.topic
    if (t == null) { // topik hilang (mis. proses dibuat ulang): kembali ke halaman asal
        LaunchedEffect(Unit) { onBack() }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val ic = t.icon
        if (ic != null) {
            ic()
            Spacer(Modifier.width(10.dp))
        }
        PackageSectionHeader(t.title)
    }
    PackageGroupCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) { HelpSections(t) }
    }
}

/** [INFO-DIALOG] Keterangan sebagai dialog tumpuk. Aman dipakai di atas dialog yang sedang berjalan. */
@Composable
internal fun HelpDialog(t: HelpTopic, onDismiss: () -> Unit) {
    MolinaAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ic = t.icon
                if (ic != null) {
                    ic()
                    Spacer(Modifier.width(10.dp))
                }
                Text(t.title)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { HelpSections(t) }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
}

/** [INFO-DIALOG] Judul dialog dengan ikon (i) di kanan; tanpa ikon bila [info] null. */
@Composable
internal fun InfoTitle(text: String, info: HelpTopic?, onInfo: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f))
        if (info != null) InfoIconButton(onInfo)
    }
}

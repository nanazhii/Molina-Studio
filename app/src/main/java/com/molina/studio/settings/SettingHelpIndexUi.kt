package com.molina.studio.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molina.studio.ui.MolinaTheme
import com.molina.studio.ui.components.InfoIconButton
import com.molina.studio.ui.components.PackageDivider
import com.molina.studio.ui.components.PackageGroupCard
import com.molina.studio.ui.components.PackageSectionHeader
import com.molina.studio.ui.components.highlightMatch

/** [INFO-CENTRAL] Urutan kelompok mengikuti menu Settings: Paket, lalu Pemutar, sisanya menyusul. */
private val HELP_GROUP_ORDER = listOf("Paket", "Pemutar")

/**
 * [INFO-INDEX] Sistem > Info > Keterangan setelan: semua keterangan (i) dari [HelpKey.entries] di satu tempat,
 * dikelompokkan per [HelpKey.group] dan bisa dicari (judul, isi, dan kelompok). Ketuk baris = buka keterangannya;
 * Kembali pulang ke halaman ini. [query] disimpan oleh pemanggil supaya tidak hilang saat keterangan dibuka.
 */
@Composable
internal fun HelpIndexPage(query: String, onQuery: (String) -> Unit) {
    val all = remember { HelpKey.entries.map { it to it.topic } }
    val ctx = androidx.compose.ui.platform.LocalContext.current // [INFO-HISTORY]
    val q = query.trim()
    val shown = if (q.isEmpty()) all else all.filter { (k, _) -> k.matches(q) }
    HelpSearchField(query, onQuery)
    Spacer(Modifier.height(8.dp))
    Text(
        if (q.isEmpty()) "${all.size} keterangan" else "${shown.size} dari ${all.size} keterangan",
        color = MolinaTheme.Explorer.TextDim, fontSize = 11.sp
    )
    Spacer(Modifier.height(8.dp))
    if (q.isEmpty()) RecentSearches(onQuery) // [INFO-HISTORY]
    if (shown.isEmpty()) {
        Text("Tidak ada keterangan untuk \"$q\"", color = MolinaTheme.Explorer.Ink, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp)) // [INFO-EMPTY]
        SearchSuggestionChips(onQuery)
    } else {
        shown.groupBy { it.first.group }.entries
            .sortedBy { e -> HELP_GROUP_ORDER.indexOf(e.key).let { i -> if (i < 0) HELP_GROUP_ORDER.size else i } }
            .forEachIndexed { gi, (group, rows) ->
            if (gi > 0) Spacer(Modifier.height(16.dp))
            HelpGroupHeader(group) // [INFO-IKON-GRUP]
            PackageGroupCard {
                rows.forEachIndexed { i, (key, t) ->
                    if (i > 0) PackageDivider()
                    HelpIndexRow(t.title, t.what.substringBefore("\n\n"), q) { SearchHistory.add(ctx, q); SettingHelp.show(key) }
                }
            }
        }
    }
}

/** [INFO-IKON-WARNA] Header kelompok + lencana 22dp beraksen per kategori; kelompok tanpa gaya tampil tanpa ikon. */
@Composable
private fun HelpGroupHeader(group: String) {
    val style = groupStyle(group)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (style != null) {
            Box(Modifier.padding(end = 8.dp, bottom = 4.dp).size(HELP_ICON_BOX), Alignment.Center) { GroupBadge(style) }
        }
        Box(Modifier.weight(1f)) { PackageSectionHeader(group) }
    }
}

private val HELP_ICON_BOX = 22.dp

private class GroupStyle(val color: Color, val label: String)

/**
 * [INFO-IKON-WARNA] Kunci = HelpKey.group; warna hanya dari token [MolinaTheme] (tanpa literal baru).
 * Keluarga Terminal (I/O, View, Tampilan) memakai Brand zamrud; Cinema ungu untuk Pemutar; sisanya dibedakan satu per satu.
 */
private fun groupStyle(group: String): GroupStyle? = when (group) {
    "Paket" -> GroupStyle(MolinaTheme.Link, "pkg")
    "Pemutar" -> GroupStyle(MolinaTheme.Cinema, "mpv")
    "Debugging dan Log" -> GroupStyle(MolinaTheme.Warn, "dbg")
    "Editor Code" -> GroupStyle(MolinaTheme.AeroBlueBottom, "</>")
    "Jaringan dan Keamanan" -> GroupStyle(MolinaTheme.Bad, "net")
    "Monitor Sistem" -> GroupStyle(MolinaTheme.Mint, "mon")
    "Otomasi" -> GroupStyle(MolinaTheme.FolderYellow, "run")
    "Pembersihan dan Diagnostik" -> GroupStyle(MolinaTheme.Muted, "bug")
    "Tampilan Molina" -> GroupStyle(MolinaTheme.Brand, "Aa")
    "Terminal I/O" -> GroupStyle(MolinaTheme.Brand, "I/O")
    "Terminal View" -> GroupStyle(MolinaTheme.Brand, ">_")
    else -> null
}

/** Lencana gradien ringan; teks gelap di atas warna terang (Warn, Mint, FolderYellow) agar tetap terbaca. */
@Composable
private fun GroupBadge(style: GroupStyle) {
    val shape = RoundedCornerShape(5.dp)
    val fg = if (style.color.luminance() > 0.4f) Color(0xFF101010) else Color.White
    Box(
        Modifier.size(HELP_ICON_BOX).clip(shape)
            .background(Brush.verticalGradient(listOf(lerp(style.color, Color.Black, 0.1f), lerp(style.color, Color.Black, 0.4f))), shape)
            .border(1.dp, Color(0x55000000), shape),
        Alignment.Center
    ) {
        Text(style.label, color = fg, fontSize = if (style.label.length > 2) 8.sp else 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun HelpIndexRow(title: String, summary: String, highlight: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                highlightMatch(title, highlight), color = MolinaTheme.Explorer.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                highlightMatch(summary, highlight), color = MolinaTheme.Explorer.TextDim, fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        InfoIconButton(onClick) // token InfoAccent yang sama di semua halaman
    }
}

@Composable
private fun HelpSearchField(value: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = MolinaTheme.Explorer.Ink, fontSize = 14.sp),
        cursorBrush = SolidColor(MolinaTheme.InfoAccent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text("Cari keterangan setelan", color = MolinaTheme.Explorer.TextDim, fontSize = 14.sp)
                    }
                    inner()
                }
                if (value.isNotEmpty()) {
                    Text(
                        "\u2715", color = MolinaTheme.Explorer.TextDim, fontSize = 14.sp,
                        modifier = Modifier.clip(CircleShape).clickable { onChange("") }.padding(6.dp)
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clip(shape).background(MolinaTheme.Explorer.Card, shape)
            .border(1.dp, MolinaTheme.Explorer.Hairline, shape).padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

/** [INFO-INDEX] Satu aturan pencarian untuk halaman Keterangan setelan dan pencarian global Settings. */
internal fun HelpKey.matches(q: String): Boolean {
    val t = topic
    return listOfNotNull(t.title, t.what, t.ifOff, t.whenApplies, t.note, group).any { it.looseContains(q) }
}

/**
 * [INFO-INDEX] Cocok biasa tanpa peduli huruf besar-kecil, atau cocok setelah tanda baca dan spasi dibuang di kedua sisi
 * ("rtmps" menemukan "rtmp(s)", "ytdlp" menemukan "yt-dlp").
 */
internal fun String.looseContains(q: String): Boolean {
    if (contains(q, ignoreCase = true)) return true
    val cq = q.compactForSearch()
    return cq.isNotEmpty() && compactForSearch().contains(cq)
}

private fun String.compactForSearch(): String = buildString {
    for (c in this@compactForSearch) if (c.isLetterOrDigit()) append(c.lowercaseChar())
}

/** [INFO-EMPTY] Kata kunci yang pasti punya hasil, ditawarkan saat pencarian kosong. */
private val SUGGESTED_QUERIES = listOf("yt-dlp", "Wi-Fi", "m3u")

/** [INFO-HISTORY] Baris chip yang bisa digeser mendatar; ketuk = isi kolom cari. */
@Composable
private fun ChipRow(words: List<String>, onPick: (String) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        words.forEach { word ->
            Text(
                word, color = MolinaTheme.InfoAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.clip(shape).border(1.dp, MolinaTheme.InfoAccent, shape)
                    .clickable { onPick(word) }.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/** [INFO-HISTORY] Pencarian terakhir + tombol Hapus; tidak menampilkan apa pun bila riwayat kosong. */
@Composable
internal fun RecentSearches(onPick: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var items by remember { mutableStateOf(SearchHistory.load(ctx)) }
    if (items.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pencarian terakhir", color = MolinaTheme.Explorer.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                "Hapus", color = MolinaTheme.InfoAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable { SearchHistory.clear(ctx); items = emptyList() }.padding(6.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        ChipRow(items, onPick)
        Spacer(Modifier.height(12.dp))
    }
}

/** [INFO-EMPTY] Chip InfoAccent: riwayat dulu, lalu kata kunci yang pasti punya hasil. */
@Composable
internal fun SearchSuggestionChips(onPick: (String) -> Unit) {
    RecentSearches(onPick)
    Text("Coba kata kunci ini:", color = MolinaTheme.Explorer.TextDim, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))
    ChipRow(SUGGESTED_QUERIES, onPick)
}

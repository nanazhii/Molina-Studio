package com.molina.studio.cinema

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.molina.core.policy.ExternalIntentGate
import com.molina.core.net.IptvChannel
import com.molina.core.net.IptvPlayback
import com.molina.core.net.IptvTrust // [IPTV-TRUST-BLOCK]
import com.molina.core.stream.PlaylistExtras
import com.molina.core.stream.PlaylistPlan
import com.molina.core.stream.StreamSearchItem
import com.molina.studio.security.ShortcutToken
import com.molina.cinema.MpvFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Peluncur Molina Cinema (MPVActivity) dan validasi input. Input pengguna dianggap tidak tepercaya. */
internal object CinemaActions {
    /**
     * Putar dari Penjelajah: satu berkas, beberapa berkas (playlist memory://), atau satu folder
     * (playlist folder). Pemindaian folder berjalan di IO; peluncuran activity di thread pemanggil.
     */
    suspend fun playFiles(ctx: Context, files: List<File>): Boolean {
        val dir = files.singleOrNull()?.takeIf { it.isDirectory }
        return when {
            dir != null -> {
                val first = withContext(Dispatchers.IO) { MpvFolder.firstMediaIn(dir) } ?: return false
                playFolder(ctx, first)
            }
            files.size == 1 -> playPath(ctx, files[0].absolutePath)
            files.isNotEmpty() -> {
                val uri = withContext(Dispatchers.IO) { MpvFolder.memoryPlaylist(files.map { it.absolutePath }) } ?: return false
                playTree(ctx, uri)
            }
            else -> false
        }
    }

    private const val PLAYER = "com.molina.cinema.MPVActivity"
    private const val SETTINGS = "com.molina.cinema.preferences.PreferenceActivity"
    private val SCHEMES = setOf("http", "https", "rtsp", "rtmp", "rtmps")
    private val BARE_HOSTS = listOf("www.", "youtu.be/", "youtube.com/", "m.youtube.com/", "music.youtube.com/")

    sealed interface Input {
        class Direct(val uri: Uri) : Input
        /** Bukan URL: diperlakukan sebagai kata kunci pencarian judul. */
        object Query : Input
        /** Mengandung "://" tetapi skema/host tidak didukung. */
        object Invalid : Input
        object Empty : Input
    }

    /** Tambahkan https:// pada tautan tanpa skema yang jelas berupa host web (www., youtu.be, dst.). */
    fun normalize(raw: String): String {
        val t = raw.trim()
        if (t.contains("://")) return t
        return if (BARE_HOSTS.any { t.startsWith(it, ignoreCase = true) }) "https://$t" else t
    }

    fun classify(raw: String): Input {
        val t = normalize(raw)
        if (t.isEmpty()) return Input.Empty
        val u = runCatching { Uri.parse(t) }.getOrNull()
        val scheme = u?.scheme?.lowercase()
        val host = u?.host?.lowercase().orEmpty()
        if (u != null && scheme in SCHEMES && host.isNotEmpty()) return Input.Direct(u)
        return if (t.contains("://")) Input.Invalid else Input.Query
    }

    fun play(ctx: Context, uri: Uri): Boolean {
        val ok = start(
            ctx,
            Intent(Intent.ACTION_VIEW, uri)
                .setClassName(ctx.packageName, PLAYER)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        if (ok) { CinemaRecent.add(ctx, uri.toString(), null); SeenStore.markPlayed(ctx, uri.toString()) } // [SEEN-BADGES]
        return ok
    }

    /**
     * Putar hasil yang diketuk dan jadikan hasil lain sebagai playlist (urutan sama dengan daftar).
     * Bila kurang dari 2 hasil valid, jatuh ke pemutaran tunggal.
     */
    fun playResults(ctx: Context, items: List<StreamSearchItem>, picked: StreamSearchItem): Boolean {
        CinemaRecent.add(ctx, picked.pageUrl, picked.title)
        SeenStore.markPlayed(ctx, picked.pageUrl) // [SEEN-BADGES]
        val list = items.filter { PlaylistPlan.isWatchUrl(it.pageUrl) }.take(PlaylistPlan.MAX_ITEMS)
        val idx = list.indexOfFirst { it.pageUrl == picked.pageUrl }
        val uri = Uri.parse(picked.pageUrl)
        if (idx < 0 || list.size < 2) return play(ctx, uri)
        CinemaRecent.saveSnapshot(ctx, picked.pageUrl, "res", list.map { it.pageUrl }, list.map { it.title }) // [RECENT-PLAYLIST]
        return start(
            ctx,
            Intent(Intent.ACTION_VIEW, uri)
                .setClassName(ctx.packageName, PLAYER)
                .putStringArrayListExtra(PlaylistExtras.URLS, ArrayList(list.map { it.pageUrl }))
                .putStringArrayListExtra(PlaylistExtras.TITLES, ArrayList(list.map { it.title }))
                .putExtra(PlaylistExtras.INDEX, idx)
        )
    }

    /** Putar berkas lokal lewat extra "filepath" (tanpa file://). Validasi path ada di MPVActivity. */
    fun playPath(ctx: Context, path: String): Boolean {
        val ok = start(ctx, Intent().setClassName(ctx.packageName, PLAYER).putExtra("filepath", path))
        if (ok) CinemaRecent.add(ctx, path, null)
        return ok
    }

    /** Putar folder sebagai playlist: [firstPath] = berkas media pertama (MpvFolder.firstMediaIn). */
    fun playFolder(ctx: Context, firstPath: String): Boolean = start(
        ctx,
        Intent().setClassName(ctx.packageName, PLAYER)
            .putExtra("filepath", firstPath)
            .putExtra("folder_playlist", true)
    )

    /** Putar daftar dari folder beserta subfolder: [memoryUri] = MpvFolder.memoryPlaylist(...). */
    fun playTree(ctx: Context, memoryUri: String, startIndex: Int = -1, chHeaders: com.molina.core.net.ChannelHeaders.Packed? = null, chGroups: com.molina.core.net.ChannelGroups.Packed? = null): Boolean = start( // [GROUP-VIEW]
        // [ORDER-KEEP] [CH-HEADERS]
        ctx,
        Intent().setClassName(ctx.packageName, PLAYER)
            .putExtra("filepath", memoryUri)
            .putExtra("folder_playlist", true)
            .apply { if (startIndex >= 0) putExtra("start_index", startIndex) }
            .apply { // [CH-HEADERS]
                if (chHeaders != null) {
                    putStringArrayListExtra(com.molina.core.net.ChannelHeaders.EXTRA_URLS, chHeaders.urls)
                    putStringArrayListExtra(com.molina.core.net.ChannelHeaders.EXTRA_VALUES, chHeaders.values)
                }
            }
            .apply { // [GROUP-VIEW]
                if (chGroups != null) {
                    putStringArrayListExtra(com.molina.core.net.ChannelGroups.EXTRA_URLS, chGroups.urls)
                    putStringArrayListExtra(com.molina.core.net.ChannelGroups.EXTRA_VALUES, chGroups.values)
                }
            }
    )

    /** Putar saluran terpilih sebagai playlist memory:// (prev/next di pemutar = ganti saluran). */
    fun playChannels(ctx: Context, allChannels: List<IptvChannel>, picked: IptvChannel): Boolean {
        if (IptvTrust.isBlocked(picked.url)) return false // [IPTV-TRUST-BLOCK] fail-closed
        if (IptvTrust.needsConfirm(picked.url)) return false // [IPTV-TRUST-EXEMPT] belum dikonfirmasi: tolak (riwayat/antrean tidak boleh melewati dialog)
        val channels = allChannels.filter { !IptvTrust.isBlocked(it.url) && !IptvTrust.needsConfirm(it.url) } // [IPTV-TRUST-BLOCK] [IPTV-TRUST-EXEMPT] prev/next hanya lewat yang sudah dikonfirmasi
        val bad = com.molina.cinema.UnplayableStore.all(ctx) // [UNPLAYABLE-CHIP]
        val usable = if (bad.isEmpty()) channels else channels.filter { it.url == picked.url || it.url !in bad }
        val q = IptvPlayback.queue(usable, picked) ?: return false // [ORDER-KEEP]
        val ok = playTree(ctx, q.uri, q.startIndex, com.molina.core.net.ChannelHeaders.encode(channels), com.molina.core.net.ChannelGroups.encode(channels)) // [CH-HEADERS] [GROUP-VIEW]
        if (ok) {
            CinemaRecent.add(ctx, picked.url, picked.name)
            CinemaRecent.saveSnapshot(ctx, picked.url, "ch", channels.map { it.url }, channels.map { it.name }, channels.map { it.logo }, channels.map { com.molina.core.net.ChannelHeaders.pack(it.headers) }) // [RECENT-PLAYLIST] [CH-RECENT]
        }
        return ok
    }

    /**
     * [RECENT-PLAYLIST] Putar ulang [ref] bersama daftar yang berlaku saat dipilih dulu (saluran M3U atau hasil
     * pencarian), bukan satu item saja. False bila tidak ada cuplikan: pemanggil jatuh ke pemutaran tunggal.
     */
    fun playSnapshot(ctx: Context, ref: String): Boolean {
        val s = CinemaRecent.snapshot(ctx, ref) ?: return false
        val idx = s.urls.indexOf(ref)
        if (idx < 0) return false
        return when (s.kind) {
            "ch" -> {
                val list = s.urls.indices.map { IptvChannel(s.titles[it], s.urls[it], "", s.logos[it], com.molina.core.net.ChannelHeaders.unpack(s.headers.getOrElse(it) { "" })) } // [CH-RECENT]
                playChannels(ctx, list, list[idx])
            }
            "res" -> {
                val items = s.urls.indices.map { StreamSearchItem(s.urls[it], s.titles[it]) }
                playResults(ctx, items, items[idx])
            }
            else -> false
        }
    }

    /** Putar item teratas riwayat Cinema (shortcut "Putar terakhir"); false bila riwayat kosong atau gagal. */
    fun playLast(ctx: Context): Boolean {
        val r = CinemaRecent.load(ctx).firstOrNull() ?: return false
        if (playSnapshot(ctx, r.ref)) return true // [RECENT-PLAYLIST]
        if (r.ref.startsWith("/")) return playPath(ctx, r.ref)
        return when (val i = classify(r.ref)) {
            is Input.Direct -> play(ctx, i.uri)
            else -> false
        }
    }

    fun openSettings(ctx: Context): Boolean =
        start(ctx, Intent().setClassName(ctx.packageName, SETTINGS))

    private fun start(ctx: Context, i: Intent): Boolean =
        try {
            // [EXT-CONFIRM] intent internal ke pemutar membawa token; tanpa token, MPVActivity meminta konfirmasi untuk URL jaringan
            if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // [MEDIA-GW] konteks aplikasi (kontrak) butuh task baru
            if (i.component?.className == PLAYER) i.putExtra(ExternalIntentGate.EXTRA, ShortcutToken.get(ctx))
            ctx.startActivity(i); true
        } catch (e: Exception) { false }
}

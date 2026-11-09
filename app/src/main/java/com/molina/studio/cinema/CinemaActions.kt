package com.molina.studio.cinema

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.molina.core.common.ExternalIntentGate
import com.molina.core.common.IptvChannel
import com.molina.core.common.IptvPlayback
import com.molina.core.common.PlaylistExtras
import com.molina.core.common.PlaylistPlan
import com.molina.core.common.StreamSearchItem
import com.molina.studio.security.ShortcutToken

/** Peluncur Molina Cinema (MPVActivity) dan validasi input. Input pengguna dianggap tidak tepercaya. */
internal object CinemaActions {
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
    fun playTree(ctx: Context, memoryUri: String, startIndex: Int = -1): Boolean = start( // [ORDER-KEEP]
        ctx,
        Intent().setClassName(ctx.packageName, PLAYER)
            .putExtra("filepath", memoryUri)
            .putExtra("folder_playlist", true)
            .apply { if (startIndex >= 0) putExtra("start_index", startIndex) }
    )

    /** Putar saluran terpilih sebagai playlist memory:// (prev/next di pemutar = ganti saluran). */
    fun playChannels(ctx: Context, channels: List<IptvChannel>, picked: IptvChannel): Boolean {
        val q = IptvPlayback.queue(channels, picked) ?: return false // [ORDER-KEEP]
        val ok = playTree(ctx, q.uri, q.startIndex)
        if (ok) {
            CinemaRecent.add(ctx, picked.url, picked.name)
            CinemaRecent.saveSnapshot(ctx, picked.url, "ch", channels.map { it.url }, channels.map { it.name }, channels.map { it.logo }) // [RECENT-PLAYLIST]
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
                val list = s.urls.indices.map { IptvChannel(s.titles[it], s.urls[it], "", s.logos[it]) }
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
            if (i.component?.className == PLAYER) i.putExtra(ExternalIntentGate.EXTRA, ShortcutToken.get(ctx))
            ctx.startActivity(i); true
        } catch (e: Exception) { false }
}

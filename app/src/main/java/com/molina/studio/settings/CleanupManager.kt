package com.molina.studio.settings

import android.content.Context
import com.molina.core.common.MolinaDir
import com.molina.core.common.TrashBin
import com.molina.studio.AppPaths
import com.molina.studio.terminal.CrashReporter
import com.molina.studio.terminal.RootfsManager
import com.molina.studio.terminal.RootfsState
import java.io.File
import java.util.concurrent.TimeUnit

internal enum class CleanupId { Rootfs, AppCache, AptCache, OldReports, OldCrashes, OldTranscripts, DebianTemp, TrashBin }

internal class CleanupItem(
    val id: CleanupId,
    val title: String,
    val detail: String,
    val bytes: Long,
    val defaultOn: Boolean,
    val blockedReason: String? = null
)

/**
 * Pusat Pembersihan Cerdas: memindai delapan kategori, menampilkan estimasi byte, lalu menghapus yang dipilih.
 * Rootfs Debian itu sendiri tidak pernah disentuh. Operasi disk: panggil di luar main thread.
 */
internal object CleanupManager {
    const val REPORT_DAYS = 7
    const val CRASH_KEEP = 3
    const val TRANSCRIPT_DAYS = 30
    const val TRANSCRIPT_KEEP = 20
    private const val ROOTFS_ARCHIVE = "debian-rootfs.tar.gz"
    private val EXPORT_PREFIXES = listOf("molina-laporan-bug-", "molina-diagnostik-", "molina-log-")

    private fun cutoff(): Long = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(REPORT_DAYS.toLong())

    private fun rootfsLeftovers(ctx: Context): List<File> =
        listOf(File(ctx.cacheDir, ROOTFS_ARCHIVE), File(ctx.cacheDir, "$ROOTFS_ARCHIVE.part")).filter { it.exists() }

    private fun appCacheEntries(ctx: Context): List<File> =
        (ctx.cacheDir.listFiles() ?: emptyArray()).filter { it.name != "proot-tmp" && !it.name.startsWith(ROOTFS_ARCHIVE) }

    private fun aptEntries(ctx: Context): List<File> {
        val dir = aptArchives(ctx)
        val debs = (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".deb") }
        val partial = File(dir, "partial").listFiles()?.toList() ?: emptyList()
        return debs + partial
    }

    private fun oldReports(ctx: Context): List<File> {
        val dir = AppPaths.get(ctx).dir(MolinaDir.Export)
        val c = cutoff()
        return (dir.listFiles() ?: emptyArray()).filter { f ->
            f.isFile && f.lastModified() < c && EXPORT_PREFIXES.any { f.name.startsWith(it) }
        }
    }

    private fun oldCrashes(ctx: Context): List<File> {
        val private = CrashReporter.list(ctx)
        val keep = private.take(CRASH_KEEP).map { it.name }.toSet()
        val pub = (AppPaths.get(ctx).dir(MolinaDir.Crash).listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith("crash-") }
        val c = cutoff()
        return (private + pub).filter { it.name !in keep && it.lastModified() < c }
    }

    /** Transkrip Terminal: hanya yang lebih tua dari TRANSCRIPT_DAYS DAN di luar TRANSCRIPT_KEEP terbaru. */
    private fun oldTranscripts(ctx: Context): List<File> {
        val c = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(TRANSCRIPT_DAYS.toLong())
        return (AppPaths.get(ctx).dir(MolinaDir.TerminalLog).listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith("terminal-") && it.name.endsWith(".txt") }
            .sortedByDescending { it.lastModified() }
            .drop(TRANSCRIPT_KEEP)
            .filter { it.lastModified() < c }
    }

    private fun debianTemp(ctx: Context): List<File> {
        val root = RootfsManager.rootfsDir(ctx)
        return listOf("tmp", "var/log").flatMap { File(root, it).listFiles()?.toList() ?: emptyList() }
    }

    fun scan(ctx: Context, cancelled: () -> Boolean): List<CleanupItem> {
        RootfsManager.refresh(ctx)
        val ready = RootfsManager.state.value is RootfsState.Ready
        fun size(files: List<File>) = files.sumOf { dirSize(it, cancelled) }
        return listOf(
            CleanupItem(
                CleanupId.Rootfs, "Sisa unduhan Debian",
                "Arsip rootfs di cache yang tidak diperlukan lagi setelah Debian terpasang.",
                size(rootfsLeftovers(ctx)), true,
                if (ready) null else "Debian belum siap: berkas ini masih dipakai pemasangan"
            ),
            CleanupItem(
                CleanupId.AppCache, "Cache aplikasi",
                "Cache shader mpv dan berkas sementara. proot-tmp dan unduhan Debian tidak disentuh.",
                size(appCacheEntries(ctx)), true
            ),
            CleanupItem(
                CleanupId.AptCache, "Cache apt (.deb)",
                "Paket yang sudah diunduh. Paket terpasang tidak terpengaruh. Jangan dijalankan saat apt berjalan.",
                size(aptEntries(ctx)), true
            ),
            CleanupItem(
                CleanupId.OldReports, "Laporan lama (lebih dari $REPORT_DAYS hari)",
                "Laporan bug, log, dan ZIP diagnostik di Documents/Export.",
                size(oldReports(ctx)), true
            ),
            CleanupItem(
                CleanupId.OldCrashes, "Laporan crash lama",
                "Lebih dari $REPORT_DAYS hari dan di luar $CRASH_KEEP crash terbaru (yang terbaru selalu dijaga).",
                size(oldCrashes(ctx)), true
            ),
            CleanupItem(
                CleanupId.OldTranscripts, "Transkrip Terminal lama",
                "Lebih dari $TRANSCRIPT_DAYS hari dan di luar $TRANSCRIPT_KEEP transkrip terbaru di Logs/Terminal (yang terbaru selalu dijaga).",
                size(oldTranscripts(ctx)), true
            ),
            CleanupItem(
                CleanupId.DebianTemp, "File sementara Debian",
                "Isi /tmp dan /var/log di Debian. Tutup sesi Terminal dulu. Rootfs tidak dihapus.",
                size(debianTemp(ctx)), false
            ),
            CleanupItem(
                CleanupId.TrashBin, "Recycle Bin",
                "Hapus permanen semua item di Recycle Bin (internal, kartu SD, Debian). Tidak bisa dibatalkan.",
                runCatching { TrashBin.usage(trashVolumes(ctx)) }.getOrDefault(0L), false
            )
        )
    }

    private fun delete(ctx: Context, id: CleanupId) {
        when (id) {
            CleanupId.Rootfs -> rootfsLeftovers(ctx).forEach { wipe(it) }
            CleanupId.AppCache -> appCacheEntries(ctx).forEach { wipe(it) }
            CleanupId.AptCache -> aptEntries(ctx).forEach { wipe(it) }
            CleanupId.OldReports -> oldReports(ctx).forEach { wipe(it) }
            CleanupId.OldCrashes -> oldCrashes(ctx).forEach { wipe(it) }
            CleanupId.OldTranscripts -> oldTranscripts(ctx).forEach { wipe(it) }
            CleanupId.DebianTemp -> debianTemp(ctx).forEach { wipe(it) }
            CleanupId.TrashBin -> runCatching { TrashBin.empty(trashVolumes(ctx)) }
        }
    }

    /** Hapus kategori terpilih (yang tidak diblokir) dan kembalikan byte yang benar-benar dibebaskan. */
    fun clean(ctx: Context, ids: Set<CleanupId>): Long {
        val before = scan(ctx) { false }.associateBy { it.id }
        ids.filter { before[it]?.blockedReason == null }.forEach { delete(ctx, it) }
        val after = scan(ctx) { false }.associateBy { it.id }
        return ids.sumOf { maxOf(0L, (before[it]?.bytes ?: 0L) - (after[it]?.bytes ?: 0L)) }
    }
}

package com.molina.core.common

import java.io.File

/**
 * "Rapikan ke folder kanal": video yang tergeletak langsung di sebuah folder (mis. unduhan sebelum mode
 * `--per-channel`) dipindah ke subfolder bernama kanal, bersama poster, `.info.json`, dan subtitle di sampingnya.
 * Nama kanal dibaca dari `.info.json` sidecar ([MediaSidecar], butuh `molina_dl --sidecar`) dan dijadikan nama
 * folder lewat [FolderName], sama dengan unduhan `--per-channel`. Hanya `renameTo` di volume yang sama (tanpa salin),
 * tidak pernah menimpa, bisa dibatalkan di tengah jalan dan diurungkan. Murni JVM; tidak melempar.
 */
object ChannelTidy {
    const val MAX_VIDEOS = 500
    private val SUB_EXTS = setOf("srt", "vtt", "ass")

    /** Satu video, folder kanal tujuannya, dan berkas pendamping yang ikut pindah. */
    class Item(val video: File, val folder: String, val related: List<File>)

    /**
     * @property noInfo video tanpa `.info.json` / tanpa nama kanal (dilewati)
     * @property conflicts video yang namanya sudah ada di folder kanal tujuan (dilewati, tidak ditimpa)
     * @property inPlace video yang sudah berada di folder bernama kanalnya (tidak perlu dipindah)
     */
    class Plan(val dir: File, val items: List<Item>, val noInfo: Int, val conflicts: Int, val inPlace: Int) {
        val channels: Int get() = items.map { it.folder.lowercase() }.toSet().size
    }

    class Moved(val from: File, val to: File)

    /** [videos] jumlah video yang berhasil pindah, [failed] video yang gagal pindah, [created] folder kanal yang baru dibuat. */
    class Result(val moved: List<Moved>, val videos: Int, val failed: Int, val created: List<File>)

    fun plan(dir: File): Plan {
        val kids = try { dir.listFiles() } catch (e: SecurityException) { null }
            ?: return Plan(dir, emptyList(), 0, 0, 0)
        val videos = kids.asSequence()
            .filter { !it.name.startsWith(".") && it.isFile && it.extension.lowercase() in ShelfScan.VIDEO_EXTS }
            .sortedBy { it.name.lowercase() }.take(MAX_VIDEOS).toList()
        val items = ArrayList<Item>()
        var noInfo = 0
        var conflicts = 0
        var inPlace = 0
        for (v in videos) {
            val channel = MediaSidecar.readInfo(MediaSidecar.find(v).info)?.channel
            if (channel == null) { noInfo++; continue }
            val folder = FolderName.sanitize(channel)
            if (folder.equals(dir.name, ignoreCase = true)) { inPlace++; continue }
            val target = File(File(dir, folder), v.name)
            val folderFile = File(dir, folder)
            if (target.exists() || (folderFile.exists() && !folderFile.isDirectory)) { conflicts++; continue }
            items.add(Item(v, folder, related(v, kids)))
        }
        return Plan(dir, items, noInfo, conflicts, inPlace)
    }

    /** Poster, `.info.json`, dan subtitle yang namanya `<nama video>.<sesuatu>` (mis. `Film [id].id.srt`). */
    private fun related(video: File, siblings: Array<File>): List<File> {
        val prefix = video.nameWithoutExtension + "."
        return siblings.filter { f ->
            f.isFile && f.name != video.name && f.name.startsWith(prefix) &&
                (f.name.endsWith(MediaSidecar.INFO_SUFFIX) || f.extension.lowercase() in MediaSidecar.POSTER_EXTS || f.extension.lowercase() in SUB_EXTS)
        }.sortedBy { it.name }
    }

    /** Jalankan [plan]. [cancelled] diperiksa sebelum tiap video; yang sudah pindah tetap tercatat di hasil. */
    fun apply(plan: Plan, resumeDirs: List<File> = emptyList(), cancelled: () -> Boolean = { false }): Result {
        val moved = ArrayList<Moved>()
        val created = ArrayList<File>()
        var videos = 0
        var failed = 0
        for (item in plan.items) {
            if (cancelled()) break
            val folder = File(plan.dir, item.folder)
            if (!folder.exists() && folder.mkdirs()) created.add(folder)
            if (!folder.isDirectory) { failed++; continue }
            val target = File(folder, item.video.name)
            if (target.exists() || !item.video.renameTo(target)) { failed++; continue }
            videos++
            moved.add(Moved(item.video, target))
            ResumeMark.move(resumeDirs, item.video.path, target.path)
            for (r in item.related) {
                val t = File(folder, r.name)
                if (!t.exists() && r.isFile && r.renameTo(t)) moved.add(Moved(r, t))
            }
        }
        return Result(moved, videos, failed, created)
    }

    /** Kembalikan semua berkas di [result] ke tempat asal (tidak menimpa) dan hapus folder kanal baru yang kosong. Mengembalikan jumlah berkas yang dipulihkan. */
    fun undo(result: Result, resumeDirs: List<File> = emptyList()): Int {
        var back = 0
        for (m in result.moved.asReversed()) {
            if (m.to.isFile && !m.from.exists() && m.to.renameTo(m.from)) {
                back++
                ResumeMark.move(resumeDirs, m.to.path, m.from.path)
            }
        }
        for (d in result.created.asReversed()) d.delete()   // hanya berhasil bila kosong
        return back
    }
}

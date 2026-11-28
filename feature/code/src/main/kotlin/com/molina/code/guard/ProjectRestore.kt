package com.molina.code.guard

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// [CODE-RESTORE] Pemulihan SELURUH proyek `.molina` ke satu commit, sepenuhnya lokal (tanpa jaringan). Alurnya:
// pratinjau ([plan]: berkas yang ditimpa/dibuat/dihapus dan yang dilewati) lalu [run]: commit pengaman otomatis
// "Sebelum pemulihan ke ..." (supaya keadaan sekarang bisa dikembalikan), hapus berkas yang tidak ada di commit
// target, tulis ulang sisanya. Berkas yang isinya tidak bisa dicadangkan (terlalu besar) tidak pernah ditimpa/dihapus,
// dan berkas yang isi targetnya tidak tersimpan dilewati. Folder di [RunReport.SKIP_DIRS] tidak disentuh.
// Kotlin murni (tanpa Android): scripts/code-tests/run_projrestore.sh.

object ProjectRestore {
    const val SAFETY_PREFIX = "Sebelum pemulihan ke "
    const val LOG_PREFIX = "Pulihkan proyek ke "

    enum class Skip { NO_BLOB, NO_BACKUP, BAD_PATH }

    /** [kind]: 'A' dibuat lagi, 'M' ditimpa, 'D' dihapus. */
    class Item(val path: String, val kind: Char)

    class Skipped(val path: String, val reason: Skip)

    class Plan(val items: List<Item>, val skipped: List<Skipped>) {
        val created: Int get() = items.count { it.kind == 'A' }
        val overwritten: Int get() = items.count { it.kind == 'M' }
        val deleted: Int get() = items.count { it.kind == 'D' }
        val isEmpty: Boolean get() = items.isEmpty()
    }

    enum class Status { OK, NO_SNAPSHOT, DISABLED, TOO_BIG, SAFETY_FAILED }

    class Result(
        val status: Status,
        val created: Int = 0,
        val overwritten: Int = 0,
        val deleted: Int = 0,
        val failed: Int = 0,
        val skipped: Int = 0
    )

    /** Label waktu commit untuk pesan log (zona waktu perangkat). */
    fun label(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(time))

    /**
     * Rencana pemulihan [root] ke commit [time] terhadap isi folder sekarang; null bila bukan proyek `.molina`, commit
     * tidak punya manifest, atau proyek terlalu besar. [strict] = true hanya menganggap berkas tercadangkan bila
     * blob-nya sudah ada (dipakai [run] setelah commit pengaman); false = berkas kecil dianggap akan dicadangkan.
     */
    fun plan(root: File, time: Long, maxFiles: Int = RunReport.MAX_FILES, strict: Boolean = false): Plan? {
        if (!ProjectLog.isEnabled(root)) return null
        val man = ProjectStore.readManifest(root, time) ?: return null
        val cur = ProjectCommit.scan(root, ProjectCommit.readIndex(root), maxFiles) ?: return null
        val items = ArrayList<Item>()
        val skipped = ArrayList<Skipped>()
        fun backedUp(c: ProjectCommit.Stat) = ProjectStore.has(root, c.sha) || (!strict && c.size <= ProjectStore.MAX_BLOB_FILE)
        for (path in man.keys.sorted()) {
            val t = man.getValue(path)
            if (!ProjectStore.safePath(path)) { skipped.add(Skipped(path, Skip.BAD_PATH)); continue }
            val c = cur[path]
            if (c != null && c.sha == t.sha) continue
            if (!ProjectStore.has(root, t.sha)) { skipped.add(Skipped(path, Skip.NO_BLOB)); continue }
            if (c != null && !backedUp(c)) { skipped.add(Skipped(path, Skip.NO_BACKUP)); continue }
            items.add(Item(path, if (c == null) 'A' else 'M'))
        }
        for (path in cur.keys.sorted()) {
            if (path in man || !ProjectStore.safePath(path)) continue
            if (!backedUp(cur.getValue(path))) { skipped.add(Skipped(path, Skip.NO_BACKUP)); continue }
            items.add(Item(path, 'D'))
        }
        return Plan(items, skipped)
    }

    /**
     * Pulihkan [root] ke commit [time]. Memblokir: panggil dari thread IO. Bila commit pengaman gagal, tidak ada berkas
     * yang diubah. Indeks tidak disentuh sesudahnya, jadi hasil pemulihan tampak sebagai perubahan belum di-commit.
     */
    fun run(root: File, time: Long, now: Long): Result = synchronized(ProjectStore.LOCK) {
        if (!ProjectLog.isEnabled(root)) return@synchronized Result(Status.DISABLED)
        val man = ProjectStore.readManifest(root, time) ?: return@synchronized Result(Status.NO_SNAPSHOT)
        // Commit pengaman: simpan keadaan sekarang. Manifest target dilindungi dari gc walau melewati batas jumlah.
        val safety = ProjectCommit.commit(root, SAFETY_PREFIX + label(time), now, keepSnapshot = time)
        when (safety.status) {
            ProjectCommit.Status.COMMITTED, ProjectCommit.Status.NOTHING -> {}
            ProjectCommit.Status.TOO_BIG -> return@synchronized Result(Status.TOO_BIG)
            else -> return@synchronized Result(Status.SAFETY_FAILED)
        }
        val plan = plan(root, time, strict = true) ?: return@synchronized Result(Status.TOO_BIG)
        var created = 0
        var overwritten = 0
        var deleted = 0
        var failed = 0
        val done = ArrayList<ProjectLog.FileChange>()
        // Hapus dulu: berkas yang menghalangi folder (atau sebaliknya) hilang sebelum penulisan.
        for (it in plan.items) if (it.kind == 'D') {
            if (ProjectStore.deleteFile(root, it.path)) { deleted++; done.add(ProjectLog.FileChange(it.path, 'D', 0, 0)) } else failed++
        }
        for (it in plan.items) if (it.kind != 'D') {
            if (ProjectStore.restoreWith(root, man, it.path) == ProjectStore.RestoreStatus.OK) {
                if (it.kind == 'A') created++ else overwritten++
                done.add(ProjectLog.FileChange(it.path, it.kind, 0, 0))
            } else failed++
        }
        val at = ProjectStore.freeTime(root, maxOf(now, safety.entry?.time ?: now) + 1)
        val base = ProjectLog.note(at, ProjectStore.SRC_RESTORE, LOG_PREFIX + label(time))
        ProjectLog.append(
            root,
            ProjectLog.Entry(at, ProjectStore.SRC_RESTORE, base.message, done.take(ProjectLog.MAX_FILES_PER_ENTRY),
                maxOf(0, done.size - ProjectLog.MAX_FILES_PER_ENTRY))
        )
        Result(Status.OK, created, overwritten, deleted, failed, plan.skipped.size)
    }
}

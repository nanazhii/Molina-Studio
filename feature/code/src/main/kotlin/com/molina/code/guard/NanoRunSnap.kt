package com.molina.code.guard

import com.molina.code.history.NanoInbox
import java.io.File

// [NANO-RUNSNAP] Impor snapshot folder skrip dari nano-wrapper.sh (Alt+R) menjadi RunReport.Snapshot, supaya kartu
// "Perubahan skrip" + Urungkan yang sudah ada untuk runner Code juga muncul untuk skrip yang dijalankan lewat nano.
// Format <inbox>/<ms>_r_<pid>_<n>.runsnap/: root (jalur guest folder), files/ (salinan cp -p, mtime asli ikut),
// big/<nama> + big.tsv (nama<TAB>ukuran; berkas besar/biner tanpa salinan), done (penanda selesai, ditulis terakhir).
// Snapshot hanya satu tingkat folder, sedangkan RunReport.compare berjalan rekursif: perubahan di subfolder disaring.
// Kotlin murni: scripts/code-tests/run_nano_runsnap.sh.
object NanoRunSnap {
    const val SUFFIX = ".runsnap"
    private const val DONE = "done"
    private const val STALE_MS = 3_600_000L
    private const val MTIME_TRUST_MS = 2_000L

    class Loaded(val snap: RunReport.Snapshot, val ignore: Set<String>)
    class Result(val snap: RunReport.Snapshot, val report: RunReport.Report)

    /** Bangun Snapshot dari satu folder .runsnap; null bila folder tak lengkap atau jalurnya tak aman/tak dikenal. */
    fun load(dir: File, storageRoot: File): Loaded? {
        val rootFile = File(dir, "root")
        if (!rootFile.isFile) return null
        val guest = rootFile.readText(Charsets.UTF_8).lineSequence().firstOrNull().orEmpty()
        val root = NanoInbox.hostFile(guest, storageRoot) ?: return null
        if (!root.isDirectory) return null
        val startedAt = dir.name.substringBefore('_').toLongOrNull() ?: return null
        val meta = HashMap<String, RunReport.Meta>()
        val backups = HashMap<String, ByteArray>()
        val ignore = HashSet<String>()
        for (f in File(dir, "files").listFiles().orEmpty()) {
            if (!f.isFile) continue
            // Isi identik tidak dilaporkan oleh RunReport.analyze, jadi mtime salinan yang tak terjaga hanya membuat kerja ekstra.
            meta[f.name] = RunReport.Meta(f.length(), f.lastModified())
            if (f.length() <= RunReport.MAX_BACKUP_FILE) backups[f.name] = f.readBytes()
        }
        val tsv = File(dir, "big.tsv")
        if (tsv.isFile) {
            for (line in tsv.readLines(Charsets.UTF_8)) {
                val parts = line.split('\t')
                if (parts.size != 2) continue
                val size = parts[1].toLongOrNull() ?: continue
                val ph = File(File(dir, "big"), parts[0])
                // mtime placeholder baru (touch -r gagal menjaga waktu asli) tak bisa dipercaya: berkas itu tak dilaporkan.
                if (!ph.isFile || ph.lastModified() >= startedAt - MTIME_TRUST_MS) { ignore.add(parts[0]); continue }
                meta[parts[0]] = RunReport.Meta(size, ph.lastModified())
            }
        }
        return Loaded(RunReport.Snapshot(root, startedAt, meta, backups, null), ignore)
    }

    /**
     * Proses semua .runsnap yang sudah punya penanda `done`: bandingkan dengan keadaan sekarang, simpan hanya perubahan
     * tingkat folder yang tak kosong (urut nama = urut waktu), lalu hapus foldernya. Tanpa `done` dan lebih tua dari satu
     * jam dibuang; yang lebih muda dibiarkan (skrip mungkin masih jalan). Memblokir: panggil dari thread IO.
     */
    fun collect(inbox: File, storageRoot: File, now: Long = System.currentTimeMillis()): List<Result> {
        val dirs = inbox.listFiles { f -> f.isDirectory && f.name.endsWith(SUFFIX) } ?: return emptyList()
        val out = ArrayList<Result>()
        for (d in dirs.sortedBy { it.name }) {
            if (!File(d, DONE).isFile) {
                if (now - d.lastModified() > STALE_MS) d.deleteRecursively()
                continue
            }
            try {
                val l = load(d, storageRoot)
                val rep = if (l == null) null else RunReport.compare(l.snap)
                if (l != null && rep != null) {
                    val ch = rep.changes.filter { '/' !in it.path && it.path !in l.ignore }
                    if (ch.isNotEmpty()) out.add(Result(l.snap, RunReport.Report(rep.root, ch)))
                }
            } catch (e: Exception) {
                // best-effort
            }
            d.deleteRecursively()
        }
        return out
    }
}

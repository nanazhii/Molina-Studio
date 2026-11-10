import com.molina.code.guard.NanoRunSnap
import com.molina.code.guard.RunReport
import java.io.File
import java.nio.file.Files

// [NANO-RUNSNAP] Uji impor snapshot folder skrip dari nano (JVM murni), termasuk uji ujung-ke-ujung dengan nano-wrapper.sh asli.
private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private const val OLD = 1_000_000_000_000L
private const val START = 1_700_000_000_000L

private fun snapDir(inbox: File, id: String, guestRoot: String, done: Boolean = true): File {
    val d = File(inbox, "$id${NanoRunSnap.SUFFIX}")
    File(d, "files").mkdirs(); File(d, "big").mkdirs()
    File(d, "root").writeText("$guestRoot\n")
    File(d, "big.tsv").writeText("")
    if (done) File(d, "done").writeText("")
    return d
}
private fun backup(d: File, name: String, body: String, mtime: Long = OLD) {
    val f = File(d, "files/$name"); f.writeText(body); f.setLastModified(mtime)
}
private fun big(d: File, name: String, size: Long, mtime: Long) {
    File(d, "big.tsv").appendText("$name\t$size\n")
    val f = File(d, "big/$name"); f.writeText(""); f.setLastModified(mtime)
}
private fun put(f: File, body: String, mtime: Long = OLD) { f.parentFile.mkdirs(); f.writeText(body); f.setLastModified(mtime) }

fun main() {
    val tmp = Files.createTempDirectory("nanorunsnap").toFile()
    try {
        val sd = File(tmp, "sd").apply { mkdirs() }
        val inbox = File(sd, "Molina/.history-inbox").apply { mkdirs() }
        val work = File(sd, "work")

        // keadaan setelah skrip jalan
        put(File(work, "a.sh"), "satu\ndua\n", mtime = START + 5_000)       // diubah
        put(File(work, "baru.sh"), "baru\n", mtime = START + 5_000)         // ditambah
        put(File(work, "sama.sh"), "tetap\n")                               // tak berubah
        put(File(work, "salinan.sh"), "isi sama\n", mtime = START + 9_000)  // mtime beda, isi sama
        put(File(work, "besar.dat"), "x".repeat(10))                        // besar: tak berubah
        put(File(work, "besar2.dat"), "y".repeat(12))                       // besar: placeholder tak tepercaya
        put(File(work, "sub/dalam.sh"), "baru di subfolder\n")              // subfolder: disaring
        val d = snapDir(inbox, "${START}_r_1_1", "/sdcard/work")
        // fixture pakai /sdcard/..., sehingga root = $sd/work lewat hostFile
        backup(d, "a.sh", "satu\n"); backup(d, "hapus.sh", "hilang\n"); backup(d, "sama.sh", "tetap\n")
        backup(d, "salinan.sh", "isi sama\n", mtime = START + 50_000)
        big(d, "besar.dat", 10, OLD)
        big(d, "besar2.dat", 11, START + 100) // mtime placeholder baru -> diabaikan

        val l = NanoRunSnap.load(d, sd)
        check("load: root, startedAt, meta, cadangan, ignore", l != null && l.snap.root == work && l.snap.startedAt == START &&
            l.snap.meta.keys == setOf("a.sh", "hapus.sh", "sama.sh", "salinan.sh", "besar.dat") &&
            l.snap.backups.keys == setOf("a.sh", "hapus.sh", "sama.sh", "salinan.sh") && l.ignore == setOf("besar2.dat"))

        val res = NanoRunSnap.collect(inbox, sd, now = START + 10_000)
        val ch = res.singleOrNull()?.report?.changes.orEmpty()
        val kinds = ch.associate { it.path to it.kind }
        check("collect: a.sh diubah, baru.sh ditambah, hapus.sh dihapus", kinds == mapOf(
            "a.sh" to RunReport.Kind.MODIFIED, "baru.sh" to RunReport.Kind.ADDED, "hapus.sh" to RunReport.Kind.DELETED))
        check("collect: subfolder, tak berubah, mtime-beda-isi-sama, besar, placeholder baru tidak dilaporkan",
            ch.none { '/' in it.path || it.path in setOf("sama.sh", "salinan.sh", "besar.dat", "besar2.dat") })
        check("collect: folder .runsnap dihapus", inbox.listFiles().orEmpty().none { it.name.endsWith(NanoRunSnap.SUFFIX) })
        check("collect: a.sh tambah/hapus baris benar", ch.first { it.path == "a.sh" }.let { it.added == 1 && it.removed == 0 && it.undoable })

        // Urungkan memakai RunReport.undo yang sudah ada
        val snap = res.single().snap
        val undo = RunReport.undo(snap, res.single().report)
        check("undo: a.sh kembali, baru.sh hilang, hapus.sh dibuat ulang",
            File(work, "a.sh").readText() == "satu\n" && !File(work, "baru.sh").exists() && File(work, "hapus.sh").readText() == "hilang\n" &&
                undo.failed.isEmpty())

        // tanpa penanda done
        val young = snapDir(inbox, "${START}_r_2_1", "/sdcard/work", done = false)
        check("tanpa done & baru: dibiarkan", NanoRunSnap.collect(inbox, sd, now = young.lastModified() + 1_000).isEmpty() && young.exists())
        check("tanpa done & > 1 jam: dibuang", NanoRunSnap.collect(inbox, sd, now = young.lastModified() + 3_700_000).isEmpty() && !young.exists())

        // jalur tak aman / folder tak ada
        val bad = snapDir(inbox, "${START}_r_3_1", "/sdcard/../etc")
        check("root dengan .. -> null, folder dibuang", NanoRunSnap.load(bad, sd) == null && NanoRunSnap.collect(inbox, sd, START).isEmpty() && !bad.exists())
        val miss = snapDir(inbox, "${START}_r_4_1", "/sdcard/tidakada")
        check("folder skrip tak ada -> dilewati, dibuang", NanoRunSnap.collect(inbox, sd, START).isEmpty() && !miss.exists())
        val noroot = snapDir(inbox, "${START}_r_5_1", "/sdcard/work"); File(noroot, "root").delete()
        check("tanpa root -> null", NanoRunSnap.load(noroot, sd) == null)
        check("inbox tidak ada = kosong", NanoRunSnap.collect(File(tmp, "none"), sd).isEmpty())

        // tak ada perubahan -> tak ada hasil
        val work2 = File(sd, "w2"); put(File(work2, "a.sh"), "x\n")
        val d2 = snapDir(inbox, "${START}_r_6_1", "/sdcard/w2"); backup(d2, "a.sh", "x\n")
        check("tanpa perubahan -> tidak ada hasil (folder tetap dihapus)", NanoRunSnap.collect(inbox, sd, START + 10_000).isEmpty() && !d2.exists())

        // ujung-ke-ujung: wrapper asli membuat snapshot, lalu diimpor
        val sh = ProcessBuilder("sh", "-c", "exit 0").start().waitFor() == 0
        if (!sh) { println("sh tidak ada: uji ujung-ke-ujung dilewati") } else {
            val e2e = File(tmp, "e2e"); val sd2 = File(e2e, "sd").apply { mkdirs() }
            File(sd2, "Molina").mkdirs()
            val w3 = File(sd2, "w3"); w3.mkdirs()
            val a = File(w3, "a.sh"); a.writeText("satu\n"); a.setLastModified(OLD)
            val h = File(w3, "hapus.sh"); h.writeText("hilang\n"); h.setLastModified(OLD)
            val b = File(w3, "besar.dat"); b.writeBytes(ByteArray(600_000) { 1 }); b.setLastModified(OLD)
            val nano = File(e2e, "nano"); nano.writeText("#!/bin/sh\n: > \"\$MOLINA_RUN_FLAG\"\nexit 0\n"); nano.setExecutable(true)
            val run = File(e2e, "run"); run.writeText("#!/bin/sh\nd=\$(dirname \"\$1\")\nprintf 'dua\\n' >> \"\$d/a.sh\"; printf 'n\\n' > \"\$d/baru.sh\"; rm -f \"\$d/hapus.sh\"\nexit 0\n"); run.setExecutable(true)
            val home = File(e2e, "home").apply { mkdirs() }
            val pb = ProcessBuilder("sh", File("app/src/main/assets/molina/nano-wrapper.sh").absolutePath, "a.sh").directory(w3).redirectErrorStream(true)
            pb.environment().putAll(mapOf(
                "HOME" to home.path, "MOLINA_NANO_TEMPLATE" to "off", "MOLINA_HISTORY_INBOX" to File(sd2, "Molina/.history-inbox").path,
                "MOLINA_HISTORY_PREFIX" to sd2.path, "MOLINA_NANO_BIN" to nano.path, "MOLINA_RUN_BIN" to run.path, "MOLINA_NANORC" to File(e2e, "rc").path))
            val out = pb.start().inputStream.bufferedReader().readText()
            val r = NanoRunSnap.collect(File(sd2, "Molina/.history-inbox"), sd2)
            val k = r.singleOrNull()?.report?.changes.orEmpty().associate { it.path to it.kind }
            check("e2e wrapper asli: a.sh diubah, baru.sh ditambah, hapus.sh dihapus, besar.dat tidak ($out)", k == mapOf(
                "a.sh" to RunReport.Kind.MODIFIED, "baru.sh" to RunReport.Kind.ADDED, "hapus.sh" to RunReport.Kind.DELETED))
        }
    } finally {
        tmp.deleteRecursively()
    }
    if (fails > 0) { println("$fails GAGAL"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}

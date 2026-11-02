package com.molina.code.ui

import java.io.File
import java.nio.file.Files

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun w(dir: File, rel: String, text: String): File {
    val f = File(dir, rel)
    f.parentFile.mkdirs()
    f.writeText(text)
    return f
}

fun main() {
    val root = Files.createTempDirectory("symtest").toFile()
    try {
        val kt = w(root, "src/Player.kt", "package x\n\nclass MolinaPlayer {\n    fun play() {}\n    private fun stopAll() {}\n    inner class Queue {\n        fun enqueue() {}\n    }\n}\n\nfun topLevelHelper() {}\n")
        val py = w(root, "tools/run.py", "class Runner:\n    def start(self):\n        pass\n\ndef main():\n    pass\n")
        val md = w(root, "README.md", "# Judul\n## Bagian\n")
        val png = w(root, "a.png", "x")
        val empty = w(root, "empty.kt", "")
        val big = w(root, "big.kt", "fun a() {}\n" + "x".repeat((SymbolIndex.MAX_BYTES + 10).toInt()))
        val all = listOf(kt, py, md, png, empty, big)

        // ---- indeks dasar
        val idx = SymbolIndex()
        val r = idx.build(all, emptyMap()) { true }
        val names = r.entries.map { it.name }
        check("indeks: kelas dan fungsi Kotlin", "MolinaPlayer" in names && "play" in names && "topLevelHelper" in names)
        check("indeks: Python", "Runner" in names && "start" in names && "main" in names)
        check("indeks: markdown tidak masuk", "Judul" !in names && "Bagian" !in names)
        check("indeks: png, kosong, terlalu besar dilewati", r.files == 2 && "a" !in names)
        check("indeks: baca disk 2 berkas", idx.lastDiskReads == 2)

        // ---- container
        val play = r.entries.first { it.name == "play" }
        val enq = r.entries.first { it.name == "enqueue" }
        val queue = r.entries.first { it.name == "Queue" }
        val top = r.entries.first { it.name == "topLevelHelper" }
        check("container: metode di kelas", play.container == "MolinaPlayer")
        check("container: kelas bersarang", queue.container == "MolinaPlayer")
        check("container: metode di kelas bersarang", enq.container == "Queue")
        check("container: tingkat atas kosong", top.container == "" && r.entries.first { it.name == "MolinaPlayer" }.container == "")
        check("baris basis 0", play.line == 3 && top.line == 10)

        // ---- cache
        idx.build(all, emptyMap()) { true }
        check("cache: pembukaan kedua tanpa baca disk", idx.lastDiskReads == 0)
        Thread.sleep(20)
        kt.writeText(kt.readText() + "fun baru() {}\n")
        kt.setLastModified(kt.lastModified() + 5000)
        val r2 = idx.build(all, emptyMap()) { true }
        check("cache: berkas berubah dibaca ulang saja", idx.lastDiskReads == 1 && r2.entries.any { it.name == "baru" })

        // ---- override tab terbuka
        val r3 = idx.build(all, mapOf(py.path to "def hanyaDiTab():\n    pass\n")) { true }
        val n3 = r3.entries.map { it.name }
        check("override: teks tab menang atas disk", "hanyaDiTab" in n3 && "Runner" !in n3)
        val r4 = idx.build(all, emptyMap()) { true }
        check("override: tidak masuk cache", r4.entries.any { it.name == "Runner" } && r4.entries.none { it.name == "hanyaDiTab" })

        // ---- berkas hilang dipangkas, pembatalan
        py.delete()
        val r5 = idx.build(listOf(kt), emptyMap()) { true }
        check("pangkas: berkas hilang tidak ikut", r5.entries.none { it.name == "Runner" })
        var calls = 0
        val r6 = idx.build(all, emptyMap()) { calls++ < 1 }
        check("batal: berhenti di tengah", r6.files <= 1)

        // ---- peringkat
        val idx2 = SymbolIndex()
        val e = idx2.build(
            listOf(
                w(root, "a/Alpha.kt", "class Player {}\nfun playerCount() {}\nfun xplayer() {}\nfun p_l_a_y_e_r() {}\n"),
                w(root, "b/Beta.kt", "fun player() {}\nobject PlayerState {}\n")
            ), emptyMap()
        ) { true }.entries
        val q = SymbolSearch.rank(e, "player", 10, null)
        check("peringkat: nama persis paling atas", q.first().name.equals("player", true))
        check("peringkat: awalan di atas fuzzy", q.indexOfFirst { it.name == "playerCount" } < q.indexOfFirst { it.name == "xplayer" })
        check("peringkat: fuzzy tetap ketemu", q.any { it.name == "p_l_a_y_e_r" })
        check("peringkat: huruf kecil/besar tak berpengaruh", SymbolSearch.rank(e, "PLAYERSTATE", 10, null).first().name == "PlayerState")
        check("peringkat: tidak cocok = kosong", SymbolSearch.rank(e, "zzzzqq", 10, null).isEmpty())
        val cur = File(root, "b/Beta.kt")
        val qc = SymbolSearch.rank(e, "pl", 10, cur)
        check("peringkat: bonus berkas aktif", qc.first().file.path == cur.path)
        val empty0 = SymbolSearch.rank(e, "", 10, cur)
        check("kosong: hanya berkas aktif, urut baris", empty0.all { it.file.path == cur.path } && empty0.map { it.line } == empty0.map { it.line }.sorted())
        check("kosong: tanpa berkas aktif = kosong", SymbolSearch.rank(e, "", 10, null).isEmpty())
        check("batas hasil", SymbolSearch.rank(e, "p", 2, null).size == 2)
        check("fuzzyScore: tidak cocok -1", fuzzyScore("zq", "player") == -1)
    } finally {
        root.deleteRecursively()
    }
    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}

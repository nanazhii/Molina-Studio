import com.molina.code.lsp.DefinitionOutcome
import com.molina.code.lsp.LspDefinitionLogic
import com.molina.code.lsp.LspDefinitionTarget
import com.molina.code.nav.JumpPoint
import com.molina.code.nav.JumpStack
import com.molina.code.nav.RootfsPaths
import java.io.File
import java.nio.file.Files

// [CODE-BACK][CODE-DEBLIB] Uji JVM murni: tumpukan posisi "Kembali" dan resolusi path rootfs (read-only Debian).

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun jp(tab: Int, line: Int, col: Int = 1) = JumpPoint(tab, line, col)

fun main() {
    // ---------------------------------------------------------------- JumpStack
    val alive = { _: Int -> true }
    run {
        val s = JumpStack()
        check("stack: awal kosong", s.size == 0 && s.pop(alive) == null && s.peek(alive) == null)
        check("stack: origin null tidak dicatat", !s.push(null, jp(1, 5)) && s.size == 0)
        check("stack: push mencatat", s.push(jp(1, 10), jp(2, 3)) && s.size == 1)
        check("stack: peek tidak mengambil", s.peek(alive) == jp(1, 10) && s.size == 1)
        check("stack: pop mengambil", s.pop(alive) == jp(1, 10) && s.size == 0)
    }
    run {
        val s = JumpStack()
        check("stack: lompat di baris yang sama tidak dicatat", !s.push(jp(1, 10, 4), jp(1, 10, 20)) && s.size == 0)
        check("stack: lompat ke baris lain di tab sama dicatat", s.push(jp(1, 10), jp(1, 11)))
        check("stack: baris asal sama dengan puncak tidak digandakan", !s.push(jp(1, 10, 9), jp(2, 0)) && s.size == 1)
        check("stack: tab lain di baris sama dicatat", s.push(jp(2, 10), jp(3, 0)) && s.size == 2)
    }
    run {
        val s = JumpStack()
        s.push(jp(1, 1), jp(2, 0)); s.push(jp(2, 2), jp(3, 0)); s.push(jp(3, 3), jp(4, 0))
        val live = { id: Int -> id != 3 }
        check("stack: peek membuang tab mati di puncak", s.peek(live) == jp(2, 2) && s.size == 2)
        check("stack: pop urut terbalik", s.pop(live) == jp(2, 2) && s.pop(live) == jp(1, 1) && s.pop(live) == null)
    }
    run {
        val s = JumpStack()
        s.push(jp(1, 1), jp(9, 9)); s.push(jp(2, 2), jp(9, 9)); s.push(jp(3, 3), jp(9, 9))
        s.dropTab(2)
        check("stack: dropTab", s.size == 2 && s.pop(alive) == jp(3, 3) && s.pop(alive) == jp(1, 1))
        s.push(jp(1, 1), jp(9, 9)); s.clear()
        check("stack: clear", s.size == 0)
    }
    run {
        val s = JumpStack(max = 3)
        for (i in 1..5) s.push(jp(1, i * 10), jp(2, 0))
        check("stack: batas dijaga, yang tertua dibuang", s.size == 3 && s.pop(alive) == jp(1, 50) && s.pop(alive) == jp(1, 40) && s.pop(alive) == jp(1, 30) && s.pop(alive) == null)
        check("stack: batas bawaan 50", JumpStack.DEFAULT_MAX == 50)
        val d = JumpStack()
        for (i in 1..80) d.push(jp(1, i), jp(2, 0))
        check("stack: 80 lompatan -> 50 tersisa", d.size == 50)
    }

    // ---------------------------------------------------------------- RootfsPaths
    val tmp = Files.createTempDirectory("rootfs-test").toFile()
    try {
        val rootfs = File(tmp, "debian").apply { mkdirs() }
        fun mk(rel: String, text: String = "x") = File(rootfs, rel).apply { parentFile.mkdirs(); writeText(text) }
        fun link(rel: String, target: String) {
            val f = File(rootfs, rel); f.parentFile.mkdirs()
            Files.createSymbolicLink(f.toPath(), java.nio.file.Paths.get(target))
        }
        mk("usr/lib/python3.12/os.py")
        mk("usr/lib/python3/dist-packages/pkg/mod.py")
        File(rootfs, "usr/lib/python3.12/adir").mkdirs()
        // rahasia di luar rootfs: symlink jahat tidak boleh membacanya
        File(tmp, "rahasia.txt").writeText("rahasia")
        link("usr/bin/python3", "python3.12")                       // relatif
        mk("usr/bin/python3.12")
        link("lib", "usr/lib")                                       // symlink direktori relatif
        link("etc/alt", "/usr/lib/python3.12/os.py")                 // absolut ke dalam rootfs
        link("usr/jahat", "../../../rahasia.txt")                    // relatif keluar rootfs
        link("usr/jahat2", File(tmp, "rahasia.txt").path)            // absolut: dibaca sebagai path guest
        link("loop/a", "b"); link("loop/b", "a")
        link("usr/lib/python3.12/sitelink", "../python3/dist-packages")

        fun r(p: String) = RootfsPaths.resolve(rootfs, p)
        val os = File(rootfs.canonicalFile, "usr/lib/python3.12/os.py")
        check("rootfs: berkas biasa", r("/usr/lib/python3.12/os.py") == os)
        check("rootfs: tidak ada = null", r("/usr/lib/python3.12/tidak-ada.py") == null)
        check("rootfs: direktori = null", r("/usr/lib/python3.12/adir") == null)
        check("rootfs: path relatif = null", r("usr/lib/python3.12/os.py") == null)
        check("rootfs: root saja = null", r("/") == null)
        check("rootfs: symlink direktori relatif", r("/lib/python3.12/os.py") == os)
        check("rootfs: symlink berkas absolut dibaca relatif ke rootfs", r("/etc/alt") == os)
        check("rootfs: symlink berkas relatif", r("/usr/bin/python3") == File(rootfs.canonicalFile, "usr/bin/python3.12"))
        check("rootfs: symlink direktori lewat ..", r("/usr/lib/python3.12/sitelink/pkg/mod.py") == File(rootfs.canonicalFile, "usr/lib/python3/dist-packages/pkg/mod.py"))
        check("rootfs: .. tidak keluar dari rootfs", r("/../../../usr/lib/python3.12/os.py") == os)
        check("rootfs: .. berlebih menuju rahasia = null", r("/../rahasia.txt") == null)
        check("rootfs: symlink relatif keluar rootfs dijepit (bukan membaca rahasia)", r("/usr/jahat") == null)
        check("rootfs: symlink absolut ke path host = null", r("/usr/jahat2") == null)
        check("rootfs: symlink berputar = null", r("/loop/a") == null)
        check("rootfs: titik dan garis ganda diabaikan", r("/usr//lib/./python3.12/os.py") == os)
        check("rootfs: NUL ditolak", r("/usr/lib/python3.12/os.py\u0000") == null)
        check("rootfs: hasil selalu di bawah rootfs", listOf("/lib/python3.12/os.py", "/etc/alt", "/usr/bin/python3").all { p -> r(p)?.let { RootfsPaths.isUnder(rootfs, it) } == true })
        check("rootfs: rootfs tidak ada = null", RootfsPaths.resolve(File(tmp, "kosong"), "/usr/lib/python3.12/os.py") == null)
        check("isUnder: di dalam", RootfsPaths.isUnder(rootfs, File(rootfs, "usr/lib/python3.12/os.py")))
        check("isUnder: symlink ke berkas host di luar = bukan", !RootfsPaths.isUnder(rootfs, File(rootfs, "usr/jahat2")))
        check("isUnder: awalan nama mirip bukan anak", !RootfsPaths.isUnder(rootfs, File(tmp, "debian-lain/x.py")))
        check("isUnder: rootfs sendiri bukan anak", !RootfsPaths.isUnder(rootfs, rootfs))
        check("isUnder: di luar", !RootfsPaths.isUnder(rootfs, File(tmp, "rahasia.txt")))

        // ------------------------------------------------------------ classifyDebian
        fun tg(uri: String, line: Int, col: Int = 0) = LspDefinitionTarget(null, uri, line, col)
        val res = { g: String -> r(g) }
        val one = LspDefinitionLogic.classifyDebian(listOf(tg("file:///usr/lib/python3.12/os.py", 11)), res)
        check("debian: satu tujuan = Jump ke berkas rootfs", one == DefinitionOutcome.Jump(os.path, 11, 0))
        val none = LspDefinitionLogic.classifyDebian(listOf(tg("file:///usr/lib/python3.12/hilang.py", 1), tg("file:///usr/lib/x.py", 2)), res)
        check("debian: tak satu pun ada = OnlyInDebian dengan path pertama", none == DefinitionOutcome.OnlyInDebian("/usr/lib/python3.12/hilang.py", 2))
        check("debian: OnlyInDebian tanpa target = jalur kosong", LspDefinitionLogic.classifyDebian(emptyList(), res) == DefinitionOutcome.OnlyInDebian("", 0))
        val two = LspDefinitionLogic.classifyDebian(listOf(
            tg("file:///usr/lib/python3.12/os.py", 5), tg("file:///usr/lib/python3/dist-packages/pkg/mod.py", 1), tg("file:///usr/lib/python3.12/os.py", 5),
        ), res)
        check("debian: beberapa tujuan = Choose, duplikat dibuang", two is DefinitionOutcome.Choose && two.choices.size == 2)
        check("debian: label berawalan [Debian] dan baris basis 1", two is DefinitionOutcome.Choose && two.choices.any { it.label == "[Debian] /usr/lib/python3.12/os.py:6" })
        val partial = LspDefinitionLogic.classifyDebian(listOf(tg("file:///usr/lib/python3.12/os.py", 5), tg("file:///usr/lib/python3.12/hilang.py", 1), tg("bukan-uri", 3)), res)
        check("debian: tujuan yang hilang/uri rusak dilewati", partial == DefinitionOutcome.Jump(os.path, 5, 0))
        check("debian: label murni", LspDefinitionLogic.labelDebian("/usr/x.py", 0) == "[Debian] /usr/x.py:1")
        check("debian: pesan OnlyInDebian menyebut rootfs", LspDefinitionLogic.message(DefinitionOutcome.OnlyInDebian("/usr/x.py", 1))!!.contains("rootfs"))
    } finally {
        tmp.deleteRecursively()
    }

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails > 0) System.exit(1)
}

package com.molina.explorer

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    val yes: (String) -> Boolean = { true }

    val h = NavHistory()
    check("awal: kosong", !h.canBack && !h.canForward)
    h.visit("/a", "/b"); h.visit("/b", "/c")
    check("visit: bisa mundur", h.canBack && !h.canForward)
    check("back: ke folder sebelumnya", h.back("/c", yes) == "/b")
    check("back: current masuk riwayat maju", h.canForward)
    check("forward: kembali ke /c", h.forward("/b", yes) == "/c")
    check("forward habis", !h.canForward)

    val h2 = NavHistory()
    h2.visit("/a", "/b"); h2.back("/b", yes)
    h2.visit("/a", "/z")
    check("visit baru mengosongkan maju", !h2.canForward)

    val h3 = NavHistory()
    h3.visit("/a", "/a")
    check("folder sama tidak dicatat", !h3.canBack)

    val h4 = NavHistory()
    h4.visit("/a", "/b"); h4.visit("/b", "/gone"); h4.visit("/gone", "/d")
    val r = h4.back("/d") { it != "/gone" }
    check("back melewati folder hilang", r == "/b")
    check("riwayat maju hanya berisi folder saat ini", h4.canForward && h4.forward("/b", yes) == "/d")

    val h5 = NavHistory()
    h5.visit("/a", "/b")
    check("back tanpa folder valid -> null", h5.back("/b") { false } == null)
    check("setelah itu riwayat mundur kosong", !h5.canBack)

    val h6 = NavHistory(cap = 3)
    for (i in 0 until 10) h6.visit("/d$i", "/d${i + 1}")
    var n = 0
    var cur = "/d10"
    while (true) { val p = h6.back(cur, yes) ?: break; cur = p; n++ }
    check("batas riwayat 3, terlama dibuang", n == 3 && cur == "/d7")

    if (fails > 0) { println("$fails gagal"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}

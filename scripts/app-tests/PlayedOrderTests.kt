package com.molina.explorer

import java.io.File

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private fun f(path: String) = W7Entry(File(path), false, 0L, 0L)
private fun d(path: String) = W7Entry(File(path), true, 0L, 0L)
private fun names(l: List<W7Entry>) = l.joinToString(",") { it.file.name }

fun main() {
    check("norm: /sdcard/ jadi jalur penyimpanan", PlayedOrder.norm("/sdcard/Movies/a.mkv") == "/storage/emulated/0/Movies/a.mkv")
    check("norm: jalur lain tetap", PlayedOrder.norm("/storage/emulated/0/a") == "/storage/emulated/0/a" && PlayedOrder.norm("/data/x") == "/data/x")
    check("norm: /sdcard tanpa garis miring tidak diubah", PlayedOrder.norm("/sdcard") == "/sdcard")

    val a = f("/m/a.mkv"); val b = f("/m/b.mkv"); val c = f("/m/c.mkv"); val z = f("/m/z.mkv")
    val dirB = d("/m/dirB"); val dirA = d("/m/dirA")

    val r1 = PlayedOrder.sort(listOf(a, b, c), listOf("/m/c.mkv", "/m/a.mkv"))
    check("berkas menurut peringkat putar, belum diputar di belakang", names(r1) == "c.mkv,a.mkv,b.mkv")

    val r2 = PlayedOrder.sort(listOf(a, dirB, b, dirA), listOf("/m/b.mkv"))
    check("folder tetap di atas dengan urutan asal", names(r2) == "dirB,dirA,b.mkv,a.mkv")

    val r3 = PlayedOrder.sort(listOf(a, b, z), emptyList())
    check("tanpa riwayat: urutan asal", names(r3) == "a.mkv,b.mkv,z.mkv")

    val r4 = PlayedOrder.sort(listOf(a, b), listOf("/m/b.mkv", "/m/a.mkv", "/m/b.mkv"))
    check("duplikat riwayat: kemunculan pertama menang", names(r4) == "b.mkv,a.mkv")

    val r5 = PlayedOrder.sort(listOf(f("/sdcard/m/x.mkv"), f("/sdcard/m/y.mkv")), listOf(PlayedOrder.norm("/sdcard/m/y.mkv")))
    check("entri /sdcard/ cocok dengan riwayat ternormalisasi", names(r5) == "y.mkv,x.mkv")

    val r6 = PlayedOrder.sort(listOf(a, b, c), listOf("/m/a.mkv"))
    check("belum diputar: urutan asal stabil", names(r6) == "a.mkv,b.mkv,c.mkv")

    check("daftar kosong", PlayedOrder.sort(emptyList(), listOf("/m/a.mkv")).isEmpty())
    check("UNPLAYED di belakang peringkat riwayat wajar", PlayedOrder.UNPLAYED > 50)

    if (fails > 0) { println("$fails gagal"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}

// Pengganti FileOps (aslinya butuh pustaka arsip): hanya Ctl, dua galat yang diharapkan, dan deleteAll sederhana.
package com.molina.core.fileops

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

object FileOps {
    class Ctl(private val cancel: AtomicBoolean, private val report: (Float) -> Unit) {
        @Volatile var total = 0L
        fun check() { if (cancel.get()) throw IOException("Dibatalkan") }
        fun cancelled(): Boolean = cancel.get()
        fun add(n: Long) { report(0f) }
    }

    class NeedPassword : IOException("Arsip ini dilindungi kata sandi"), ExpectedFailure
    class WrongPassword : IOException("Kata sandi salah"), ExpectedFailure

    fun deleteAll(files: List<File>, guard: Set<String>, ctl: Ctl): Int {
        var n = 0
        for (f in files) {
            ctl.check()
            if (f.absolutePath in guard) throw IOException("Dilindungi: " + f.name)
            if (f.deleteRecursively()) n++
        }
        return n
    }
}

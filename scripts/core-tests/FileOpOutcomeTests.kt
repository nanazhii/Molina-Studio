import com.molina.core.fileops.ExpectedFailure
import com.molina.core.fileops.FileOpOutcome
import java.io.IOException

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private class Expected : IOException("sandi diminta"), ExpectedFailure

fun main() {
    check("failureText: memakai pesan", FileOpOutcome.failureText(IOException("boom")) == "boom")
    check("failureText: tanpa pesan -> nama kelas", FileOpOutcome.failureText(IllegalStateException()) == "Operasi gagal (IllegalStateException)")
    check("shouldLog: galat biasa dicatat", FileOpOutcome.shouldLog(IOException("boom")) && FileOpOutcome.shouldLog(RuntimeException()))
    check("shouldLog: ExpectedFailure tidak dicatat", !FileOpOutcome.shouldLog(Expected()))
    check("shouldLog: pembatalan tidak dicatat", !FileOpOutcome.shouldLog(IOException(FileOpOutcome.CANCELLED)))
    check("CANCELLED sama dengan pesan Ctl.check", FileOpOutcome.CANCELLED == "Dibatalkan")
    val t = FileOpOutcome.trace(IOException("x"))
    val lines = t.split("\n")
    check("trace: baris pertama jenis + pesan", lines.first() == "java.io.IOException: x")
    check("trace: paling banyak 8 baris tumpukan", lines.size - 1 in 1..8 && lines.drop(1).all { it.startsWith("  at ") })
    check("trace: tanpa pesan -> '-'", FileOpOutcome.trace(RuntimeException()).startsWith("java.lang.RuntimeException: -"))
    if (fails > 0) { println("$fails gagal"); kotlin.system.exitProcess(1) }
    println("semua lulus")
}

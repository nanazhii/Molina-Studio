import com.molina.core.diag.PlaybackSessionId
import com.molina.core.diag.PlaybackSessions
import java.util.Collections

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    PlaybackSessions.resetForTest()
    check("awal: tidak ada sesi aktif", PlaybackSessions.current() == null && PlaybackSessions.prefix() == "")
    val a = PlaybackSessions.begin()
    check("begin: id pertama 1, label s001", a.value == 1 && a.label == "s001")
    check("begin: menjadi sesi aktif dan awalan log", PlaybackSessions.current() == a && PlaybackSessions.prefix() == "[s001] ")
    check("label: lebar tetap 3 digit, melebar bila lebih", PlaybackSessionId(7).label == "s007" && PlaybackSessionId(123).label == "s123" && PlaybackSessionId(4567).label == "s4567")
    check("end: sesi aktif ditutup sekali", PlaybackSessions.end(a) && PlaybackSessions.current() == null && PlaybackSessions.prefix() == "")
    check("end: END_FILE ganda tidak berbuat apa pun", !PlaybackSessions.end(a))
    val b = PlaybackSessions.begin()
    val c = PlaybackSessions.begin()
    check("begin: id naik terus, sesi baru mengganti yang belum diakhiri", b.value == 2 && c.value == 3 && PlaybackSessions.current() == c)
    check("end: sesi lama yang terlambat tidak menutup sesi baru", !PlaybackSessions.end(b) && PlaybackSessions.current() == c)
    check("end: sesi baru bisa ditutup", PlaybackSessions.end(c) && PlaybackSessions.current() == null)

    // pencacah melingkar: setelah MAX_ID kembali ke 1, tidak pernah 0
    PlaybackSessions.resetForTest()
    repeat(PlaybackSessions.MAX_ID) { PlaybackSessions.begin() }
    check("lingkar: id ke-MAX_ID = 999999", PlaybackSessions.current()?.value == PlaybackSessions.MAX_ID)
    val w = PlaybackSessions.begin()
    check("lingkar: sesudah MAX_ID kembali ke 1 (bukan 0)", w.value == 1 && w.label == "s001")

    // keamanan thread: id unik dari banyak thread
    PlaybackSessions.resetForTest()
    val seen = Collections.synchronizedSet(HashSet<Int>())
    val threads = (1..8).map { Thread { repeat(1000) { seen.add(PlaybackSessions.begin().value) } } }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    check("thread: 8 x 1000 begin menghasilkan 8000 id unik", seen.size == 8000)

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}

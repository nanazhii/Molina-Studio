import com.molina.core.library.StallTracker

// Tes JVM murni untuk StallTracker (keputusan penurunan kualitas otomatis, E-3b-3b).

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    run {
        val t = StallTracker()
        check("E-3b-3b: dua macet belum memicu, ketiga memicu", !t.recordStall(0) && !t.recordStall(10_000) && t.recordStall(20_000))
    }
    run {
        val t = StallTracker()
        t.recordStall(0); t.recordStall(30_000)
        check("E-3b-3b: batas jendela tepat 60 dtk masih dihitung", t.recordStall(60_000) && t.stallCount == 3)
    }
    run {
        val t = StallTracker()
        t.recordStall(0); t.recordStall(30_000)
        check("E-3b-3b: macet lebih dari 60 dtk dibuang dari hitungan", !t.recordStall(60_001) && t.stallCount == 2)
    }
    run {
        val t = StallTracker()
        check("E-3b-3b: penurunan pertama boleh walau waktu masih kecil (belum ada jeda)", t.tryBeginShift(0))
    }
    run {
        val t = StallTracker()
        check("E-3b-3b: penurunan kedua ditolak saat yang pertama berjalan", t.tryBeginShift(1_000) && !t.tryBeginShift(2_000) && t.isShifting)
    }
    run {
        val t = StallTracker()
        t.recordStall(0); t.recordStall(1); t.recordStall(2)
        t.tryBeginShift(1_000); t.abortShift()
        check("E-3b-3b: abort tidak mengosongkan hitungan dan tidak memulai jeda", t.stallCount == 3 && !t.isShifting && t.tryBeginShift(1_001))
    }
    run {
        val t = StallTracker()
        t.recordStall(0); t.recordStall(1); t.recordStall(2)
        t.tryBeginShift(1_000); t.commitShift()
        check("E-3b-3b: commit mengosongkan hitungan macet", t.stallCount == 0 && !t.isShifting && !t.recordStall(5_000))
    }
    run {
        val t = StallTracker()
        t.tryBeginShift(1_000); t.commitShift()
        check("E-3b-3b: jeda 30 dtk dihitung dari waktu mulai (29.999 ditolak, 30.000 boleh)", !t.tryBeginShift(30_999) && t.tryBeginShift(31_000))
    }
    run {
        val t = StallTracker()
        t.tryBeginShift(1_000); t.commitShift(); t.commitShift()
        check("E-3b-3b: commit tanpa penurunan berjalan diabaikan", !t.isShifting && !t.tryBeginShift(2_000))
    }
    run {
        val t = StallTracker()
        t.commitShift()
        check("E-3b-3b: commit tanpa begin tidak memulai jeda", t.tryBeginShift(0))
    }
    check("E-3b-3b: konstanta (60 dtk, 3x, jeda 30 dtk, macet panjang 8 dtk)",
        StallTracker.WINDOW_MS == 60_000L && StallTracker.THRESHOLD == 3 && StallTracker.COOLDOWN_MS == 30_000L && StallTracker.LONG_STALL_MS == 8_000L)

    println(if (fails == 0) "SEMUA LULUS" else "$fails GAGAL")
    if (fails != 0) kotlin.system.exitProcess(1)
}

import com.molina.cinema.QualityRule

// Tes JVM murni untuk QualityRule (aturan mode kualitas efektif, E-3c-2).

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

private enum class Q { AUTO, P720, AUDIO }

fun main() {
    check("audioOnlyActive: keduanya false", !QualityRule.audioOnlyActive(false, false))
    check("audioOnlyActive: forced saja", QualityRule.audioOnlyActive(true, false))
    check("audioOnlyActive: UI audio saja", QualityRule.audioOnlyActive(false, true))
    check("audioOnlyActive: keduanya true", QualityRule.audioOnlyActive(true, true))

    check("effective: tanpa audio-only memakai kunci AUTO", QualityRule.effective(false, false, Q.AUDIO, Q.AUTO) == Q.AUTO)
    check("effective: tanpa audio-only memakai kunci 720p", QualityRule.effective(false, false, Q.AUDIO, Q.P720) == Q.P720)
    check("effective: forced mengalahkan kunci", QualityRule.effective(true, false, Q.AUDIO, Q.P720) == Q.AUDIO)
    check("effective: UI audio mengalahkan kunci", QualityRule.effective(false, true, Q.AUDIO, Q.P720) == Q.AUDIO)
    check("effective: keduanya true tetap audio", QualityRule.effective(true, true, Q.AUDIO, Q.AUTO) == Q.AUDIO)
    check("effective: kunci AUDIO tersimpan tanpa flag tetap AUDIO", QualityRule.effective(false, false, Q.AUDIO, Q.AUDIO) == Q.AUDIO)

    if (fails > 0) { println("TOTAL GAGAL: $fails"); kotlin.system.exitProcess(1) }
    println("SEMUA LULUS")
}

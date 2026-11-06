import com.molina.cinema.*
import kotlin.math.abs
import kotlin.random.Random
import kotlin.system.exitProcess

/** Uji JVM murni LyricsAutoSync: data sintetis, kesalahan rata-rata (ms) estimasi vs penyelarasan spektrum. */
private fun scenario(correlated: Boolean, vocalAdd: Float, noise: Float, seed: Int): Pair<Double, Double> {
    val fps = 30.0
    val rnd = Random(seed)
    val ests = ArrayList<Double>(); val als = ArrayList<Double>()
    repeat(15) {
        val dur = 210_000L
        val n = 36
        val texts = List(n) { "x".repeat(rnd.nextInt(14, 44)) }
        val trueStart = LongArray(n)
        var t = 14_000L + rnd.nextLong(-3000, 3000)
        for (k in 0 until n) {
            trueStart[k] = t
            t += if (correlated) ((texts[k].length + 8) * 95L * rnd.nextDouble(0.75, 1.25)).toLong() else rnd.nextLong(2200, 5200)
            if (k == 17) t += 14_000 // jeda instrumental di tengah
        }
        val env = FloatArray((dur * fps / 1000).toInt()) { i ->
            val ms = i * 1000.0 / fps
            var v = 0.45f // instrumen dasar
            for (k in 0 until n) {
                val e = if (k + 1 < n) trueStart[k + 1] - 350 else trueStart[k] + 4000
                if (ms in trueStart[k].toDouble()..e.toDouble()) v += vocalAdd
            }
            (v + rnd.nextFloat() * noise).coerceIn(0f, 1f)
        }
        fun mae(l: List<LyricLine>) = (0 until n).map { abs(l[it].startMs - trueStart[it]).toDouble() }.average()
        val est = LyricsAutoSync.estimate(texts, dur)
        ests.add(mae(est))
        als.add(LyricsAutoSync.align(texts, env, fps, 0, dur)?.let { mae(it) } ?: mae(est))
    }
    return ests.average() to als.average()
}

fun main() {
    var fail = 0
    fun check(name: String, ok: Boolean, info: String) { println("${if (ok) "OK   " else "GAGAL"} $name  $info"); if (!ok) fail++ }

    val (e1, a1) = scenario(true, 0.45f, 0.04f, 2)
    check("kontras kuat: galat < 1500 ms", a1 < 1500, "estimasi=%.0f align=%.0f".format(e1, a1))
    val (e2, a2) = scenario(true, 0.25f, 0.10f, 3)
    check("kontras sedang: galat < 3000 ms", a2 < 3000, "estimasi=%.0f align=%.0f".format(e2, a2))
    val (e3, a3) = scenario(true, 0.12f, 0.15f, 4)
    check("kontras lemah: tetap lebih baik dari estimasi", a3 < e3 * 0.6, "estimasi=%.0f align=%.0f".format(e3, a3))

    check("terlalu sedikit baris -> null", LyricsAutoSync.align(listOf("a", "b"), FloatArray(9000), 30.0, 0, 200_000L) == null, "")
    check("envelope terlalu pendek -> null", LyricsAutoSync.align(List(10) { "abc" }, FloatArray(30), 30.0, 0, 200_000L) == null, "")
    val est = LyricsAutoSync.estimate(List(10) { "baris $it" }, 120_000L)
    check("estimasi menaik tegas", est.zipWithNext().all { (a, b) -> b.startMs > a.startMs }, "")
    val env = LyricsAutoSync.vocalEnvelope(ByteArray(32 * 100) { (it % 256).toByte() }, 100, 32, 44_100)
    check("envelope 0..1", env.size == 100 && env.all { it in 0f..1f }, "")

    if (fail > 0) { println("$fail uji gagal"); exitProcess(1) }
    println("semua uji lulus")
}

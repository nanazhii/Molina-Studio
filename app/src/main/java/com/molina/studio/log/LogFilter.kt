package com.molina.studio.log

import com.molina.core.common.LogLevel

/**
 * [LOGCAT-FILTER] Filter log ala `adb logcat`, dari satu kolom cari. Token dipisah spasi, semuanya digabung (AND):
 *  - kata            : pesan/tag/sumber memuat kata (abaikan huruf besar/kecil); "frasa dengan spasi" pakai tanda kutip
 *  - -kata           : kecualikan
 *  - tag:Mpv         : tag memuat teks itu; -tag:Mpv untuk mengecualikan
 *  - pid:123 tid:456 : hanya PID/TID itu (baris logcat mentah)
 *  - level:W         : prioritas minimum V/D/I/W/E/F (prio: sama saja)
 *  - /regex/         : regex pada pesan (seperti `logcat -e`)
 *  - Tag:W  *:S      : filterspec adb persis; tag harus sama persis, tanpa `*` tag lain tetap tampil (seperti adb)
 * Kotlin murni (tanpa Android) agar mudah diuji.
 */
class LogFilter private constructor(
    private val words: List<String>,
    private val notWords: List<String>,
    private val tags: List<String>,
    private val notTags: List<String>,
    private val wantPid: Int?,
    private val wantTid: Int?,
    private val minRank: Int,
    private val regex: Regex?,
    private val specs: Map<String, Int>,
    private val defaultRank: Int,
    /** Kata pertama yang disorot di daftar ("" bila tidak ada). */
    val highlight: String,
    /** Pesan galat sintaks (mis. regex rusak); token bermasalah diabaikan. */
    val error: String?
) {
    private val isEmpty = words.isEmpty() && notWords.isEmpty() && tags.isEmpty() && notTags.isEmpty() &&
        wantPid == null && wantTid == null && minRank == 0 && regex == null && specs.isEmpty() && defaultRank == 0

    fun matches(source: String, tag: String, message: String, pid: Int, tid: Int, rank: Int): Boolean {
        if (isEmpty) return true
        if (rank < minRank) return false
        if (wantPid != null && pid != wantPid) return false
        if (wantTid != null && tid != wantTid) return false
        if (specs.isNotEmpty() || defaultRank > 0) {
            val need = specs[tag] ?: defaultRank
            if (rank < need) return false
        }
        if (tags.isNotEmpty() && tags.none { tag.contains(it, ignoreCase = true) }) return false
        if (notTags.any { tag.contains(it, ignoreCase = true) }) return false
        for (w in words) {
            if (!(message.contains(w, ignoreCase = true) || tag.contains(w, ignoreCase = true) ||
                    source.contains(w, ignoreCase = true))
            ) return false
        }
        for (w in notWords) {
            if (message.contains(w, ignoreCase = true) || tag.contains(w, ignoreCase = true) ||
                source.contains(w, ignoreCase = true)
            ) return false
        }
        if (regex != null && !regex.containsMatchIn(message)) return false
        return true
    }

    fun matches(e: LogEntry): Boolean =
        matches(e.source, e.tag, e.message, e.pid, e.tid, rankOf(e.prio, e.level))

    companion object {
        private val TOKEN = Regex("\"([^\"]*)\"|(\\S+)")
        private val SPEC = Regex("^(\\*|[^\\s:*]+):([VDIWEFSvdiwefs])$")

        /** V=0 D=1 I=2 W=3 E=4 F=5 S=6 (senyap). Entri non-logcat memakai LogLevel. */
        fun rankOfLetter(c: Char): Int = when (c.uppercaseChar()) {
            'V' -> 0; 'D' -> 1; 'I' -> 2; 'W' -> 3; 'E' -> 4; 'F', 'A' -> 5; 'S' -> 6
            else -> -1
        }

        fun rankOf(prio: Char, level: LogLevel): Int {
            val r = if (prio == '\u0000') -1 else rankOfLetter(prio)
            if (r >= 0) return r
            return when (level) {
                LogLevel.Debug -> 1
                LogLevel.Info -> 2
                LogLevel.Warn -> 3
                LogLevel.Error -> 4
            }
        }

        fun parse(q: String): LogFilter {
            val words = ArrayList<String>()
            val notWords = ArrayList<String>()
            val tags = ArrayList<String>()
            val notTags = ArrayList<String>()
            val specs = HashMap<String, Int>()
            var defaultRank = 0
            var pid: Int? = null
            var tid: Int? = null
            var minRank = 0
            var regex: Regex? = null
            var err: String? = null
            for (m in TOKEN.findAll(q)) {
                val quoted = m.groups[1]
                if (quoted != null) {
                    if (quoted.value.isNotEmpty()) words.add(quoted.value)
                    continue
                }
                val t = m.groupValues[2]
                if (t.length > 2 && t.startsWith("/") && t.endsWith("/")) {
                    try {
                        regex = Regex(t.substring(1, t.length - 1), RegexOption.IGNORE_CASE)
                    } catch (e: java.util.regex.PatternSyntaxException) {
                        err = "regex tidak valid"
                    }
                    continue
                }
                val neg = t.length > 1 && t.startsWith("-")
                val body = if (neg) t.substring(1) else t
                val low = body.lowercase()
                when {
                    low.startsWith("pid:") ->
                        body.substring(4).toIntOrNull()?.let { pid = it } ?: run { err = "pid harus angka" }
                    low.startsWith("tid:") ->
                        body.substring(4).toIntOrNull()?.let { tid = it } ?: run { err = "tid harus angka" }
                    low.startsWith("level:") || low.startsWith("prio:") -> {
                        val c = body.substringAfter(':').firstOrNull()
                        val r = if (c == null || body.substringAfter(':').length != 1) -1 else rankOfLetter(c)
                        if (r in 0..5) minRank = r else err = "level: pakai V, D, I, W, E, atau F"
                    }
                    low.startsWith("tag:") -> {
                        val v = body.substring(4).lowercase()
                        if (v.isNotEmpty()) (if (neg) notTags else tags).add(v)
                    }
                    !neg && SPEC.matches(body) -> {
                        val sm = SPEC.find(body)!!
                        val r = rankOfLetter(sm.groupValues[2][0])
                        if (sm.groupValues[1] == "*") defaultRank = r else specs[sm.groupValues[1]] = r
                    }
                    body.isNotEmpty() -> (if (neg) notWords else words).add(body)
                }
            }
            return LogFilter(
                words, notWords, tags, notTags, pid, tid, minRank, regex, specs, defaultRank,
                words.firstOrNull() ?: "", err
            )
        }
    }
}

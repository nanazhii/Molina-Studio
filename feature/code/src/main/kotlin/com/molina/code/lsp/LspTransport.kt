package com.molina.code.lsp

/**
 * [CODE-LSP2] Kontrak transport proses menetap dengan stdin/stdout dua arah (mis. language server di Debian).
 * Kotlin murni: :feature:code tidak tahu PRoot; :app memasang implementasinya (ProotScriptExecutor.startStream).
 */
interface StreamExecutor {
    /** Mulai proses dan segera kembali. Kegagalan memulai dilaporkan lewat [StreamListener.onExit] (boleh sinkron). */
    fun startStream(request: StreamRequest, listener: StreamListener): StreamHandle
}

/**
 * [guestArgv] dijalankan APA ADANYA di guest (tanpa shell); argv[0] dicari di PATH guest.
 * [hostDir] folder di Android untuk cwd (null = /root). [env] divalidasi di :app (ScriptCommand.validate).
 */
class StreamRequest(
    val name: String,
    val guestArgv: List<String>,
    val hostDir: String? = null,
    val env: Map<String, String> = emptyMap(),
    val missingHint: String? = null,
    /** Batas total karakter stderr yang diteruskan; kelebihannya dibuang (aliran tetap dikuras). */
    val maxStderrChars: Int = 64_000,
)

/** Semua callback datang dari thread latar. [onStdout] harus memakai [data] SEKARANG juga: buffer dipakai ulang. */
interface StreamListener {
    fun onStdout(data: ByteArray, length: Int)
    fun onStderr(text: String)

    /** Tepat sekali, setelah semua keluaran terkirim. */
    fun onExit(result: StreamResult)
}

/** [startError] terisi bila proses tidak pernah berjalan (pesannya siap tampil). */
class StreamResult(
    val exitCode: Int,
    val cancelled: Boolean,
    val millis: Long,
    val startError: String? = null,
)

interface StreamHandle {
    /**
     * Antre byte untuk stdin; tidak pernah memblokir. False bila input sudah ditutup, proses selesai,
     * atau antrean melewati batas (proses tidak membaca).
     */
    fun write(data: ByteArray): Boolean

    /** Tutup stdin (EOF) setelah semua yang sudah diantre terkirim. */
    fun closeInput()

    /** Matikan proses beserta seluruh turunannya. Aman dipanggil berkali-kali dan dari thread mana pun. */
    fun cancel()
}

/**
 * Stdout dari pembungkus sh diawali satu baris PID (lihat YtdlpCommand.SH_WRAPPER). Filter ini membuang baris itu
 * dari aliran byte dan melaporkan PID-nya; sisanya diteruskan utuh. Bila baris pertama bukan angka atau tidak
 * ada LF dalam [MAX_HEAD] byte, semuanya diteruskan apa adanya (tanpa PID).
 */
class LeadingPidFilter {
    private var head = ByteArray(MAX_HEAD)
    private var headLen = 0
    private var done = false

    fun feed(data: ByteArray, n: Int, onPid: (Int) -> Unit, onData: (ByteArray, Int) -> Unit) {
        if (n <= 0) return
        if (done) {
            onData(data, n)
            return
        }
        var i = 0
        while (i < n && !done) {
            val b = data[i]
            if (b == '\n'.code.toByte()) {
                done = true
                val pid = String(head, 0, headLen, Charsets.US_ASCII).trim().toIntOrNull()
                if (pid != null) {
                    onPid(pid)
                    i++ // buang LF
                    break
                }
                // bukan PID: kembalikan baris pertama beserta LF-nya
                val first = head.copyOf(headLen + 1)
                first[headLen] = b
                onData(first, first.size)
                i++
                break
            }
            if (headLen >= MAX_HEAD) {
                done = true
                onData(head.copyOf(headLen), headLen)
                break // byte ke-i ikut diteruskan lewat sisa di bawah
            }
            head[headLen++] = b
            i++
        }
        if (!done) return // masih mengumpulkan baris pertama
        headLen = 0
        if (i < n) onData(data.copyOfRange(i, n), n - i)
    }

    companion object { const val MAX_HEAD = 32 }
}

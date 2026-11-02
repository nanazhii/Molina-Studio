package com.molina.studio.cmd

/**
 * Protokol CLI `molina` (Debian -> aplikasi). Kotlin murni agar bisa diuji di JVM.
 * Satu permintaan per koneksi: "verb[TAB]argumen\n". Balasan satu baris:
 *   "OK[ teks]\n" atau "ERR kode teks\n".
 */
object CommandProtocol {
    /** Abstract-namespace Unix socket (sama gaya dengan @molina.log). */
    const val SOCKET_NAME = "molina.cmd"
    const val VERSION = 2
    const val MAX_LINE = 131072

    /** Batas argumen verb biasa; clip dan share punya batas sendiri (base64). */
    const val MAX_ARG = 4096

    enum class Verb(val wire: String) {
        PING("ping"), VERSION("version"), STATUS("status"), PLAY("play"), STOP("stop"), OPEN("open"), CLIP("clip"), NOTIFY("notify"), SHARE("share")
    }

    class Request(val verb: Verb, val arg: String)

    sealed interface Parsed {
        class Ok(val req: Request) : Parsed
        class Bad(val code: String, val message: String) : Parsed
    }

    fun parse(line: String): Parsed {
        val t = line.trimEnd('\r', '\n')
        if (t.isBlank()) return Parsed.Bad("empty", "perintah kosong")
        val tab = t.indexOf('\t')
        val word = (if (tab < 0) t else t.substring(0, tab)).trim().lowercase()
        val arg = if (tab < 0) "" else t.substring(tab + 1).trim()
        val verb = Verb.entries.firstOrNull { it.wire == word }
            ?: return Parsed.Bad("unknown", "perintah tidak dikenal: ${oneLine(word)}")
        if (verb != Verb.CLIP && verb != Verb.SHARE && arg.length > MAX_ARG) return Parsed.Bad("toolong", "argumen terlalu panjang")
        if ((verb == Verb.PLAY || verb == Verb.OPEN || verb == Verb.NOTIFY || verb == Verb.SHARE) && arg.isEmpty()) {
            return Parsed.Bad("usage", "${verb.wire} butuh argumen")
        }
        if (verb == Verb.NOTIFY && parseNotify(arg) == null) return Parsed.Bad("usage", "notify butuh teks")
        if (verb == Verb.CLIP) (parseClip(arg) as? ClipParse.Bad)?.let { return Parsed.Bad(it.code, it.message) }
        if (verb == Verb.SHARE) (parseShare(arg) as? ShareParse.Bad)?.let { return Parsed.Bad(it.code, it.message) }
        return Parsed.Ok(Request(verb, arg))
    }

    fun ok(text: String = ""): String = if (text.isEmpty()) "OK\n" else "OK ${oneLine(text)}\n"

    fun err(code: String, text: String): String = "ERR $code ${oneLine(text)}\n"

    /** Satu baris, tanpa TAB/CR/LF, maks 300 karakter (balasan dan log tidak boleh memecah protokol). */
    fun oneLine(s: String): String = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').take(300)

    /** Batas berkas per perintah open. */
    const val MAX_OPEN = 10

    /** Argumen open: path dipisah TAB; entri kosong dibuang. */
    fun splitPaths(arg: String): List<String> = arg.split('\t').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Path yang terlihat di Debian (/sdcard atau /storage/emulated/0) -> path Android di bawah
     * [storageRoot]. Null bila bukan path mutlak, mengandung NUL atau "..", atau di luar dua akar itu.
     */
    fun guestToHost(guest: String, storageRoot: String): String? {
        val g = guest.trim()
        if (!g.startsWith("/") || g.indexOf('\u0000') >= 0) return null
        val rest = when {
            g == "/sdcard" || g == "/storage/emulated/0" -> ""
            g.startsWith("/sdcard/") -> g.substring("/sdcard".length)
            g.startsWith("/storage/emulated/0/") -> g.substring("/storage/emulated/0".length)
            else -> return null
        }
        val parts = rest.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        val root = storageRoot.trimEnd('/')
        return if (parts.isEmpty()) root else root + "/" + parts.joinToString("/")
    }
    /**
     * [CLI-SCOPE] Path host (hasil [guestToHost], sudah tanpa "..") boleh dipakai bila [full] aktif, atau berada di bawah
     * salah satu folder [allowed] (nama folder langsung di bawah [storageRoot]). Tanpa ini, bridge CLI bisa menjangkau
     * berkas yang tidak terlihat di Debian saat "Akses penuh" mati. Pembandingan peka huruf besar-kecil: salah arah = tolak.
     */
    fun inScope(host: String, storageRoot: String, full: Boolean, allowed: List<String>): Boolean {
        if (full) return true
        val root = storageRoot.trimEnd('/')
        return allowed.any { name -> host == "$root/$name" || host.startsWith("$root/$name/") }
    }

    /** [CLI-SCOPE] True bila jeda sejak aksi sebelumnya cukup (rate limit aksi yang membuka UI). */
    fun gapOk(prevMs: Long, nowMs: Long, minGapMs: Long): Boolean = nowMs - prevMs >= minGapMs

    // ---- Bridge API Android: clip, notify, share (protokol v2) ----

    /** Batas teks clipboard/share (byte UTF-8 sebelum base64). */
    const val MAX_CLIP = 65536
    const val MAX_NOTIFY_TITLE = 64
    const val MAX_NOTIFY_TEXT = 500
    const val DEFAULT_NOTIFY_TITLE = "Molina"

    fun encodeB64(text: String): String =
        java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    /** Null bila bukan base64 baku atau bukan UTF-8 yang sah. */
    fun decodeB64(b64: String): String? = try {
        val bytes = java.util.Base64.getDecoder().decode(b64)
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: java.nio.charset.CharacterCodingException) {
        null
    }

    /** Balasan bermuatan panjang (tanpa pemotongan [oneLine]); muatan base64 tanpa spasi. */
    fun okPayload(payload: String): String =
        if (payload.isEmpty()) "OK\n" else "OK " + payload.filterNot { it.isWhitespace() } + "\n"

    sealed interface ClipParse {
        object Get : ClipParse
        class Put(val text: String) : ClipParse
        class Bad(val code: String, val message: String) : ClipParse
    }

    /** Argumen clip: "get" atau "set[TAB]<base64 UTF-8>" (maks [MAX_CLIP] byte; set tanpa muatan = kosongkan). */
    fun parseClip(arg: String): ClipParse {
        if (arg == "get") return ClipParse.Get
        if (arg == "set" || arg.startsWith("set\t")) {
            val b64 = if (arg == "set") "" else arg.substring(4)
            val tooBig = ClipParse.Bad("toolong", "teks clipboard lebih dari $MAX_CLIP byte")
            if (b64.length > (MAX_CLIP / 3 + 1) * 4) return tooBig
            val text = decodeB64(b64) ?: return ClipParse.Bad("usage", "muatan clip set bukan base64/UTF-8 yang sah")
            return if (text.toByteArray(Charsets.UTF_8).size > MAX_CLIP) tooBig else ClipParse.Put(text)
        }
        return ClipParse.Bad("usage", "clip butuh get atau set")
    }

    class NotifyMsg(val title: String, val text: String)

    /** [NOTIFY-SPOOF] Awalan tetap pada judul notifikasi dari CLI: proses di Debian tidak boleh meniru notifikasi resmi Molina. */
    const val NOTIFY_PREFIX = "CLI \u00b7 "

    fun notifyHeadline(title: String): String = NOTIFY_PREFIX + title

    /**
     * [NOTIFY-SPOOF] Buang karakter kontrol dan karakter format Unicode (pembalik arah teks U+202E, spasi lebar nol, BOM,
     * pemisah baris/paragraf) yang dipakai untuk menyamarkan teks. Baris baru dipertahankan hanya bila [keepNewline].
     */
    private fun cleanNotify(s: String, keepNewline: Boolean): String = s.filter { c ->
        if (c == '\n') keepNewline else {
            val t = Character.getType(c)
            !(c.isISOControl() || t == Character.FORMAT.toInt() ||
                t == Character.LINE_SEPARATOR.toInt() || t == Character.PARAGRAPH_SEPARATOR.toInt())
        }
    }

    /** Argumen notify: "teks" atau "judul[TAB]teks". Null bila teks kosong. */
    fun parseNotify(arg: String): NotifyMsg? {
        val tab = arg.indexOf('\t')
        val title = if (tab < 0) DEFAULT_NOTIFY_TITLE else cleanNotify(arg.substring(0, tab), false).trim().ifEmpty { DEFAULT_NOTIFY_TITLE }
        val text = cleanNotify((if (tab < 0) arg else arg.substring(tab + 1)).replace('\t', ' '), true).trim()
        if (text.isEmpty()) return null
        return NotifyMsg(title.take(MAX_NOTIFY_TITLE), text.take(MAX_NOTIFY_TEXT))
    }

    sealed interface ShareParse {
        class Text(val text: String) : ShareParse
        class Files(val paths: List<String>) : ShareParse
        class Bad(val code: String, val message: String) : ShareParse
    }

    /** Argumen share: "text[TAB]<base64>" atau "file[TAB]<path>[TAB]<path>..." (maks [MAX_OPEN] berkas). */
    fun parseShare(arg: String): ShareParse {
        val tab = arg.indexOf('\t')
        if (tab < 0) return ShareParse.Bad("usage", "share butuh text atau file")
        val rest = arg.substring(tab + 1)
        return when (arg.substring(0, tab)) {
            "text" -> {
                val t = decodeB64(rest.trim()) ?: return ShareParse.Bad("usage", "muatan share text bukan base64/UTF-8 yang sah")
                when {
                    t.isBlank() -> ShareParse.Bad("usage", "teks kosong")
                    t.toByteArray(Charsets.UTF_8).size > MAX_CLIP -> ShareParse.Bad("toolong", "teks lebih dari $MAX_CLIP byte")
                    else -> ShareParse.Text(t)
                }
            }
            "file" -> {
                val p = splitPaths(rest)
                when {
                    p.isEmpty() -> ShareParse.Bad("usage", "share file butuh minimal satu berkas")
                    p.size > MAX_OPEN -> ShareParse.Bad("many", "maksimal $MAX_OPEN berkas")
                    else -> ShareParse.Files(p)
                }
            }
            else -> ShareParse.Bad("usage", "share butuh text atau file")
        }
    }
}

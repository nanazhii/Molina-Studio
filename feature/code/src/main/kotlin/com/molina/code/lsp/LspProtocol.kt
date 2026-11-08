package com.molina.code.lsp

import com.molina.code.check.CheckException
import com.molina.code.check.MiniJson
import java.io.ByteArrayOutputStream

// [CODE-LSP1] Lapisan protokol LSP murni (tanpa Android, tanpa proses): framing Content-Length, penulis/pembaca JSON,
// URI berkas. Diuji di JVM: scripts/code-tests/run_lsp.sh. Dipakai LspClient; transport (proses Debian) menyusul di tahap 2.

/** Galat protokol: pembaca harus menganggap koneksi rusak dan mematikan server. */
class LspException(message: String) : Exception(message)

/** Penulis JSON kecil (Map/List/String/Boolean/angka/null) dan pembaca lewat [MiniJson]. Kedalaman dibatasi. */
object LspJson {
    private const val MAX_DEPTH = 64

    fun write(v: Any?): String {
        val sb = StringBuilder()
        put(sb, v, 0)
        return sb.toString()
    }

    fun parse(s: String): Any? = try {
        MiniJson.parse(s)
    } catch (e: CheckException) {
        throw LspException("JSON rusak: " + e.message)
    }

    private fun put(sb: StringBuilder, v: Any?, depth: Int) {
        if (depth > MAX_DEPTH) throw LspException("JSON terlalu dalam")
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> num(sb, v)
            is Float -> num(sb, v.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (k !is String) throw LspException("kunci JSON harus string")
                    if (!first) sb.append(',')
                    first = false
                    str(sb, k)
                    sb.append(':')
                    put(sb, value, depth + 1)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                for ((i, value) in v.withIndex()) {
                    if (i > 0) sb.append(',')
                    put(sb, value, depth + 1)
                }
                sb.append(']')
            }
            else -> throw LspException("tipe JSON tidak didukung: " + v.javaClass.simpleName)
        }
    }

    private fun num(sb: StringBuilder, d: Double) {
        when {
            d.isNaN() || d.isInfinite() -> sb.append("null")
            d == Math.rint(d) && Math.abs(d) < 1e15 -> sb.append(d.toLong().toString())
            else -> sb.append(d.toString())
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}

/** Framing LSP: `Content-Length: N\r\n\r\n` + N byte UTF-8. */
object LspFraming {
    fun encode(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        val head = ("Content-Length: " + body.size + "\r\n\r\n").toByteArray(Charsets.US_ASCII)
        return head + body
    }
}

/**
 * Pembaca bertahap: beri potongan byte berapa pun ukurannya ([feed]); setiap pesan utuh dikirim ke callback.
 * Setelah melempar [LspException] decoder tidak boleh dipakai lagi (server harus dimatikan).
 */
class LspDecoder(private val maxBody: Int = 8 shl 20, private val maxHeader: Int = 4096) {
    private var buf = ByteArray(4096)
    private var start = 0
    private var end = 0

    fun feed(data: ByteArray, off: Int, n: Int, onMessage: (String) -> Unit) {
        if (n > 0) append(data, off, n)
        while (true) {
            val hEnd = findHeaderEnd()
            if (hEnd < 0) {
                if (end - start > maxHeader) throw LspException("header terlalu panjang")
                return
            }
            if (hEnd - start > maxHeader) throw LspException("header terlalu panjang")
            val len = parseLength(start, hEnd)
            val bodyStart = hEnd + 4
            if (end - bodyStart < len) return // sisa pesan belum datang
            val body = String(buf, bodyStart, len, Charsets.UTF_8)
            start = bodyStart + len
            if (start == end) {
                start = 0
                end = 0
            }
            onMessage(body)
        }
    }

    private fun append(data: ByteArray, off: Int, n: Int) {
        if (off < 0 || n < 0 || off + n > data.size) throw LspException("potongan byte tidak valid")
        if ((end - start).toLong() + n > maxBody.toLong() + maxHeader + 16) throw LspException("pesan terlalu besar")
        if (end + n > buf.size && start > 0) {
            System.arraycopy(buf, start, buf, 0, end - start)
            end -= start
            start = 0
        }
        if (end + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, end + n))
        System.arraycopy(data, off, buf, end, n)
        end += n
    }

    private fun findHeaderEnd(): Int {
        var i = start
        val last = end - 4
        while (i <= last) {
            if (buf[i] == '\r'.code.toByte() && buf[i + 1] == '\n'.code.toByte() &&
                buf[i + 2] == '\r'.code.toByte() && buf[i + 3] == '\n'.code.toByte()
            ) return i
            i++
        }
        return -1
    }

    private fun parseLength(from: Int, hEnd: Int): Int {
        val head = String(buf, from, hEnd - from, Charsets.ISO_8859_1)
        for (line in head.split("\r\n")) {
            val c = line.indexOf(':')
            if (c <= 0) continue
            if (line.substring(0, c).trim().equals("content-length", ignoreCase = true)) {
                val n = line.substring(c + 1).trim().toIntOrNull() ?: throw LspException("Content-Length tidak valid")
                if (n < 0 || n > maxBody) throw LspException("Content-Length di luar batas")
                return n
            }
        }
        throw LspException("header tanpa Content-Length")
    }
}

/** URI `file://` <-> path. Path memakai '/' dan UTF-8; karakter selain huruf/angka/`-._~/` di-percent-encode. */
object LspUri {
    private const val HEX = "0123456789ABCDEF"

    fun fromPath(path: String): String {
        val sb = StringBuilder("file://")
        val norm = if (path.startsWith("/")) path else "/$path"
        for (b in norm.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 128 && (ch.isLetterOrDigit() || ch == '-' || ch == '.' || ch == '_' || ch == '~' || ch == '/')) {
                sb.append(ch)
            } else {
                sb.append('%').append(HEX[c shr 4]).append(HEX[c and 15])
            }
        }
        return sb.toString()
    }

    /** Null bila bukan `file://` atau ada percent-encoding rusak. */
    fun toPath(uri: String): String? {
        if (!uri.startsWith("file://")) return null
        val s = uri.substring(7)
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '%') {
                if (i + 3 > s.length) return null
                val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                out.write(v)
                i += 3
            } else {
                val cp = s.codePointAt(i)
                val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                out.write(bytes, 0, bytes.size)
                i += Character.charCount(cp)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

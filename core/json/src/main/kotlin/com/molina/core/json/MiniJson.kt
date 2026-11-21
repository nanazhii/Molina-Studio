package com.molina.core.json

/**
 * Cara membaca angka. AUTO: bilangan bulat menjadi Long bila muat, selain itu Double.
 * ALWAYS_DOUBLE: semua angka Double (kontrak lama LSP dan shellcheck).
 */
enum class JsonNumbers { AUTO, ALWAYS_DOUBLE }

/** Galat baca JSON. Pesan stabil: "JSON rusak", "sisa teks", "terlalu besar". */
class JsonException(message: String) : RuntimeException(message)

/** Batas dan mode baca. [maxDepth] = tingkat sarang wadah ({} atau []) tertinggi yang diterima. */
class JsonLimits(val maxChars: Int, val maxDepth: Int, val numbers: JsonNumbers) {
    companion object {
        /** Keluaran yt-dlp: ukuran dibatasi, bilangan bulat tetap Long. */
        val YTDLP = JsonLimits(4_000_000, 32, JsonNumbers.AUTO)

        /** Keluaran alat luar dan pesan LSP: angka selalu Double. */
        val TOOL = JsonLimits(Int.MAX_VALUE, 64, JsonNumbers.ALWAYS_DOUBLE)

        /** Berkas JSON milik pengguna (validasi sintaks). */
        val EDITOR = JsonLimits(Int.MAX_VALUE, 200, JsonNumbers.AUTO)
    }
}

/**
 * Satu-satunya pembaca JSON Molina (RFC 8259 ketat, tanpa dependensi, JVM murni).
 * Tanpa komentar, koma menggantung, kutip tunggal, NaN/Infinity, angka berawalan nol, atau karakter kontrol mentah dalam string.
 */
object MiniJson {
    /** Nilai tingkat-atas apa pun: Map, List, String, Boolean, angka, atau null. Rusak -> [JsonException]. */
    fun parseValue(text: String, limits: JsonLimits = JsonLimits.TOOL): Any? {
        if (text.length > limits.maxChars) throw JsonException("terlalu besar")
        return JsonCursor(text, limits, true).readDocument()
    }

    /** Hanya objek tingkat-atas; rusak, terlalu besar, atau bukan objek -> null. */
    fun parseObject(text: String, limits: JsonLimits = JsonLimits.YTDLP): Map<String, Any?>? = try {
        @Suppress("UNCHECKED_CAST")
        parseValue(text, limits) as? Map<String, Any?>
    } catch (e: JsonException) {
        null
    }

    /** Pemeriksa sintaks saja (tanpa membangun pohon). BOM di awal dibuang; angka di luar jangkauan Double tetap sah. */
    fun isValid(text: String, limits: JsonLimits = JsonLimits.EDITOR): Boolean {
        val s = text.removePrefix("\uFEFF")
        if (s.length > limits.maxChars) return false
        return try {
            JsonCursor(s, limits, false).readDocument()
            true
        } catch (e: JsonException) {
            false
        }
    }
}

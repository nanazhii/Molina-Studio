package com.molina.code.guard

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// [CODE-PROJECT] Penanda proyek lokal tanpa Git: folder `.molina/` berisi `project.json`. Kotlin murni (tanpa Android)
// agar bisa diuji di JVM: scripts/code-tests/run_project.sh. Dipakai [RunReport.findRootInfo] sebagai penanda kuat.

object ProjectMarker {
    const val DIR = ".molina"
    const val FILE = "project.json"
    const val FORMAT = 1

    enum class Result { CREATED, ALREADY, FAILED }

    /** Nama penanda kuat yang sudah ada TEPAT di [dir] (".git" atau ".molina"), atau null. */
    fun markerIn(dir: File): String? = when {
        File(dir, ".git").exists() -> ".git"
        File(dir, DIR).exists() -> DIR
        else -> null
    }

    /**
     * Buat `.molina/project.json` di [dir]. Tidak pernah menimpa: bila [dir] sudah punya penanda kuat hasilnya
     * [Result.ALREADY]. Bila penulisan gagal di tengah jalan, folder `.molina` yang setengah jadi dibersihkan.
     */
    fun create(dir: File, nowMs: Long): Result {
        if (!dir.isDirectory) return Result.FAILED
        if (markerIn(dir) != null) return Result.ALREADY
        val m = File(dir, DIR)
        return try {
            if (!m.mkdirs()) return Result.FAILED
            try {
                File(m, FILE).writeText(json(dir.name, nowMs))
                Result.CREATED
            } catch (e: Exception) {
                File(m, FILE).delete()
                m.delete()
                Result.FAILED
            }
        } catch (e: Exception) {
            Result.FAILED
        }
    }

    /** Isi `project.json`: versi format, nama folder, tanggal dibuat (UTC). */
    fun json(name: String, nowMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return "{\n  \"format\": $FORMAT,\n  \"name\": \"${esc(name)}\",\n  \"created\": \"${fmt.format(Date(nowMs))}\"\n}\n"
    }

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        return sb.toString()
    }
}

package com.molina.cinema

import android.content.Context
import android.os.Environment
import com.molina.core.common.MolinaDir
import com.molina.core.common.MolinaPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/** Satu penanda: posisi (detik) dan nama opsional (kosong = hanya waktu). */
data class Mark(val sec: Double, val name: String = "")

/**
 * Penanda posisi video per media, disimpan sebagai satu objek JSON di SharedPreferences
 * "molina_bookmarks". Kunci media = berkas lokal atau tautan asal (bukan URL stream hasil resolve).
 * Format elemen: angka (tanpa nama) atau {"t":detik,"n":"nama"}.
 * Batas: 200 media (yang paling lama tak disentuh dibuang), 50 penanda per media, nama maks. 40 karakter.
 *
 * Cadangan: setiap perubahan ditulis ke /sdcard/Molina/Backup/molina-bookmarks.json (thread latar). Bila prefs
 * kosong (pasang baru / data aplikasi dihapus) data dipulihkan dari berkas itu.
 */
object VideoBookmarks {
    private const val PREFS = "molina_bookmarks"
    private const val KEY = "all"
    private const val MAX_VIDEOS = 200
    private const val MAX_PER_VIDEO = 50
    private const val MAX_NAME = 40
    private const val MIN_GAP_SEC = 1.0
    private const val BACKUP_NAME = "molina-bookmarks.json"

    private val io = Executors.newSingleThreadExecutor()

    private fun backupFile(): File = File(
        Environment.getExternalStorageDirectory(),
        MolinaPaths.APP_FOLDER + "/" + MolinaDir.Backup.folderName + "/" + BACKUP_NAME
    )

    private fun parse(o: JSONObject): LinkedHashMap<String, MutableList<Mark>> {
        val out = LinkedHashMap<String, MutableList<Mark>>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val arr = o.getJSONArray(k)
            val list = mutableListOf<Mark>()
            for (i in 0 until arr.length()) {
                when (val e = arr.get(i)) {
                    is JSONObject -> list.add(Mark(e.getDouble("t"), e.optString("n", "").take(MAX_NAME)))
                    is Number -> list.add(Mark(e.toDouble()))
                }
            }
            out[k] = list
        }
        return out
    }

    private fun toJson(all: LinkedHashMap<String, MutableList<Mark>>): JSONObject {
        val o = JSONObject()
        for ((k, v) in all) {
            if (v.isEmpty()) continue
            val arr = JSONArray()
            for (m in v) {
                if (m.name.isBlank()) arr.put(m.sec)
                else arr.put(JSONObject().put("t", m.sec).put("n", m.name))
            }
            o.put(k, arr)
        }
        return o
    }

    private fun wrap(o: JSONObject): String =
        JSONObject().put("app", "molina-player").put("version", 1).put("bookmarks", o).toString(2) + "\n"

    private fun readBackup(): LinkedHashMap<String, MutableList<Mark>>? = try {
        val f = backupFile()
        if (!f.isFile) null else parse(JSONObject(f.readText()).getJSONObject("bookmarks"))
    } catch (e: Exception) {
        null
    }

    private fun readAll(ctx: Context): LinkedHashMap<String, MutableList<Mark>> {
        val raw = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return readBackup() ?: LinkedHashMap()
        return try {
            parse(JSONObject(raw))
        } catch (e: Exception) {
            LinkedHashMap()
        }
    }

    private fun writeAll(ctx: Context, all: LinkedHashMap<String, MutableList<Mark>>) {
        while (all.size > MAX_VIDEOS) all.remove(all.keys.first())
        val o = toJson(all)
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, o.toString()).apply()
        autoBackup(o)
    }

    private fun autoBackup(o: JSONObject) {
        val text = wrap(o)
        val f = backupFile()
        io.execute {
            try {
                f.parentFile?.mkdirs()
                f.writeText(text)
            } catch (e: Exception) {
                // izin penyimpanan belum ada: abaikan, Ekspor manual tetap tersedia
            }
        }
    }

    fun list(ctx: Context, key: String): List<Mark> {
        val all = readAll(ctx)
        // Data lama (sebelum ada cadangan): buat berkas cadangan pertama kali.
        if (all.isNotEmpty() && !backupFile().isFile) autoBackup(toJson(all))
        return all[key]?.sortedBy { it.sec } ?: emptyList()
    }

    /** True bila ditambahkan; false bila sudah ada penanda dalam 1 detik dari posisi ini. */
    fun add(ctx: Context, key: String, sec: Double): Boolean {
        val all = readAll(ctx)
        val marks = all.remove(key) ?: mutableListOf()
        val s = Math.round(sec * 10.0) / 10.0
        if (marks.any { abs(it.sec - s) < MIN_GAP_SEC }) {
            all[key] = marks
            writeAll(ctx, all)
            return false
        }
        marks.add(Mark(s))
        marks.sortBy { it.sec }
        while (marks.size > MAX_PER_VIDEO) marks.removeAt(0)
        all[key] = marks
        writeAll(ctx, all)
        return true
    }

    /** Ubah nama penanda ke-[index] (urutan waktu). Nama kosong = hapus nama. */
    fun rename(ctx: Context, key: String, index: Int, name: String) {
        val all = readAll(ctx)
        val marks = all[key]?.sortedBy { it.sec }?.toMutableList() ?: return
        if (index in marks.indices) {
            marks[index] = marks[index].copy(name = name.trim().take(MAX_NAME))
            all[key] = marks
            writeAll(ctx, all)
        }
    }

    fun removeAt(ctx: Context, key: String, index: Int) {
        val all = readAll(ctx)
        val marks = all[key]?.sortedBy { it.sec }?.toMutableList() ?: return
        if (index in marks.indices) {
            marks.removeAt(index)
            all[key] = marks
            writeAll(ctx, all)
        }
    }

    fun clear(ctx: Context, key: String) {
        val all = readAll(ctx)
        if (all.remove(key) != null) writeAll(ctx, all)
    }

    /** Tulis cadangan sekarang (tombol Ekspor). Mengembalikan pesan untuk toast. */
    fun exportNow(ctx: Context): String {
        val all = readAll(ctx)
        return try {
            val f = backupFile()
            f.parentFile?.mkdirs()
            f.writeText(wrap(toJson(all)))
            "Diekspor ${all.values.sumOf { it.size }} penanda dari ${all.size} video ke ${MolinaDir.Backup.folderName}/$BACKUP_NAME"
        } catch (e: Exception) {
            "Gagal mengekspor penanda"
        }
    }

    /** Seluruh penanda sebagai objek JSON (untuk cadangan gabungan). */
    fun snapshot(ctx: Context): JSONObject = toJson(readAll(ctx))

    /** Gabungkan [bookmarks] (kunci -> larik); posisi yang sudah ada (dalam 1 detik) dilewati. Mengembalikan jumlah baru. */
    fun mergeFrom(ctx: Context, bookmarks: JSONObject): Int {
        val incoming = parse(bookmarks)
        val all = readAll(ctx)
        var added = 0
        for ((key, list) in incoming) {
            val cur = all.remove(key) ?: mutableListOf()
            for (m in list) {
                if (cur.size >= MAX_PER_VIDEO) break
                if (cur.none { abs(it.sec - m.sec) < MIN_GAP_SEC }) {
                    cur.add(m)
                    added++
                }
            }
            cur.sortBy { it.sec }
            all[key] = cur
        }
        if (added > 0) writeAll(ctx, all)
        return added
    }

    /** Gabungkan penanda dari berkas cadangan (tombol Impor). */
    fun importFromFile(ctx: Context): String {
        val f = backupFile()
        if (!f.isFile) return "Berkas tidak ditemukan: ${MolinaDir.Backup.folderName}/$BACKUP_NAME"
        return try {
            val added = mergeFrom(ctx, JSONObject(f.readText()).getJSONObject("bookmarks"))
            "Diimpor $added penanda baru"
        } catch (e: Exception) {
            "Berkas cadangan tidak valid"
        }
    }
}

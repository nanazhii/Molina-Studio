package com.molina.studio.code

import android.content.Context
import com.molina.code.preview.ScriptLaunch
import com.molina.code.run.ScriptShare
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Satu skrip di Settings > Script execution. [sourceKey] = asal dari editor Code (null bila dibuat manual). */
data class ScriptItem(
    val id: String,
    val title: String,
    val note: String,
    val body: String,
    val sourceKey: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long,
    val favorite: Boolean = false,
    val args: String = "",
    val trusted: Boolean = false
)

/** Hasil operasi toko skrip; [message] siap ditampilkan sebagai Toast. */
class ScriptResult(val ok: Boolean, val message: String, val id: String? = null)

/**
 * Penyimpanan skrip Script Execution: satu berkas JSON di filesDir, ditulis atomik (tmp lalu rename)
 * di thread latar. State dipublikasikan lewat [items] supaya halaman Settings selalu sinkron dengan
 * kiriman dari editor Code. Semua mutasi diserialkan dengan @Synchronized.
 */
object ScriptStore {
    const val MAX_SCRIPTS = 50
    const val MAX_ARGS = 300
    private const val MAX_BODY_CHARS = 300_000
    private const val FILE = "script-execution.json"
    private val SAFE_ID = Regex("[A-Za-z0-9]{1,16}")

    private var file: File? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "molina-script-store").apply { isDaemon = true } }
    private val _items = MutableStateFlow<List<ScriptItem>>(emptyList())
    val items: StateFlow<List<ScriptItem>> = _items.asStateFlow()

    @Synchronized
    fun init(ctx: Context) {
        val f = File(ctx.applicationContext.filesDir, FILE)
        file = f
        _items.value = load(f)
    }

    /** Kiriman dari editor Code. Sumber yang sama memperbarui entri lama (catatan dipertahankan). */
    @Synchronized
    fun upsertFromEditor(share: ScriptShare): ScriptResult {
        val body = ScriptLaunch.normalizeEol(share.text)
        checkBody(body)?.let { return ScriptResult(false, it) }
        val title = share.title.trim().take(ScriptLaunch.MAX_TITLE).ifEmpty { "Skrip" }
        val now = System.currentTimeMillis()
        val list = _items.value
        val old = list.firstOrNull { it.sourceKey == share.sourceKey || "script:${it.id}" == share.sourceKey }
        if (old != null) {
            if (old.body == body && old.title == title) {
                return ScriptResult(true, "\"$title\" sudah sinkron di Script Execution", old.id)
            }
            publish(list.map { if (it.id == old.id) it.copy(title = title, body = body, updatedAt = now) else it })
            return ScriptResult(true, "\"$title\" diperbarui di Script Execution", old.id)
        }
        if (list.size >= MAX_SCRIPTS) {
            return ScriptResult(false, "Script Execution penuh ($MAX_SCRIPTS skrip). Hapus satu di Settings dulu")
        }
        val item = ScriptItem(newId(list), title, "", body, share.sourceKey, now, now, 0L)
        publish(list + item)
        return ScriptResult(true, "\"$title\" ditambahkan ke Script Execution", item.id)
    }

    /** Simpan dari formulir Settings. [id] null = skrip baru. */
    @Synchronized
    fun save(id: String?, title: String, note: String, rawBody: String): ScriptResult {
        val t = title.trim()
        if (t.isEmpty()) return ScriptResult(false, "Judul skrip wajib diisi")
        if (t.length > ScriptLaunch.MAX_TITLE) return ScriptResult(false, "Judul maksimal ${ScriptLaunch.MAX_TITLE} karakter")
        val n = note.trim()
        if (n.length > ScriptLaunch.MAX_NOTE) return ScriptResult(false, "Catatan maksimal ${ScriptLaunch.MAX_NOTE} karakter")
        val body = ScriptLaunch.normalizeEol(rawBody)
        checkBody(body)?.let { return ScriptResult(false, it) }
        val now = System.currentTimeMillis()
        val list = _items.value
        if (id == null) {
            if (list.size >= MAX_SCRIPTS) return ScriptResult(false, "Script Execution penuh ($MAX_SCRIPTS skrip)")
            val item = ScriptItem(newId(list), t, n, body, null, now, now, 0L)
            publish(list + item)
            return ScriptResult(true, "\"$t\" ditambahkan", item.id)
        }
        if (list.none { it.id == id }) return ScriptResult(false, "Skrip tidak ditemukan (mungkin sudah dihapus)")
        publish(list.map { if (it.id == id) it.copy(title = t, note = n, body = body, updatedAt = now) else it })
        return ScriptResult(true, "\"$t\" disimpan", id)
    }

    /** Salinan seluruh skrip sebagai JSON untuk cadangan gabungan. */
    @Synchronized
    fun snapshot(): JSONArray = JSONObject(encode(_items.value)).getJSONArray("items")

    /**
     * Gabungkan skrip dari cadangan: judul + isi yang sudah ada dilewati, id tidak aman atau bentrok diganti id baru
     * (id dipakai sebagai bagian nama berkas), batas [MAX_SCRIPTS] dihormati. Mengembalikan jumlah yang ditambahkan.
     */
    @Synchronized
    fun mergeFrom(arr: JSONArray): Int {
        var list = _items.value
        var added = 0
        for (i in 0 until arr.length()) {
            if (list.size >= MAX_SCRIPTS) break
            val o = arr.optJSONObject(i) ?: continue
            val body = ScriptLaunch.normalizeEol(o.optString("body"))
            if (checkBody(body) != null) continue
            val title = o.optString("title", "Skrip").trim().take(ScriptLaunch.MAX_TITLE).ifEmpty { "Skrip" }
            if (list.any { it.title == title && it.body == body }) continue
            val rawId = o.optString("id")
            val id = if (SAFE_ID.matches(rawId) && list.none { it.id == rawId }) rawId else newId(list)
            val source = if (o.isNull("source")) null else o.optString("source").ifEmpty { null }
            list = list + ScriptItem(
                id, title, o.optString("note", "").take(ScriptLaunch.MAX_NOTE), body, source,
                o.optLong("created"), o.optLong("updated"), o.optLong("lastRun"),
                o.optBoolean("fav"), o.optString("args").filter { it >= ' ' }.take(MAX_ARGS), o.optBoolean("trust")
            )
            added++
        }
        if (added > 0) publish(list)
        return added
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val list = _items.value
        if (list.none { it.id == id }) return false
        publish(list.filterNot { it.id == id })
        return true
    }

    @Synchronized
    fun setFavorite(id: String, on: Boolean) {
        publish(_items.value.map { if (it.id == id) it.copy(favorite = on) else it })
    }

    /** Argumen (satu baris, gaya prompt shell) dan saklar "percaya" (lewati konfirmasi untuk ketukan dalam aplikasi). */
    @Synchronized
    fun setOptions(id: String, args: String, trusted: Boolean) {
        val a = args.filter { it >= ' ' }.trim().take(MAX_ARGS)
        publish(_items.value.map { if (it.id == id) it.copy(args = a, trusted = trusted) else it })
    }

    @Synchronized
    fun markRun(id: String) {
        val now = System.currentTimeMillis()
        publish(_items.value.map { if (it.id == id) it.copy(lastRunAt = now) else it })
    }

    private fun checkBody(body: String): String? {
        if (body.isBlank()) return "Isi skrip kosong"
        val lines = ScriptLaunch.countLines(body)
        if (lines > ScriptLaunch.MAX_LINES) return "Skrip $lines baris, melebihi batas ${ScriptLaunch.MAX_LINES} baris"
        if (body.length > MAX_BODY_CHARS) return "Skrip terlalu besar (maks ${MAX_BODY_CHARS / 1000} rb karakter)"
        return null
    }

    private fun newId(existing: List<ScriptItem>): String {
        while (true) {
            val id = UUID.randomUUID().toString().replace("-", "").take(8)
            if (existing.none { it.id == id }) return id
        }
    }

    private fun publish(list: List<ScriptItem>) {
        _items.value = list
        val f = file ?: return
        val json = encode(list)
        try {
            io.execute { write(f, json) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // proses sedang berhenti
        }
    }

    private fun encode(list: List<ScriptItem>): String {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("id", s.id).put("title", s.title).put("note", s.note).put("body", s.body)
                    .put("source", s.sourceKey ?: JSONObject.NULL)
                    .put("created", s.createdAt).put("updated", s.updatedAt).put("lastRun", s.lastRunAt)
                    .put("fav", s.favorite).put("args", s.args).put("trust", s.trusted)
            )
        }
        return JSONObject().put("v", 1).put("items", arr).toString()
    }

    private fun write(f: File, json: String) {
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            // gagal menulis: state di memori tetap benar, percobaan berikutnya menimpa
        }
    }

    private fun load(f: File): List<ScriptItem> {
        if (!f.isFile) return emptyList()
        return try {
            val arr = JSONObject(f.readText(Charsets.UTF_8)).getJSONArray("items")
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id")
                    if (id.isEmpty() || any { it.id == id }) continue
                    add(
                        ScriptItem(
                            id = id,
                            title = o.optString("title", "Skrip"),
                            note = o.optString("note", ""),
                            body = o.optString("body", ""),
                            sourceKey = if (o.isNull("source")) null else o.optString("source"),
                            createdAt = o.optLong("created"),
                            updatedAt = o.optLong("updated"),
                            lastRunAt = o.optLong("lastRun"),
                            favorite = o.optBoolean("fav"),
                            args = o.optString("args").filter { it >= ' ' }.take(MAX_ARGS),
                            trusted = o.optBoolean("trust")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // berkas rusak: simpan sebagai .bad agar tidak hilang diam-diam, mulai dari kosong
            f.renameTo(File(f.parentFile, f.name + ".bad"))
            emptyList()
        }
    }
}

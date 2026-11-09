package com.molina.studio.script

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.molina.exec.plan.ScriptLaunch
import com.molina.exec.run.KnownScript
import com.molina.exec.run.ScanResult
import com.molina.exec.run.ScriptFolder
import com.molina.exec.run.ScriptShare
import com.molina.exec.run.Stamp
import com.molina.core.common.MolinaDir
import com.molina.core.common.TrashBin
import com.molina.studio.AppPaths
import com.molina.studio.ScriptShortcuts
import com.molina.studio.settings.trashVolumes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    val trusted: Boolean = false,
    /** Path berkas di folder Molina/Scripts (sumber nama + isi); null = entri lama yang belum dipindah ke folder. */
    val path: String? = null
)

/** Hasil operasi toko skrip; [message] siap ditampilkan sebagai Toast. */
class ScriptResult(val ok: Boolean, val message: String, val id: String? = null)

/**
 * Penyimpanan skrip Script Execution: satu berkas JSON di filesDir, ditulis atomik (tmp lalu rename)
 * di thread latar. State dipublikasikan lewat [items] supaya halaman Settings selalu sinkron dengan
 * kiriman dari editor Code. Semua mutasi diserialkan dengan @Synchronized.
 */
object ScriptStore {
    const val MAX_SCRIPTS = 100
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
        appCtx = ctx.applicationContext
        val f = File(ctx.applicationContext.filesDir, FILE)
        file = f
        _items.value = load(f)
    }

    // [SCRIPT-FOLDER] Daftar mengikuti folder Molina/Scripts: nama + isi entri = nama + isi berkas.

    /** Keadaan folder Molina/Scripts untuk ditampilkan di Settings. */
    enum class FolderStatus { Unknown, Ready, NoPermission, Unavailable }

    @Volatile private var appCtx: Context? = null
    private var stamps: Map<String, Stamp> = emptyMap()
    private val syncing = AtomicBoolean(false)
    private val _status = MutableStateFlow(FolderStatus.Unknown)
    val status: StateFlow<FolderStatus> = _status.asStateFlow()
    private val _skipped = MutableStateFlow(0)

    /** Jumlah berkas di folder yang dilewati (terlalu besar, biner, atau melebihi [MAX_SCRIPTS]). */
    val skipped: StateFlow<Int> = _skipped.asStateFlow()

    private fun hasStorage(c: Context) =
        c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Folder Molina/Scripts, atau null bila belum bisa dipakai (belum init / izin storage belum ada). */
    private fun usableDir(): File? {
        val c = appCtx ?: return null
        if (!hasStorage(c)) return null
        val dir = AppPaths.get(c).dir(MolinaDir.Scripts)
        return if (dir.isDirectory || dir.mkdirs()) dir else null
    }

    private fun prefixOf(dir: File) = dir.path.trimEnd('/') + "/"

    private fun isScriptInFolder(path: String, dir: File): Boolean {
        if (!path.startsWith(prefixOf(dir))) return false
        val f = File(path)
        if (!ScriptFolder.isScriptName(f.name)) return false
        return runCatching { f.parentFile?.canonicalPath == dir.canonicalPath }.getOrDefault(false)
    }

    /**
     * Samakan daftar dengan isi folder (nama dan isi mengikuti berkas). Operasi disk: panggil di luar main thread.
     * Entri lama tanpa berkas ditulis jadi berkas baru (tidak menimpa apa pun). Folder yang tidak terbaca
     * tidak pernah dianggap kosong. Mengembalikan true bila daftar berubah.
     */
    fun syncFromFolder(): Boolean {
        val c = appCtx ?: return false
        if (!syncing.compareAndSet(false, true)) return false
        try {
            if (!hasStorage(c)) { _status.value = FolderStatus.NoPermission; return false }
            val dir = usableDir() ?: run { _status.value = FolderStatus.Unavailable; return false }
            val scan = ScriptFolder.scan(dir, knownStamps()) ?: run { _status.value = FolderStatus.Unavailable; return false }
            val removed = ArrayList<String>()
            val changed = applyScan(scan, dir, removed)
            _status.value = FolderStatus.Ready
            for (id in removed) {
                runCatching { ScriptRunner.cleanup(c, id) }
                runCatching { ScriptShortcuts.onDeleted(c, id) }
            }
            return changed
        } catch (e: Exception) {
            _status.value = FolderStatus.Unavailable
            return false
        } finally {
            syncing.set(false)
        }
    }

    @Synchronized
    private fun knownStamps(): Map<String, Stamp> {
        val paths = _items.value.mapNotNull { it.path }.toSet()
        return stamps.filterKeys { it in paths }
    }

    @Synchronized
    private fun applyScan(scan: ScanResult, dir: File, removedOut: MutableList<String>): Boolean {
        val list = _items.value
        val plan = ScriptFolder.reconcile(
            list.map { KnownScript(it.id, it.path, it.sourceKey, it.title, it.body) }, scan.files, prefixOf(dir)
        )
        val byPath = scan.files.associateBy { it.path }
        val now = System.currentTimeMillis()
        var changed = false
        val out = ArrayList<ScriptItem>(list.size)
        for (item in list) {
            val linkedPath = plan.linked[item.id]
            val keptFile = item.path?.takeIf { plan.kept[it] == item.id }?.let { byPath[it] }
            when {
                item.id in plan.removed -> {
                    removedOut.add(item.id)
                    changed = true
                }
                keptFile != null -> {
                    val body = keptFile.body ?: item.body
                    if (body != item.body || keptFile.name != item.title) {
                        out.add(item.copy(title = keptFile.name, body = body, updatedAt = keptFile.modified))
                        changed = true
                    } else {
                        out.add(item)
                    }
                }
                linkedPath != null -> {
                    val f = byPath.getValue(linkedPath)
                    out.add(item.copy(title = f.name, body = f.body ?: item.body, path = f.path, sourceKey = item.sourceKey ?: f.path, updatedAt = f.modified))
                    changed = true
                }
                item.id in plan.exported -> {
                    val moved = exportToFolder(item, dir, now)
                    if (moved != null) { out.add(moved); changed = true } else out.add(item)
                }
                else -> out.add(item)
            }
        }
        var skippedNow = scan.skipped
        for (f in plan.added) {
            val body = f.body ?: continue
            if (out.size >= MAX_SCRIPTS) { skippedNow++; continue }
            out.add(ScriptItem(newId(out), f.name, "", body, f.path, f.modified, f.modified, 0L, path = f.path))
            changed = true
        }
        stamps = scan.stamps
        _skipped.value = skippedNow
        if (changed) publish(out)
        return changed
    }

    /** Entri lama (belum berupa berkas) ditulis jadi berkas baru di folder; tidak pernah menimpa berkas yang ada. */
    private fun exportToFolder(item: ScriptItem, dir: File, now: Long): ScriptItem? {
        val text = ScriptLaunch.normalizeEol(item.body)
        val ext = ScriptLaunch.planFor(item.title, text).extension
        val name = ScriptFolder.uniqueName(ScriptFolder.safeFileName(item.title, ext)) { File(dir, it).exists() }
        val target = File(dir, name)
        if (!writeNew(target, text)) return null
        val modified = target.lastModified().takeIf { it > 0 } ?: now
        return item.copy(title = name, body = text, path = target.path, sourceKey = item.sourceKey ?: target.path, updatedAt = modified)
    }

    private fun writeNew(target: File, text: String): Boolean = try {
        if (target.exists()) {
            false
        } else {
            val tmp = File(target.parentFile, "." + target.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (tmp.renameTo(target)) true else { tmp.delete(); false }
        }
    } catch (e: Exception) {
        false
    }

    private fun writeFile(target: File, text: String): Boolean = try {
        val tmp = File(target.parentFile, "." + target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))) true else { tmp.delete(); false }
    } catch (e: Exception) {
        false
    }

    /** + TAMBAH: buat berkas skrip baru (dengan shebang) di folder. Pair(berkas, "") bila berhasil, Pair(null, pesan galat) bila gagal. */
    fun createFile(rawName: String): Pair<File?, String> {
        val dir = usableDir() ?: return Pair(null, "Folder Molina/Scripts belum bisa diakses (cek izin penyimpanan)")
        val name = ScriptFolder.safeFileName(rawName, "sh")
        val target = File(dir, name)
        if (target.exists()) return Pair(null, "Sudah ada berkas bernama \"$name\" di folder Scripts")
        val template = if (name.endsWith(".py", ignoreCase = true)) "#!/usr/bin/env python3\n\n" else "#!/bin/bash\n\n"
        if (!writeNew(target, template)) return Pair(null, "Gagal membuat \"$name\"")
        return Pair(target, "")
    }

    /**
     * HAPUS dari Settings: berkas di folder dipindah ke Recycle Bin (bisa dipulihkan), lalu entrinya dibuang.
     * Mengembalikan pesan galat, atau null bila berhasil.
     */
    fun deleteWithFile(id: String): String? {
        val item = _items.value.firstOrNull { it.id == id } ?: return "Skrip tidak ditemukan (mungkin sudah dihapus)"
        val c = appCtx
        val p = item.path
        if (p != null && c != null) {
            val f = File(p)
            if (f.exists()) {
                try {
                    TrashBin.trash(listOf(f), trashVolumes(c), emptySet())
                } catch (e: Exception) {
                    return "Gagal memindahkan ke Recycle Bin: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
        delete(id)
        return null
    }

    /** Muat ulang isi skrip dari berkasnya tepat sebelum dijalankan. null = berkas hilang. Entri lama tanpa berkas dikembalikan apa adanya. */
    fun fresh(item: ScriptItem): ScriptItem? {
        val p = item.path ?: return item
        val f = File(p)
        if (!f.isFile) return null
        val text = runCatching { ScriptLaunch.normalizeEol(f.readText(Charsets.UTF_8)) }.getOrNull() ?: return item
        return if (text == item.body && f.name == item.title) item else item.copy(title = f.name, body = text)
    }

    /**
     * Kiriman dari editor Code. Isi ditulis ke berkas di folder Scripts (sumber yang sama memperbarui berkas
     * yang sama, bukan menggandakan). Tanpa izin storage dipakai perilaku lama ([upsertLegacy]).
     */
    @Synchronized
    fun upsertFromEditor(share: ScriptShare): ScriptResult {
        val dir = usableDir() ?: return upsertLegacy(share)
        val body = ScriptLaunch.normalizeEol(share.text)
        checkBody(body)?.let { return ScriptResult(false, it) }
        val title = share.title.trim().take(ScriptLaunch.MAX_TITLE).ifEmpty { "Skrip" }
        val key = share.sourceKey
        val list = _items.value
        val old = list.firstOrNull { it.sourceKey == key || "script:${it.id}" == key || (it.path != null && it.path == key) }
        val oldPath = old?.path
        val target = when {
            oldPath != null -> File(oldPath)
            isScriptInFolder(key, dir) -> File(key)
            else -> File(dir, ScriptFolder.uniqueName(ScriptFolder.safeFileName(title, ScriptLaunch.planFor(title, body).extension)) { File(dir, it).exists() })
        }
        val existing = old ?: list.firstOrNull { it.path == target.path }
        if (existing == null && list.size >= MAX_SCRIPTS) {
            return ScriptResult(false, "Script Execution penuh ($MAX_SCRIPTS skrip). Hapus satu di Settings dulu")
        }
        val current = if (target.isFile) runCatching { ScriptLaunch.normalizeEol(target.readText(Charsets.UTF_8)) }.getOrNull() else null
        val same = current == body
        if (!same && !writeFile(target, body)) return ScriptResult(false, "Gagal menulis \"${target.name}\" ke folder Scripts")
        val name = target.name
        val modified = target.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        if (existing != null) {
            val unchanged = same && existing.body == body && existing.title == name && existing.path == target.path
            publish(list.map { if (it.id == existing.id) it.copy(title = name, body = body, path = target.path, updatedAt = modified) else it })
            return ScriptResult(true, if (unchanged) "\"$name\" sudah sinkron di Script Execution" else "\"$name\" diperbarui di Script Execution", existing.id)
        }
        val item = ScriptItem(newId(list), name, "", body, key, modified, modified, 0L, path = target.path)
        publish(list + item)
        return ScriptResult(true, "\"$name\" disimpan di folder Scripts dan ditambahkan ke Script Execution", item.id)
    }

    /** Perilaku lama (tanpa folder): dipakai bila izin storage belum ada. */
    @Synchronized
    private fun upsertLegacy(share: ScriptShare): ScriptResult {
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
                    .put("path", s.path ?: JSONObject.NULL)
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
                            trusted = o.optBoolean("trust"),
                            path = if (o.isNull("path")) null else o.optString("path").ifEmpty { null }
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

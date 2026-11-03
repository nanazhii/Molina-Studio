package com.molina.vault

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.molina.vault.crypto.SessionLock
import com.molina.vault.store.RecordMeta
import com.molina.vault.store.RecordType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.MessageDigest

private const val REQ_IMPORT = 4101
private const val REQ_EXPORT = 4102
private const val MAX_FILE = 16 * 1024 * 1024
private const val MASK = "••••••••"
private const val PICKER_TIMEOUT_MS = 60_000L
private const val REVEAL_MS = 10_000L

private class WipeableBuffer : ByteArrayOutputStream() {
    fun wipe() { buf.fill(0) }
}

private enum class Kind { TEXT, SECRET, MULTI, FILE }
private class Spec(val label: String, val kind: Kind)

private val SPECS: Map<RecordType, List<Spec>> = mapOf(
    RecordType.PASSWORD to listOf(Spec("Username", Kind.TEXT), Spec("Password", Kind.SECRET), Spec("URL (opsional)", Kind.TEXT), Spec("Catatan", Kind.MULTI)),
    RecordType.NOTE to listOf(Spec("Isi catatan", Kind.MULTI)),
    RecordType.SECRET to listOf(Spec("Nilai rahasia (token, kunci)", Kind.MULTI), Spec("Catatan", Kind.MULTI)),
    RecordType.KEYSTORE to listOf(Spec("Nama file", Kind.TEXT), Spec("File keystore", Kind.FILE), Spec("Store password", Kind.SECRET), Spec("Alias", Kind.TEXT), Spec("Key password", Kind.SECRET)),
    RecordType.FILE to listOf(Spec("Nama file", Kind.TEXT), Spec("File", Kind.FILE), Spec("Catatan", Kind.MULTI))
)

/** Layar daftar, form, detail. Semua rahasia dibersihkan saat vault terkunci atau layar berganti. */
class EntryUi(private val host: MainActivity) {
    /** true selama menunggu picker/exporter: MainActivity tidak boleh me-render ulang layar. */
    var holdScreen = false
        private set

    private var pickedBytes: ByteArray? = null
    private var nameField: EditText? = null
    private var fileInfo: TextView? = null
    private var detailFields: List<ByteArray> = emptyList()
    private var detailMeta: RecordMeta? = null
    private var exportIndex = -1
    private val pickerLock = Runnable { SessionLock.lock(); clearSensitive() }

    fun show() { clearSensitive(); showList() }

    fun clearSensitive() {
        pickedBytes?.fill(0); pickedBytes = null
        detailFields.forEach { it.fill(0) }; detailFields = emptyList()
        detailMeta = null; nameField = null; fileInfo = null; exportIndex = -1
    }

    private fun typeName(t: RecordType) = when (t) {
        RecordType.PASSWORD -> "Password / akun"
        RecordType.NOTE -> "Catatan rahasia"
        RecordType.SECRET -> "Token / kunci API"
        RecordType.KEYSTORE -> "Keystore (JKS / PKCS12)"
        RecordType.FILE -> "File lain"
    }

    // ---------- daftar ----------
    private fun showList(): Unit = host.run {
        screen {
            label("MOLINA VAULT", 26f, amber)
            val listing = try { store.list() } catch (e: Exception) { null }
            if (listing == null) {
                label("Gagal membaca daftar entri.", 14f, red)
            } else {
                val extra = if (listing.corrupt > 0) " (${listing.corrupt} rusak/dimanipulasi)" else ""
                label("${listing.items.size} entri$extra", 16f, Color.WHITE)
                for (m in listing.items) {
                    addView(TextView(context).apply {
                        text = "${m.type.name} · ${m.title}"
                        textSize = 16f
                        setTextColor(Color.WHITE)
                        setPadding(0, 28, 0, 28)
                        isClickable = true
                        setOnClickListener { openDetail(m) }
                    })
                }
            }
            button("Tambah entri") { showTypePick() }
            button("Kunci sekarang") { SessionLock.lock(); render() }
        }
    }

    private fun showTypePick(): Unit = host.run {
        screen {
            label("Jenis entri", 22f, amber)
            for (t in RecordType.values()) button(typeName(t)) { showForm(t) }
            button("Batal") { show() }
        }
    }

    // ---------- form ----------
    private fun makeInput(s: Spec): EditText = EditText(host).apply {
        hint = s.label
        setHintTextColor(Color.GRAY)
        setTextColor(Color.WHITE)
        var flags = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        when (s.kind) {
            Kind.SECRET -> flags = flags or InputType.TYPE_TEXT_VARIATION_PASSWORD
            Kind.MULTI -> { flags = flags or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3; gravity = Gravity.TOP }
            else -> {}
        }
        inputType = flags
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    private fun utf8(chars: CharArray): ByteArray {
        val bb = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val out = ByteArray(bb.remaining())
        bb.get(out)
        if (bb.hasArray()) bb.array().fill(0)
        chars.fill(Char.MIN_VALUE)
        return out
    }

    private fun showForm(type: RecordType): Unit = host.run {
        clearSensitive()
        val specs = SPECS.getValue(type)
        screen {
            label(typeName(type), 22f, amber)
            val titleField = EditText(context).apply {
                hint = "Judul"
                setHintTextColor(Color.GRAY)
                setTextColor(Color.WHITE)
                setSingleLine(true)
            }
            addView(titleField)
            val inputs = ArrayList<EditText?>()
            for (s in specs) {
                if (s.kind == Kind.FILE) {
                    val info = TextView(context).apply { text = "Belum ada file"; setTextColor(Color.LTGRAY); setPadding(0, 12, 0, 12) }
                    fileInfo = info
                    button("Pilih file…") { pickFile() }
                    addView(info)
                    inputs.add(null)
                } else {
                    val e = makeInput(s)
                    addView(e)
                    inputs.add(e)
                    if (s.label == "Nama file") nameField = e
                }
            }
            val msg = messageView()
            button("Simpan terenkripsi") { b ->
                val t = titleField.text.toString().trim()
                if (t.isEmpty()) { msg.text = "Judul wajib diisi."; return@button }
                if (specs.any { it.kind == Kind.FILE } && pickedBytes == null) { msg.text = "Pilih file terlebih dulu."; return@button }
                val fields = ArrayList<ByteArray>()
                for ((i, s) in specs.withIndex()) {
                    if (s.kind == Kind.FILE) fields.add(pickedBytes!!.copyOf())
                    else fields.add(utf8(take(inputs[i]!!)))
                }
                b.isEnabled = false
                msg.text = "Mengenkripsi…"
                worker.execute {
                    val err: String? = try { store.put(type, t, fields); null } catch (e: Exception) { e.message ?: "Gagal menyimpan" }
                    fields.forEach { it.fill(0) }
                    ui.post {
                        if (err == null) show() else { msg.text = err; b.isEnabled = true }
                    }
                }
            }
            button("Batal") { show() }
        }
    }

    // ---------- picker / exporter ----------
    private fun launchExternal(i: Intent, req: Int) {
        host.suppressLockOnce = true
        holdScreen = true
        host.ui.postDelayed(pickerLock, PICKER_TIMEOUT_MS)
        try {
            host.startActivityForResult(i, req)
        } catch (e: Exception) {
            host.suppressLockOnce = false
            holdScreen = false
            host.ui.removeCallbacks(pickerLock)
            Toast.makeText(host, "Tidak ada aplikasi pemilih file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickFile() {
        launchExternal(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQ_IMPORT)
    }

    fun onResult(req: Int, res: Int, data: Intent?) {
        if (req != REQ_IMPORT && req != REQ_EXPORT) return
        host.ui.removeCallbacks(pickerLock)
        holdScreen = false
        if (!SessionLock.isUnlocked) { clearSensitive(); host.render(); return }
        val uri = data?.data
        if (res != Activity.RESULT_OK || uri == null) return
        if (req == REQ_IMPORT) importFrom(uri) else exportTo(uri)
    }

    private fun importFrom(uri: Uri) {
        fileInfo?.text = "Membaca…"
        host.worker.execute {
            val r = runCatching { readLimited(uri) to displayName(uri) }
            host.ui.post {
                val pair = r.getOrNull()
                if (pair == null) {
                    fileInfo?.text = r.exceptionOrNull()?.message ?: "Gagal membaca file"
                } else {
                    val (bytes, name) = pair
                    if (!SessionLock.isUnlocked) {
                        bytes.fill(0)
                    } else {
                        pickedBytes?.fill(0)
                        pickedBytes = bytes
                        fileInfo?.text = "${bytes.size} byte · SHA-256 ${fingerprint(bytes)}"
                        val nf = nameField
                        if (nf != null && nf.text.isEmpty() && name != null) nf.setText(name)
                    }
                }
            }
        }
    }

    private fun readLimited(uri: Uri): ByteArray {
        val sink = WipeableBuffer()
        val buf = ByteArray(64 * 1024)
        try {
            val ins = host.contentResolver.openInputStream(uri) ?: throw IOException("Tidak bisa membuka file")
            ins.use {
                var total = 0
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_FILE) throw IOException("File lebih dari 16 MB")
                    sink.write(buf, 0, n)
                }
            }
            return sink.toByteArray()
        } finally {
            buf.fill(0)
            sink.wipe()
        }
    }

    private fun displayName(uri: Uri): String? = try {
        host.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    private fun fingerprint(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).take(8).joinToString("") { "%02x".format(it) }

    // ---------- detail ----------
    private fun openDetail(m: RecordMeta) {
        host.worker.execute {
            val r = runCatching { host.store.read(m.id) }
            host.ui.post {
                val f = r.getOrNull()
                if (f == null) {
                    Toast.makeText(host, "Entri rusak atau dimanipulasi", Toast.LENGTH_LONG).show()
                } else if (!SessionLock.isUnlocked) {
                    f.forEach { it.fill(0) }
                } else {
                    clearSensitive()
                    detailFields = f
                    detailMeta = m
                    showDetail()
                }
            }
        }
    }

    private fun valueView() = TextView(host).apply {
        textSize = 16f
        setTextColor(Color.WHITE)
        setPadding(0, 8, 0, 16)
        setTextIsSelectable(false)
        isLongClickable = false
    }

    private fun setChars(tv: TextView, data: ByteArray) {
        val cb = Charsets.UTF_8.decode(ByteBuffer.wrap(data))
        val chars = CharArray(cb.remaining())
        cb.get(chars)
        if (cb.hasArray()) cb.array().fill(Char.MIN_VALUE)
        tv.setText(chars, 0, chars.size)
        chars.fill(Char.MIN_VALUE)
    }

    private fun reveal(tv: TextView, data: ByteArray) {
        setChars(tv, data)
        host.ui.postDelayed({ tv.text = MASK }, REVEAL_MS)
    }

    private fun showDetail(): Unit = host.run {
        val m = detailMeta
        if (m == null) { showList(); return@run }
        screen {
            label(typeName(m.type), 13f)
            label(m.title, 22f, amber)
            for ((i, s) in SPECS.getValue(m.type).withIndex()) {
                if (i >= detailFields.size) break
                val data = detailFields[i]
                label(s.label, 13f)
                when (s.kind) {
                    Kind.FILE -> {
                        label("${data.size} byte · SHA-256 ${fingerprint(data)}", 14f, Color.WHITE)
                        button("Ekspor file…") { confirmExport(i) }
                    }
                    Kind.TEXT -> addView(valueView().also { setChars(it, data) })
                    else -> {
                        val tv = valueView()
                        tv.text = MASK
                        addView(tv)
                        button("Tampilkan 10 detik") { reveal(tv, data) }
                    }
                }
            }
            button("Hapus entri") { confirmDelete(m) }
            button("Kembali") { show() }
        }
    }

    private fun confirmDelete(m: RecordMeta) {
        AlertDialog.Builder(host)
            .setTitle("Hapus entri?")
            .setMessage("\"${m.title}\" dihapus permanen. Tidak ada cadangan.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                host.worker.execute {
                    val r = runCatching { host.store.delete(m.id) }
                    host.ui.post {
                        if (r.isSuccess) show()
                        else Toast.makeText(host, "Gagal menghapus", Toast.LENGTH_LONG).show()
                    }
                }
            }.show()
    }

    private fun confirmExport(index: Int) {
        val raw = detailFields.getOrNull(index - 1)?.let { String(it, Charsets.UTF_8) }
        val safeName = (raw?.takeIf { it.isNotBlank() } ?: "vault-export.bin").replace('/', '_')
        AlertDialog.Builder(host)
            .setTitle("Ekspor ke luar Vault?")
            .setMessage("File ditulis TANPA enkripsi ke lokasi pilihan Anda dan bisa dibaca aplikasi lain. Pilih penyimpanan lokal (bukan cloud) dan hapus setelah dipakai.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Lanjut") { _, _ ->
                exportIndex = index
                launchExternal(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, safeName)
                }, REQ_EXPORT)
            }.show()
    }

    private fun exportTo(uri: Uri) {
        val copy = detailFields.getOrNull(exportIndex)?.copyOf() ?: return
        host.worker.execute {
            val r = runCatching { host.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(copy) } }
            copy.fill(0)
            host.ui.post {
                Toast.makeText(host, if (r.isSuccess) "Diekspor tanpa enkripsi. Hapus setelah dipakai." else "Gagal mengekspor", Toast.LENGTH_LONG).show()
            }
        }
    }
}

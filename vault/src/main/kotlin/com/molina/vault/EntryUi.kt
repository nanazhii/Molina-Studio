package com.molina.vault

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.molina.vault.crypto.BackupPolicy
import com.molina.vault.crypto.InvalidVaultException
import com.molina.vault.crypto.PassphraseGenerator
import com.molina.vault.crypto.PasswordPolicy
import com.molina.vault.crypto.SessionLock
import com.molina.vault.crypto.WrongPasswordOrTamperedException
import com.molina.vault.store.BackupStore
import com.molina.vault.store.RecordMeta
import com.molina.vault.store.RecordType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val REQ_IMPORT = 4101
private const val REQ_EXPORT = 4102
private const val REQ_BACKUP_EXPORT = 4103
private const val REQ_BACKUP_PICK = 4104
private const val REQ_IMPORT_MULTI = 4105
private const val MAX_BATCH = 100
private const val MAX_FILE = 16 * 1024 * 1024
private const val PREVIEW_MAX = 512 * 1024
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
    private var backupPass: CharArray? = null
    private var restoreUri: Uri? = null
    private var editKeep: ByteArray? = null   // MOLINA-H2: isi file lama saat mengubah entri

    private var filterText = ""
    private var filterType: RecordType? = null
    private val dateFmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

    /** true jika berkas cadangan sudah dipilih dan menunggu passphrase serta master baru. */
    val hasPendingRestore: Boolean get() = restoreUri != null
    private val pickerLock = Runnable { SessionLock.lock(); clearSensitive() }

    fun show() { clearSensitive(); showList() }

    fun clearSensitive() {
        pickedBytes?.fill(0); pickedBytes = null
        detailFields.forEach { it.fill(0) }; detailFields = emptyList()
        detailMeta = null; nameField = null; fileInfo = null; exportIndex = -1
        backupPass?.fill(Char.MIN_VALUE); backupPass = null
        editKeep?.fill(0); editKeep = null
    }

    private fun typeName(t: RecordType) = when (t) {
        RecordType.PASSWORD -> "Password / akun"
        RecordType.NOTE -> "Catatan rahasia"
        RecordType.SECRET -> "Token / kunci API"
        RecordType.KEYSTORE -> "Keystore (JKS / PKCS12)"
        RecordType.FILE -> "File lain"
    }

    // ---------- daftar ---------- (MOLINA-H3-LIST)
    private fun typeShort(t: RecordType) = when (t) {
        RecordType.PASSWORD -> "AKUN"
        RecordType.NOTE -> "CATATAN"
        RecordType.SECRET -> "TOKEN"
        RecordType.KEYSTORE -> "KEYSTORE"
        RecordType.FILE -> "FILE"
    }

    private fun typeHint(t: RecordType) = when (t) {
        RecordType.PASSWORD -> "Username, password, URL"
        RecordType.NOTE -> "Teks rahasia, diketik langsung"
        RecordType.SECRET -> "Token atau kunci API"
        RecordType.KEYSTORE -> "Berkas keystore beserta sandinya"
        RecordType.FILE -> "Satu dokumen apa saja, misalnya .md"
    }

    private fun showList(): Unit = host.run {
        screen {
            label("MOLINA VAULT", 26f, amber)
            button("Tambah entri") { showTypePick() }
            button("Impor banyak berkas") { pickMulti() }
            val listing = try { store.list() } catch (e: Exception) { null }
            if (listing == null) {
                label("Gagal membaca daftar entri.", 14f, red)
            } else {
                val extra = if (listing.corrupt > 0) " (${listing.corrupt} rusak/dimanipulasi)" else ""
                val countView = TextView(context).apply { setTextColor(Color.WHITE); textSize = 16f; setPadding(0, 20, 0, 8) }
                addView(countView)
                val search = makeInput(Spec("Cari judul", Kind.TEXT)).apply { setSingleLine(true); setText(filterText) }
                addView(search)
                val chip = TextView(context).apply {
                    textSize = 14f
                    setTextColor(amber)
                    background = host.cardDrawable()
                    setPadding(host.dp(14f), host.dp(10f), host.dp(14f), host.dp(10f))
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { setMargins(0, host.dp(4f), 0, host.dp(12f)) }
                }
                addView(chip)
                val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                addView(box)

                fun fill() {
                    box.removeAllViews()
                    val q = filterText.trim().lowercase()
                    val shown = listing.items.filter { m ->
                        (filterType == null || m.type == filterType) && (q.isEmpty() || m.title.lowercase().contains(q))
                    }
                    val filtering = q.isNotEmpty() || filterType != null
                    countView.text = if (filtering) "${shown.size} dari ${listing.items.size} entri$extra" else "${listing.items.size} entri$extra"
                    chip.text = "Jenis: ${filterType?.let { typeShort(it) } ?: "SEMUA"}  ▸ ketuk untuk ganti"
                    if (shown.isEmpty()) {
                        box.addView(TextView(context).apply {
                            text = if (listing.items.isEmpty()) "Belum ada entri. Ketuk Tambah entri atau Impor banyak berkas." else "Tidak ada entri yang cocok."
                            textSize = 14f
                            setTextColor(Color.GRAY)
                            setPadding(0, 24, 0, 24)
                        })
                    }
                    for (m in shown) {
                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            background = host.cardDrawable()
                            setPadding(host.dp(14f), host.dp(12f), host.dp(14f), host.dp(12f))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                                .apply { setMargins(0, 0, 0, host.dp(8f)) }
                            isClickable = true
                            setOnClickListener { openDetail(m) }
                        }
                        row.addView(TextView(context).apply { text = m.title; textSize = 16f; setTextColor(Color.WHITE) })
                        row.addView(TextView(context).apply {
                            text = "${typeShort(m.type)} · ${dateFmt.format(Date(m.modifiedAt))}"
                            textSize = 12f
                            setTextColor(Color.GRAY)
                        })
                        box.addView(row)
                    }
                }

                chip.setOnClickListener {
                    val all = RecordType.values()
                    val cur = filterType
                    filterType = if (cur == null) all.first() else all.getOrNull(cur.ordinal + 1)
                    fill()
                }
                search.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { filterText = s?.toString().orEmpty(); fill() }
                })
                fill()
            }
            button("Cadangan terenkripsi") { showBackupForm() }
            button("Ganti master password") { showChangeMaster() }
            button("Kunci sekarang") { SessionLock.lock(); render() }
        }
    }

    // MOLINA-H2
    private fun showChangeMaster(): Unit = host.run {
        clearSensitive()
        screen {
            label("Ganti master password", 22f, amber)
            label("Hanya header vault yang dibungkus ulang; entri tidak diubah. Perhatian: kunci data (vaultKey) TIDAK dirotasi, jadi salinan lama vault atau cadangan .mvault tetap terbuka dengan master/passphrase lamanya. Jika master lama diduga bocor, buat vault baru lalu pindahkan entri.", 13f, Color.LTGRAY)
            val cur = field("Master password saat ini")
            val n1 = field("Master password baru")
            val n2 = field("Ulangi master password baru")
            val msg = messageView()
            button("Ganti master") { b ->
                val old = take(cur)
                val a = take(n1)
                val c = take(n2)
                val same = a.contentEquals(c)
                c.fill(Char.MIN_VALUE)
                val problem = when {
                    !same -> "Master baru tidak sama."
                    old.contentEquals(a) -> "Master baru harus berbeda dari yang lama."
                    !PasswordPolicy.isAcceptable(a) -> "Master baru terlalu lemah."
                    else -> null
                }
                if (problem != null) {
                    old.fill(Char.MIN_VALUE)
                    a.fill(Char.MIN_VALUE)
                    msg.text = problem
                    return@button
                }
                b.isEnabled = false
                msg.text = "Memproses…"
                worker.execute {
                    val err: String? = try {
                        if (!store.matchesMaster(old)) "Master password saat ini salah."
                        else { store.changeMaster(a); null }
                    } catch (e: Exception) {
                        e.message ?: "Gagal mengganti master"
                    }
                    old.fill(Char.MIN_VALUE)
                    a.fill(Char.MIN_VALUE)
                    ui.post {
                        if (err == null) {
                            Toast.makeText(host, "Master password diganti. Cadangan lama tetap memakai passphrase-nya sendiri.", Toast.LENGTH_LONG).show()
                            if (SessionLock.isUnlocked) show()
                        } else {
                            msg.text = err
                            b.isEnabled = true
                        }
                    }
                }
            }
            button("Batal") { show() }
        }
    }

    private fun showEdit(m: RecordMeta) {
        val copy = detailFields.map { it.copyOf() }
        showForm(m.type, m, copy)
    }

    private fun showTypePick(): Unit = host.run {
        screen {
            label("Jenis entri", 22f, amber)
            for (t in RecordType.values()) { button(typeName(t)) { showForm(t) }; label(typeHint(t), 12f) }
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
        if (s.kind != Kind.TEXT) noClipboard(this)
        host.frameInput(this)
    }

    // MOLINA-H1: kolom rahasia tidak boleh disalin, dipotong, atau ditempel lewat menu seleksi (clipboard bisa dibaca aplikasi latar di Android 8).
    private fun noClipboard(e: EditText) {
        val deny = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }
        e.customSelectionActionModeCallback = deny
        e.customInsertionActionModeCallback = deny
        e.setOnLongClickListener { true }
    }

    private fun utf8(chars: CharArray): ByteArray {
        val bb = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val out = ByteArray(bb.remaining())
        bb.get(out)
        if (bb.hasArray()) bb.array().fill(0)
        chars.fill(Char.MIN_VALUE)
        return out
    }

    private fun showForm(type: RecordType, editing: RecordMeta? = null, prefill: List<ByteArray>? = null): Unit = host.run {
        clearSensitive()
        val specs = SPECS.getValue(type)
        editKeep = prefill?.getOrNull(specs.indexOfFirst { it.kind == Kind.FILE })
        screen {
            label(typeName(type), 22f, amber)
            if (specs.any { it.kind == Kind.SECRET || it.kind == Kind.MULTI }) {
                label("Salin dan tempel dimatikan di kolom rahasia agar tidak lewat clipboard. Ketik langsung atau impor dari berkas; jangan menempelkan rahasia dari aplikasi lain.", 12f)
            }
            val titleField = EditText(context).apply {
                hint = "Judul"
                setHintTextColor(Color.GRAY)
                setTextColor(Color.WHITE)
                setSingleLine(true)
                host.frameInput(this)
            }
            addView(titleField)
            if (editing != null) titleField.setText(editing.title)
            val inputs = ArrayList<EditText?>()
            for (s in specs) {
                if (s.kind == Kind.FILE) {
                    val keep = editKeep
                    val info = TextView(context).apply { text = if (keep != null) "File saat ini: ${keep.size} byte (pilih file baru untuk mengganti)" else "Belum ada file"; setTextColor(Color.LTGRAY); setPadding(0, 12, 0, 12) }
                    fileInfo = info
                    button("Pilih file…") { pickFile() }
                    addView(info)
                    inputs.add(null)
                } else {
                    val e = makeInput(s)
                    addView(e)
                    inputs.add(e)
                    prefill?.getOrNull(inputs.size - 1)?.let { setChars(e, it) }
                    if (s.label == "Nama file") nameField = e
                }
            }
            prefill?.forEachIndexed { i, a -> if (specs.getOrNull(i)?.kind != Kind.FILE) a.fill(0) }
            val msg = messageView()
            button("Simpan terenkripsi") { b ->
                val t = titleField.text.toString().trim()
                if (t.isEmpty()) { msg.text = "Judul wajib diisi."; return@button }
                if (specs.any { it.kind == Kind.FILE } && pickedBytes == null && editKeep == null) { msg.text = "Pilih file terlebih dulu."; return@button }
                val fields = ArrayList<ByteArray>()
                for ((i, s) in specs.withIndex()) {
                    if (s.kind == Kind.FILE) fields.add((pickedBytes ?: editKeep)!!.copyOf())
                    else fields.add(utf8(take(inputs[i]!!)))
                }
                b.isEnabled = false
                msg.text = "Mengenkripsi…"
                worker.execute {
                    val err: String? = try { if (editing != null) store.put(type, t, fields, editing.id) else store.put(type, t, fields); null } catch (e: Exception) { e.message ?: "Gagal menyimpan" }
                    fields.forEach { it.fill(0) }
                    ui.post {
                        if (err == null) show() else { msg.text = err; b.isEnabled = true }
                    }
                }
            }
            button("Batal") { show() }
        }
    }

    // ---------- cadangan terenkripsi (.mvault) ----------
    private fun wipeBackupPass() { backupPass?.fill(Char.MIN_VALUE); backupPass = null }

    private fun showBackupForm(): Unit = host.run {
        clearSensitive()
        screen {
            label("Cadangan terenkripsi", 22f, amber)
            label("Berkas .mvault dienkripsi dengan passphrase cadangan yang BERBEDA dari master password. Berkas ini bisa disalin lalu diserang offline, jadi kekuatan passphrase adalah satu-satunya pelindungnya. Tanpa passphrase, cadangan tidak bisa dibuka dan tidak ada pemulihan.", 13f, Color.LTGRAY)
            button("Buat passphrase acak (disarankan)") { showGeneratedPassphrase() }
            button("Saya pakai passphrase sendiri") { showCustomPassphrase() }
            button("Batal") { show() }
        }
    }

    private fun showGeneratedPassphrase(): Unit = host.run {
        clearSensitive()
        val gen = PassphraseGenerator.generate()
        screen {
            label("Passphrase cadangan Anda", 22f, amber)
            label("Tulis di kertas dan simpan TERPISAH dari berkas .mvault. Jika hilang, cadangan tidak bisa dibuka. Layar ini tidak bisa di-screenshot. Ketik persis, termasuk tanda hubung.", 13f, Color.LTGRAY)
            addView(TextView(context).apply {
                setText(gen, 0, gen.size)
                textSize = 20f
                setTextColor(amber)
                typeface = Typeface.MONOSPACE
                setPadding(0, 24, 0, 24)
            })
            val confirm = field("Ketik ulang passphrase untuk konfirmasi")
            val msg = messageView()
            button("Lanjut dan pilih lokasi") { b ->
                val typed = take(confirm)
                val same = typed.contentEquals(gen)
                typed.fill(Char.MIN_VALUE)
                if (!same) { msg.text = "Tidak cocok. Ketik persis, termasuk tanda hubung."; return@button }
                b.isEnabled = false
                msg.text = "Memeriksa…"
                proceedBackup(gen.copyOf()) { m -> msg.text = m; b.isEnabled = true }
            }
            button("Buat ulang") { gen.fill(Char.MIN_VALUE); showGeneratedPassphrase() }
            button("Batal") { gen.fill(Char.MIN_VALUE); show() }
        }
    }

    private fun showCustomPassphrase(): Unit = host.run {
        clearSensitive()
        screen {
            label("Passphrase sendiri", 22f, amber)
            label("Minimal 24 karakter, minimal 10 karakter unik, hanya ASCII. Passphrase buatan manusia jauh lebih mudah ditebak daripada yang acak; pilih passphrase acak jika ragu.", 13f, Color.LTGRAY)
            val p1 = field("Passphrase cadangan")
            val p2 = field("Ulangi passphrase cadangan")
            val msg = messageView()
            button("Pilih lokasi dan ekspor") { b ->
                val a = take(p1)
                val c = take(p2)
                val same = a.contentEquals(c)
                c.fill(Char.MIN_VALUE)
                val problem = when {
                    !same -> "Passphrase tidak sama."
                    !BackupPolicy.isAcceptable(a) -> "Minimal 24 karakter, 10 karakter unik, hanya ASCII."
                    else -> null
                }
                if (problem != null) { a.fill(Char.MIN_VALUE); msg.text = problem; return@button }
                b.isEnabled = false
                msg.text = "Memeriksa…"
                proceedBackup(a) { m -> msg.text = m; b.isEnabled = true }
            }
            button("Kembali") { showBackupForm() }
        }
    }

    /** Memastikan passphrase berbeda dari master, lalu membuka pemilih lokasi. [pass] dimiliki fungsi ini. */
    private fun proceedBackup(pass: CharArray, reject: (String) -> Unit) {
        host.worker.execute {
            val sameAsMaster = host.store.matchesMaster(pass)
            host.ui.post {
                if (sameAsMaster || !SessionLock.isUnlocked) {
                    pass.fill(Char.MIN_VALUE)
                    reject(if (sameAsMaster) "Passphrase cadangan harus berbeda dari master password." else "Vault terkunci.")
                } else {
                    wipeBackupPass()
                    backupPass = pass
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    launchExternal(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_TITLE, "molina-vault-$stamp.mvault")
                    }, REQ_BACKUP_EXPORT)
                }
            }
        }
    }

    private fun backupTo(uri: Uri) {
        val pass = backupPass ?: return
        backupPass = null
        Toast.makeText(host, "Mengekspor cadangan…", Toast.LENGTH_SHORT).show()
        host.worker.execute {
            val r = runCatching {
                val out = host.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Tidak bisa menulis berkas")
                out.use { BackupStore.export(host.store, it, pass) }
            }
            pass.fill(Char.MIN_VALUE)
            host.ui.post {
                val res = r.getOrNull()
                val text = when {
                    res == null -> "Gagal mengekspor cadangan. Hapus berkas yang terbentuk."
                    res.skipped > 0 -> "Cadangan ${res.count} entri. ${res.skipped} entri rusak dilewati."
                    else -> "Cadangan ${res.count} entri tersimpan. Uji pulihkan sebelum mengandalkannya."
                }
                Toast.makeText(host, text, Toast.LENGTH_LONG).show()
                if (SessionLock.isUnlocked) show()
            }
        }
    }

    /** Dipanggil dari layar setup (vault kosong). */
    fun pickBackup() {
        launchExternal(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQ_BACKUP_PICK)
    }

    fun showRestoreForm() {
        val uri = restoreUri ?: return
        host.run {
            screen {
                label("MOLINA VAULT", 26f, amber)
                label("Pulihkan: ${runCatching { displayName(uri) }.getOrNull() ?: "berkas .mvault"}", 15f, Color.WHITE)
                label("Vault di perangkat ini kosong, jadi cadangan dipulihkan penuh (tanpa gabung). Masukkan passphrase cadangan (ketik persis, termasuk tanda hubung), lalu pilih master password BARU untuk perangkat ini.", 13f, Color.LTGRAY)
                val pb = field("Passphrase cadangan")
                val m1 = field("Master password baru")
                val m2 = field("Ulangi master password baru")
                val msg = messageView()
                button("Pulihkan") { b ->
                    val bp = take(pb)
                    val a = take(m1)
                    val c = take(m2)
                    val same = a.contentEquals(c)
                    c.fill(Char.MIN_VALUE)
                    val problem = when {
                        !same -> "Master baru tidak sama."
                        !PasswordPolicy.isAcceptable(a) -> "Master password baru terlalu lemah."
                        bp.contentEquals(a) -> "Master baru harus berbeda dari passphrase cadangan."
                        else -> null
                    }
                    if (problem != null) { bp.fill(Char.MIN_VALUE); a.fill(Char.MIN_VALUE); msg.text = problem; return@button }
                    b.isEnabled = false
                    msg.text = "Memulihkan… (beberapa detik)"
                    worker.execute {
                        val err: String? = try {
                            val ins = contentResolver.openInputStream(uri) ?: throw IOException("Tidak bisa membuka berkas")
                            ins.use { BackupStore.restore(store, it, bp, a) }
                            null
                        } catch (e: WrongPasswordOrTamperedException) { "Passphrase salah atau cadangan rusak." }
                        catch (e: InvalidVaultException) { e.message ?: "Cadangan tidak valid." }
                        catch (e: Exception) { "Gagal memulihkan cadangan." }
                        bp.fill(Char.MIN_VALUE)
                        a.fill(Char.MIN_VALUE)
                        ui.post {
                            if (err == null) {
                                restoreUri = null
                                Toast.makeText(host, "Cadangan dipulihkan. Buka dengan master password baru.", Toast.LENGTH_LONG).show()
                                render()
                            } else {
                                msg.text = err
                                b.isEnabled = true
                            }
                        }
                    }
                }
                button("Batal") { restoreUri = null; render() }
            }
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

    // MOLINA-H3-MULTI
    private fun pickMulti() {
        launchExternal(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, REQ_IMPORT_MULTI)
    }

    private fun collectUris(data: Intent?): List<Uri> {
        val out = ArrayList<Uri>()
        val clip = data?.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out.add(it) }
        } else {
            data?.data?.let { out.add(it) }
        }
        return out
    }

    private fun importMany(all: List<Uri>) {
        val uris = all.take(MAX_BATCH)
        val over = all.size - uris.size
        var status: TextView? = null
        host.run {
            screen {
                label("Impor banyak berkas", 22f, amber)
                val s = TextView(context).apply { setTextColor(Color.WHITE); textSize = 15f; setPadding(0, 16, 0, 16); text = "Menyiapkan…" }
                addView(s)
                status = s
                label("Jangan tutup layar ini sampai selesai.", 12f)
            }
        }
        host.worker.execute {
            val existing = HashSet<String>()
            try { host.store.list().items.filter { it.type == RecordType.FILE }.forEach { existing.add(it.title) } } catch (e: Exception) { }
            var ok = 0
            val skipped = ArrayList<String>()
            val failed = ArrayList<String>()
            for ((i, uri) in uris.withIndex()) {
                val name = (displayName(uri) ?: "berkas-${i + 1}").take(200)
                host.ui.post { status?.text = "(${i + 1}/${uris.size}) $name" }
                if (name in existing) { skipped.add(name); continue }
                var bytes: ByteArray? = null
                try {
                    bytes = readLimited(uri)
                    host.store.put(RecordType.FILE, name, listOf(name.toByteArray(Charsets.UTF_8), bytes, ByteArray(0)))
                    existing.add(name)
                    ok++
                } catch (e: Exception) {
                    failed.add("$name: ${e.message ?: "gagal"}")
                } finally {
                    bytes?.fill(0)
                }
                host.ui.post { host.onUserInteraction() }
            }
            host.ui.post {
                if (!SessionLock.isUnlocked) host.render() else showImportResult(ok, skipped, failed, over)
            }
        }
    }

    private fun showImportResult(ok: Int, skipped: List<String>, failed: List<String>, over: Int): Unit = host.run {
        screen {
            label("Impor selesai", 22f, amber)
            label("Berhasil: $ok", 16f, Color.WHITE)
            if (skipped.isNotEmpty()) label("Dilewati (judul sama sudah ada): ${skipped.size}", 14f)
            if (over > 0) label("Melebihi batas $MAX_BATCH berkas per impor: $over berkas tidak diproses. Ulangi impor untuk sisanya.", 14f, amber)
            if (failed.isNotEmpty()) {
                label("Gagal: ${failed.size}", 14f, red)
                for (f in failed.take(10)) label(f, 12f, red)
            }
            button("Selesai") { show() }
        }
    }

    private fun pickFile() {
        launchExternal(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQ_IMPORT)
    }

    fun onResult(req: Int, res: Int, data: Intent?) {
        if (req != REQ_IMPORT && req != REQ_IMPORT_MULTI && req != REQ_EXPORT && req != REQ_BACKUP_EXPORT && req != REQ_BACKUP_PICK) return
        host.ui.removeCallbacks(pickerLock)
        // Dilepas lewat post: onActivityResult datang SEBELUM onStart, dan onStart memeriksa holdScreen.
        host.ui.post { holdScreen = false }
        val uri = data?.data
        if (req == REQ_BACKUP_PICK) {
            if (res == Activity.RESULT_OK && uri != null && !SessionLock.isUnlocked) restoreUri = uri
            return
        }
        if (!SessionLock.isUnlocked) { clearSensitive(); host.render(); return }
        if (req == REQ_IMPORT_MULTI) {
            val picked = collectUris(data)
            if (res == Activity.RESULT_OK && picked.isNotEmpty()) importMany(picked)
            return
        }
        if (res != Activity.RESULT_OK || uri == null) { if (req == REQ_BACKUP_EXPORT) wipeBackupPass(); return }
        when (req) {
            REQ_IMPORT -> importFrom(uri)
            REQ_EXPORT -> exportTo(uri)
            else -> backupTo(uri)
        }
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
        background = host.frameDrawable(Color.parseColor("#141416"), Color.parseColor("#4A3B00"), 1.2f, 8f)
        setPadding(host.dp(14f), host.dp(10f), host.dp(14f), host.dp(10f))
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
        if (host.untrustedAccessibilityServices().isNotEmpty()) {
            tv.text = "Diblokir: accessibility service non-sistem aktif."
            return
        }
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
                        if (m.type == RecordType.FILE && canPreview(i)) button("Pratinjau di dalam Vault") { preview(m.title, i) }
                        button("Ekspor file…") { confirmExport(i) }
                    }
                    Kind.TEXT -> addView(valueView().also { setChars(it, data) })
                    else -> {
                        val tv = valueView()
                        tv.text = MASK
                        addView(tv)
                        button("Tampilkan 10 detik") { reveal(tv, data) }
                        if (m.type == RecordType.NOTE && canPreview(i)) button("Pratinjau markdown") { preview(m.title, i) }
                    }
                }
            }
            button("Ubah entri") { showEdit(m) }
            button("Hapus entri") { confirmDelete(m) }
            button("Kembali") { show() }
        }
    }

    // MOLINA-H4-PREVIEW
    private fun canPreview(index: Int): Boolean {
        val data = detailFields.getOrNull(index) ?: return false
        if (data.size > PREVIEW_MAX) return false
        val m = detailMeta ?: return false
        if (m.type == RecordType.NOTE) return true
        val name = detailFields.getOrNull(index - 1)?.let { String(it, Charsets.UTF_8).lowercase() } ?: return false
        return name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".txt")
    }

    private fun preview(title: String, index: Int) {
        if (host.untrustedAccessibilityServices().isNotEmpty()) {
            Toast.makeText(host, "Diblokir: accessibility service non-sistem aktif.", Toast.LENGTH_LONG).show()
            return
        }
        val src = detailFields.getOrNull(index) ?: return
        if (src.size > PREVIEW_MAX) return
        val data = src.copyOf()
        val plain = detailMeta?.type == RecordType.FILE &&
            detailFields.getOrNull(index - 1)?.let { String(it, Charsets.UTF_8).lowercase().endsWith(".txt") } == true
        host.worker.execute {
            var rendered: CharSequence? = null
            var err: String? = null
            try {
                val cb = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                val chars = CharArray(cb.remaining())
                cb.get(chars)
                if (cb.hasArray()) cb.array().fill(Char.MIN_VALUE)
                try {
                    val s = String(chars)
                    rendered = if (plain) s else MarkdownRender.render(s)
                } finally {
                    chars.fill(Char.MIN_VALUE)
                }
            } catch (e: CharacterCodingException) {
                err = "Bukan teks UTF-8. Gunakan Ekspor untuk membukanya."
            } catch (e: Exception) {
                err = "Gagal memuat pratinjau"
            } finally {
                data.fill(0)
            }
            val body = rendered
            val msg = err
            host.ui.post { if (SessionLock.isUnlocked) showPreview(title, body, msg) }
        }
    }

    private fun showPreview(title: String, body: CharSequence?, err: String?): Unit = host.run {
        screen {
            label(title, 18f, amber)
            button("Tutup pratinjau") { showDetail() }
            if (body == null) {
                label(err ?: "Gagal memuat pratinjau", 14f, red)
            } else {
                addView(TextView(context).apply {
                    text = body
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    setLineSpacing(8f, 1f)
                    setPadding(0, 24, 0, 24)
                    setTextIsSelectable(false)
                    isLongClickable = false
                })
                button("Tutup pratinjau") { showDetail() }
            }
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

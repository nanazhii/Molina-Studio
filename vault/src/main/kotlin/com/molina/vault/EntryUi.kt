package com.molina.vault

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
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
import com.molina.vault.crypto.StreamCrypto
import com.molina.vault.crypto.WrongPasswordOrTamperedException
import com.molina.vault.store.BackupStore
import com.molina.vault.store.BlobInfo
import com.molina.vault.store.InsufficientSpaceException
import com.molina.vault.store.LabelPolicy
import com.molina.vault.store.RecordMeta
import com.molina.vault.store.RecordType
import com.molina.vault.store.VAULT_LARGE_THRESHOLD
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
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
private const val MAX_FILE = 1024 * 1024   // MOLINA-H12-STREAM: = VAULT_LARGE_THRESHOLD; di atasnya memakai jalur streaming v3
private const val PREVIEW_MAX = 512 * 1024
private const val MASK = "••••••••"
private const val PICKER_TIMEOUT_MS = 60_000L
private const val REVEAL_MS = 10_000L

private class WipeableBuffer : ByteArrayOutputStream() {
    fun wipe() { buf.fill(0) }
}

/** MOLINA-H12-STREAM: berkas melebihi ambang v2; pemanggil beralih ke jalur streaming. */
private class TooLargeException : IOException("File lebih dari 1 MiB. Gunakan Impor banyak berkas untuk berkas besar.")

/** MOLINA-H12-STREAM: pembungkus aliran impor: laporan kemajuan (maks 4x/detik) dan pembatalan oleh pengguna. */
private class ProgressInputStream(
    src: InputStream,
    private val cancelled: () -> Boolean,
    private val onProgress: (Long) -> Unit,
) : FilterInputStream(src) {
    private var total = 0L
    private var lastTick = 0L

    private fun tick(n: Int) {
        if (cancelled()) throw IOException("Dibatalkan")
        if (n > 0) {
            total += n
            val now = System.currentTimeMillis()
            if (now - lastTick >= 250L) { lastTick = now; onProgress(total) }
        }
    }

    override fun read(): Int { val b = super.read(); tick(if (b >= 0) 1 else 0); return b }
    override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); tick(n); return n }
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
    private var detailBlob: BlobInfo? = null   // MOLINA-H12-STREAM: deskriptor blob bila entri v3
    @Volatile private var importCancelled = false
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
    private var filterFolder: String? = null   // MOLINA-H6-FOLDER: null = semua folder
    private val dateFmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

    /** true jika berkas cadangan sudah dipilih dan menunggu passphrase serta master baru. */
    val hasPendingRestore: Boolean get() = restoreUri != null
    private val pickerLock = Runnable { SessionLock.lock(); clearSensitive() }

    fun show() { clearSensitive(); showList() }

    fun clearSensitive() {
        pickedBytes?.fill(0); pickedBytes = null
        detailFields.forEach { it.fill(0) }; detailFields = emptyList()
        detailMeta = null; detailBlob = null; nameField = null; fileInfo = null; exportIndex = -1
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
            brandHeader()
            backupReminder()?.let { msg ->
                addView(TextView(context).apply {
                    text = msg
                    textSize = 13f
                    setTextColor(amber)
                    background = host.cardDrawable()
                    setPadding(host.dp(14f), host.dp(10f), host.dp(14f), host.dp(10f))
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { setMargins(0, host.dp(12f), 0, host.dp(4f)) }
                    setOnClickListener { showBackupForm() }
                })
            }
            button("Tambah entri") { showTypePick() }
            button("Impor banyak berkas") { pickMulti() }
            val listing = try { store.list() } catch (e: Exception) { null }
            if (listing == null) {
                label("Gagal membaca daftar entri.", 14f, red)
            } else {
                val extra = if (listing.corrupt > 0) " (${listing.corrupt} rusak/dimanipulasi)" else ""
                val countView = TextView(context).apply { setTextColor(Color.WHITE); textSize = 16f; setPadding(0, 20, 0, 8) }
                addView(countView)
                val search = makeInput(Spec("Cari judul atau tag", Kind.TEXT)).apply { setSingleLine(true); setText(filterText) }
                addView(search)
                val chip = TextView(context).apply {
                    textSize = 14f
                    setTextColor(amber)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    background = host.cardDrawable()
                    setPadding(host.dp(14f), host.dp(10f), host.dp(14f), host.dp(10f))
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { setMargins(0, host.dp(4f), host.dp(6f), host.dp(12f)) }
                }
                val chipRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                chipRow.addView(chip)
                val folderChip = TextView(context).apply {
                    textSize = 14f
                    setTextColor(amber)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    background = host.cardDrawable()
                    setPadding(host.dp(14f), host.dp(10f), host.dp(14f), host.dp(10f))
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { setMargins(host.dp(6f), host.dp(4f), 0, host.dp(12f)) }
                }
                chipRow.addView(folderChip)
                addView(chipRow)
                val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                addView(box)

                fun fill() {
                    box.removeAllViews()
                    val folderNames = listing.items.map { it.folder }.filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() }
                    val ff = filterFolder
                    if (ff != null && ff !in folderNames) filterFolder = null
                    folderChip.visibility = if (folderNames.isEmpty()) View.GONE else View.VISIBLE
                    val q = filterText.trim().lowercase()
                    val shown = listing.items.filter { m ->
                        (filterType == null || m.type == filterType) && (filterFolder == null || m.folder == filterFolder) &&
                            (q.isEmpty() || m.title.lowercase().contains(q) || m.tags.any { it.lowercase().contains(q) })
                    }
                    val filtering = q.isNotEmpty() || filterType != null || filterFolder != null
                    countView.text = if (filtering) "${shown.size} dari ${listing.items.size} entri$extra" else "${listing.items.size} entri$extra"
                    chip.text = "Jenis: ${filterType?.let { typeShort(it) } ?: "SEMUA"} ▾"
                    folderChip.text = "Folder: ${filterFolder ?: "SEMUA"} ▾"
                    if (shown.isEmpty()) {
                        box.addView(TextView(context).apply {
                            text = if (listing.items.isEmpty()) "Vault masih kosong.\nKetuk Tambah entri atau Impor banyak berkas untuk mulai." else "Tidak ada entri yang cocok."
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
                        // MOLINA-H7-DESIGN: badge jenis berwarna redup + folder dan tanggal
                        val metaRow = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, host.dp(6f), 0, 0)
                        }
                        metaRow.addView(TextView(context).apply {
                            text = typeShort(m.type)
                            textSize = 10f
                            typeface = Typeface.DEFAULT_BOLD
                            letterSpacing = 0.08f
                            setTextColor(host.typeColor(m.type))
                            background = host.badgeDrawable(host.typeColor(m.type))
                            setPadding(host.dp(8f), host.dp(2f), host.dp(8f), host.dp(2f))
                        })
                        metaRow.addView(TextView(context).apply {
                            text = buildString {
                                if (m.folder.isNotEmpty()) append(m.folder).append(" · ")
                                append(dateFmt.format(Date(m.modifiedAt)))
                            }
                            textSize = 12f
                            setTextColor(Color.GRAY)
                            setPadding(host.dp(10f), 0, 0, 0)
                        })
                        row.addView(metaRow)
                        if (m.tags.isNotEmpty()) {
                            row.addView(TextView(context).apply {
                                text = m.tags.joinToString("  ") { "#$it" }
                                textSize = 12f
                                setTextColor(amber)
                            })
                        }
                        box.addView(row)
                    }
                }

                chip.setOnClickListener {
                    val all = RecordType.values()
                    val cur = filterType
                    filterType = if (cur == null) all.first() else all.getOrNull(cur.ordinal + 1)
                    fill()
                }
                folderChip.setOnClickListener {
                    val names = listing.items.map { it.folder }.filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() }
                    val cur = filterFolder
                    filterFolder = if (cur == null) names.firstOrNull() else names.getOrNull(names.indexOf(cur) + 1)
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
            button("Pengaturan") { showSettings() }
            button("Kunci sekarang") { SessionLock.lock(); render() }
        }
    }

    // ---------- pengaturan, pengingat cadangan, folder (MOLINA-H7B) ----------
    private fun backupReminder(): String? {
        val prefs = host.store.prefs
        val changes = prefs.changesSinceBackup
        if (changes == 0) return null
        val last = prefs.lastBackupAt
        if (last == 0L) return "Belum pernah mencadangkan: $changes perubahan belum aman dari uninstall atau reset. Ketuk untuk membuat cadangan."
        val days = (System.currentTimeMillis() - last) / 86_400_000L
        if (changes >= 5 || days >= 14) return "$changes perubahan sejak cadangan terakhir ($days hari lalu). Ketuk untuk mencadangkan."
        return null
    }

    private fun backupStatus(): String {
        val prefs = host.store.prefs
        val last = prefs.lastBackupAt
        val ch = prefs.changesSinceBackup
        if (last == 0L) return if (ch == 0) "belum pernah dibuat" else "belum pernah dibuat ($ch perubahan belum tercadangkan)"
        return "terakhir ${dateFmt.format(Date(last))}, $ch perubahan sejak itu"
    }

    private fun LinearLayout.folderCard(caption: String, onClick: () -> Unit) {
        addView(TextView(context).apply {
            text = caption
            textSize = 15f
            setTextColor(Color.WHITE)
            background = host.cardDrawable()
            setPadding(host.dp(14f), host.dp(10f), host.dp(14f), host.dp(10f))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, host.dp(8f)) }
            setOnClickListener { onClick() }
        })
    }

    private fun showSettings(): Unit = host.run {
        clearSensitive()
        screen {
            label("Pengaturan", 22f, amber)
            label("Auto-lock: vault terkunci setelah tidak disentuh selama waktu ini, dan langsung saat aplikasi ditinggalkan.", 13f, Color.LTGRAY)
            val cur = store.prefs.autoLockMs
            for ((name, ms) in listOf("30 detik" to 30_000L, "2 menit" to 120_000L, "5 menit" to 300_000L)) {
                button(if (cur == ms) "\u2713 $name" else name) { store.prefs.autoLockMs = ms; host.onUserInteraction(); showSettings() }
            }
            label("Cadangan: ${backupStatus()}", 13f, Color.LTGRAY)
            button("Cadangan terenkripsi") { showBackupForm() }
            val items = try { store.list().items } catch (e: Exception) { emptyList() }
            val folders = items.map { it.folder }.filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() }
            if (folders.isNotEmpty()) {
                label("Folder (ketuk untuk ganti nama)", 13f, Color.LTGRAY)
                for (f in folders) folderCard("$f \u00b7 ${items.count { it.folder == f }} entri") { showRenameFolder(f) }
            }
            button("Kembali") { show() }
        }
    }

    private fun showRenameFolder(old: String): Unit = host.run {
        clearSensitive()
        screen {
            label("Ganti nama folder", 22f, amber)
            label("Semua entri di folder \"$old\" dipindah ke nama baru. Bila nama baru sudah ada, isinya digabung. Kosongkan nama untuk mengeluarkan entri dari folder.", 13f, Color.LTGRAY)
            val input = makeInput(Spec("Nama folder baru", Kind.TEXT)).apply { setSingleLine(true); setText(old) }
            addView(input)
            val msg = messageView()
            button("Simpan") { b ->
                val to = LabelPolicy.cleanFolder(input.text.toString())
                b.isEnabled = false
                msg.text = "Memproses\u2026"
                worker.execute {
                    val r = runCatching { store.renameFolder(old, to) }
                    ui.post {
                        if (r.isSuccess) {
                            if (filterFolder == old) filterFolder = null
                            showSettings()
                        } else {
                            msg.text = r.exceptionOrNull()?.message ?: "Gagal mengganti nama folder"
                            b.isEnabled = true
                        }
                    }
                }
            }
            button("Batal") { showSettings() }
        }
    }

    private fun showMoveFolder(m: RecordMeta): Unit = host.run {
        clearSensitive()
        screen {
            label("Pindah folder", 22f, amber)
            label(m.title, 15f, Color.WHITE)
            val input = makeInput(Spec("Nama folder (kosongkan = tanpa folder)", Kind.TEXT)).apply { setSingleLine(true); setText(m.folder) }
            addView(input)
            val items = try { store.list().items } catch (e: Exception) { emptyList() }
            val folders = items.map { it.folder }.filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() }
            if (folders.isNotEmpty()) {
                label("Folder yang ada (ketuk untuk memilih)", 13f, Color.LTGRAY)
                for (f in folders) folderCard(f) { input.setText(f) }
            }
            val msg = messageView()
            button("Simpan") { b ->
                val to = LabelPolicy.cleanFolder(input.text.toString())
                b.isEnabled = false
                msg.text = "Memproses\u2026"
                worker.execute {
                    val err: String? = try { store.relabel(m.id, to); null } catch (e: Exception) { e.message ?: "Gagal memindahkan" }
                    ui.post { if (err == null) show() else { msg.text = err; b.isEnabled = true } }
                }
            }
            button("Batal") { show() }
        }
    }

    // MOLINA-H2, MOLINA-H8-ROTATE
    private fun showChangeMaster(): Unit = host.run {
        clearSensitive()
        screen {
            label("Ganti master password", 22f, amber)
            label("Master dan kunci data (vaultKey) diganti sekaligus: semua entri dienkripsi ulang. Cadangan .mvault lama dan salinan lama vault tetap terbuka dengan master atau passphrase lamanya, jadi buat cadangan baru setelah ini dan hapus yang lama. Jangan tutup aplikasi selama proses berjalan.", 13f, Color.LTGRAY)
            val cur = field("Master password saat ini")
            val n1 = field("Master password baru")
            val n2 = field("Ulangi master password baru")
            revealToggle(cur, n1, n2)
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
                    val rotated = IntArray(1)
                    val err: String? = try {
                        if (!store.matchesMaster(old)) "Master password saat ini salah."
                        else { rotated[0] = store.changeMaster(a); null }
                    } catch (e: Exception) {
                        e.message ?: "Gagal mengganti master"
                    }
                    old.fill(Char.MIN_VALUE)
                    a.fill(Char.MIN_VALUE)
                    ui.post {
                        if (err == null) {
                            val extra = if (rotated[0] > 0) " ${rotated[0]} entri rusak dilewati." else ""
                            Toast.makeText(host, "Master dan kunci data diganti. Buat cadangan .mvault baru.$extra", Toast.LENGTH_LONG).show()
                            if (SessionLock.isUnlocked) show()
                        } else if (!SessionLock.isUnlocked) {
                            Toast.makeText(host, err, Toast.LENGTH_LONG).show()
                            host.render()
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
        if (detailBlob != null) {   // MOLINA-H12-STREAM: cegah penimpaan blob dengan field kosong
            Toast.makeText(host, "Berkas besar belum bisa diubah. Ekspor lalu impor ulang.", Toast.LENGTH_LONG).show()
            return
        }
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
            val folderField = makeInput(Spec("Folder (opsional)", Kind.TEXT)).apply {
                setSingleLine(true)
                setText(editing?.folder ?: filterFolder.orEmpty())
            }
            addView(folderField)
            val tagsField = makeInput(Spec("Tag, pisahkan dengan koma (opsional)", Kind.TEXT)).apply {
                setSingleLine(true)
                setText(editing?.tags?.joinToString(", ").orEmpty())
            }
            addView(tagsField)
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
                val folderValue = LabelPolicy.cleanFolder(folderField.text.toString())
                val tagValues = LabelPolicy.parseTags(tagsField.text.toString())
                b.isEnabled = false
                msg.text = "Mengenkripsi…"
                worker.execute {
                    val err: String? = try { if (editing != null) store.put(type, t, fields, editing.id, folderValue, tagValues) else store.put(type, t, fields, folder = folderValue, tags = tagValues); null } catch (e: Exception) { e.message ?: "Gagal menyimpan" }
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
                if (res != null) host.store.prefs.markBackedUp()
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
                brandHeader()
                label("Pulihkan: ${runCatching { displayName(uri) }.getOrNull() ?: "berkas .mvault"}", 15f, Color.WHITE)
                label("Vault di perangkat ini kosong, jadi cadangan dipulihkan penuh (tanpa gabung). Masukkan passphrase cadangan (ketik persis, termasuk tanda hubung), lalu pilih master password BARU untuk perangkat ini.", 13f, Color.LTGRAY)
                val pb = field("Passphrase cadangan")
                val m1 = field("Master password baru")
                val m2 = field("Ulangi master password baru")
                revealToggle(pb, m1, m2)
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
                                store.prefs.markBackedUp()
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
        val importFolder = filterFolder.orEmpty()
        val over = all.size - uris.size
        importCancelled = false
        var status: TextView? = null
        host.run {
            screen {
                label("Impor banyak berkas", 22f, amber)
                if (importFolder.isNotEmpty()) label("Masuk ke folder: $importFolder", 13f)
                val s = TextView(context).apply { setTextColor(Color.WHITE); textSize = 15f; setPadding(0, 16, 0, 16); text = "Menyiapkan…" }
                addView(s)
                status = s
                label("Jangan tutup layar ini sampai selesai.", 12f)
                button("Batal") { b -> importCancelled = true; b.isEnabled = false }
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
                if (importCancelled) {
                    if (failed.none { it.contains("dibatalkan") }) failed.add("Impor dibatalkan oleh pengguna")
                    break
                }
                if (name in existing) { skipped.add(name); continue }
                var bytes: ByteArray? = null
                try {
                    val size = querySize(uri)
                    val progress: (Long) -> Unit = { done ->
                        host.ui.post {
                            status?.text = "(${i + 1}/${uris.size}) $name · ${fmtSize(done)}" + (if (size > 0) " / ${fmtSize(size)}" else "")
                        }
                    }
                    if (size > VAULT_LARGE_THRESHOLD) {   // MOLINA-H12-STREAM
                        importLarge(uri, name, size, importFolder, progress)
                    } else {
                        try {
                            bytes = readLimited(uri)
                            host.store.put(RecordType.FILE, name, listOf(name.toByteArray(Charsets.UTF_8), bytes, ByteArray(0)), folder = importFolder)
                        } catch (e: TooLargeException) {
                            importLarge(uri, name, -1L, importFolder, progress)
                        }
                    }
                    existing.add(name)
                    ok++
                } catch (e: InsufficientSpaceException) {
                    failed.add("$name: ruang tidak cukup (butuh ${fmtSize(e.needed)}, tersedia ${fmtSize(e.free)})")
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
                    if (total > MAX_FILE) throw TooLargeException()
                    sink.write(buf, 0, n)
                }
            }
            return sink.toByteArray()
        } finally {
            buf.fill(0)
            sink.wipe()
        }
    }

    // MOLINA-H12-STREAM ------------------------------------------------------------------
    private fun querySize(uri: Uri): Long = try {
        host.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    } catch (e: Exception) { -1L }

    private fun fmtSize(b: Long): String = when {
        b >= (1L shl 20) -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        b >= (1L shl 10) -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        else -> "$b byte"
    }

    private fun hexPrefix(sha: ByteArray): String = sha.take(8).joinToString("") { "%02x".format(it) }

    /** Berkas > ambang v2: streaming ke record v3, memori O(64 KB). [size] = -1 bila tidak diketahui. */
    private fun importLarge(uri: Uri, name: String, size: Long, folder: String, onProgress: (Long) -> Unit) {
        if (size > StreamCrypto.MAX_STREAM_BYTES) throw IOException("Berkas lebih dari ${fmtSize(StreamCrypto.MAX_STREAM_BYTES)}")
        val raw = host.contentResolver.openInputStream(uri) ?: throw IOException("Tidak bisa membuka file")
        ProgressInputStream(raw, { importCancelled }, onProgress).use { ins ->
            host.store.putFile(
                RecordType.FILE, name,
                listOf(name.toByteArray(Charsets.UTF_8), ByteArray(0), ByteArray(0)),
                1, ins, folder = folder, expectedBytes = size,
            )
        }
    }

    /** Hasil ekspor yang gagal tidak boleh tertinggal sebagai plaintext parsial di tujuan SAF. */
    private fun discardExportTarget(uri: Uri) {
        var removed = false
        try { removed = DocumentsContract.deleteDocument(host.contentResolver, uri) } catch (e: Exception) { }
        if (!removed) {
            try { host.contentResolver.openOutputStream(uri, "wt")?.close() } catch (e: Exception) { }
        }
    }

    private fun exportLarge(uri: Uri, id: String) {
        host.worker.execute {
            var err: String? = null
            try {
                val out = host.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Tidak bisa menulis ke tujuan")
                out.use { host.store.exportBlob(id, it) }
            } catch (e: Exception) {
                err = e.message ?: "Gagal mengekspor"
            }
            if (err != null) discardExportTarget(uri)
            val msg = err
            host.ui.post {
                Toast.makeText(host, if (msg == null) "Diekspor tanpa enkripsi. Hapus setelah dipakai." else "Gagal mengekspor: $msg. Tujuan ekspor dibersihkan.", Toast.LENGTH_LONG).show()
            }
        }
    }
    // ------------------------------------------------------------------------------------

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
            var blob: BlobInfo? = null
            val r = runCatching {
                if (host.store.isLarge(m.id)) {   // MOLINA-H12-STREAM: v3, blob tidak dimuat
                    val o = host.store.openLarge(m.id)
                    blob = o.info
                    o.fields
                } else host.store.read(m.id)
            }
            val info = blob
            host.ui.post {
                val f = r.getOrNull()
                if (f == null) {
                    Toast.makeText(host, "Entri rusak atau dimanipulasi", Toast.LENGTH_LONG).show()
                } else if (!SessionLock.isUnlocked) {
                    f.forEach { it.fill(0) }
                } else {
                    clearSensitive()
                    detailFields = f
                    detailBlob = info
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
            if (m.folder.isNotEmpty()) label("Folder: ${m.folder}", 13f)
            if (m.tags.isNotEmpty()) label(m.tags.joinToString("  ") { "#$it" }, 13f)
            for ((i, s) in SPECS.getValue(m.type).withIndex()) {
                if (i >= detailFields.size) break
                val data = detailFields[i]
                label(s.label, 13f)
                when (s.kind) {
                    Kind.FILE -> {
                        val bi = detailBlob   // MOLINA-H12-STREAM
                        if (bi != null && bi.index == i) {
                            label("${fmtSize(bi.size)} · SHA-256 ${hexPrefix(bi.sha256)}", 14f, Color.WHITE)
                            label("Berkas besar: terenkripsi per bagian dan tidak dimuat ke memori.", 12f)
                        } else {
                            label("${data.size} byte · SHA-256 ${fingerprint(data)}", 14f, Color.WHITE)
                        }
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
            if (detailBlob == null) button("Ubah entri") { showEdit(m) }
            else label("Berkas besar: untuk mengubah, ekspor lalu impor ulang (pengeditan menyusul).", 12f)
            button("Pindah folder") { showMoveFolder(m) }
            button("Hapus entri") { confirmDelete(m) }
            button("Kembali") { show() }
        }
    }

    // MOLINA-H4-PREVIEW
    private fun canPreview(index: Int): Boolean {
        val bi = detailBlob   // MOLINA-H12-STREAM: blob v3 tidak dimuat, tidak ada pratinjau
        if (bi != null && bi.index == index) return false
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
        val bi = detailBlob   // MOLINA-H12-STREAM
        val id = detailMeta?.id
        if (bi != null && id != null && bi.index == exportIndex) { exportLarge(uri, id); return }
        val copy = detailFields.getOrNull(exportIndex)?.copyOf() ?: return
        host.worker.execute {
            val r = runCatching { host.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(copy) } }
            copy.fill(0)
            if (r.isFailure) discardExportTarget(uri)   // MOLINA-H12-STREAM
            host.ui.post {
                Toast.makeText(host, if (r.isSuccess) "Diekspor tanpa enkripsi. Hapus setelah dipakai." else "Gagal mengekspor", Toast.LENGTH_LONG).show()
            }
        }
    }
}

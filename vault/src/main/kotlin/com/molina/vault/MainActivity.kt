package com.molina.vault

// MOLINA-UI-v1
// MOLINA-H1
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.molina.vault.crypto.AttemptLimiter
import com.molina.vault.crypto.InvalidVaultException
import com.molina.vault.crypto.PasswordPolicy
import com.molina.vault.crypto.SessionLock
import com.molina.vault.crypto.WrongPasswordOrTamperedException
import com.molina.vault.store.RecordType
import com.molina.vault.store.VaultStore
import java.util.concurrent.Executors

// MOLINA-H13-INTENT: kontrak impor lintas aplikasi (lihat docs/VAULT_INTENT.md)
internal const val ACTION_IMPORT = "com.molina.vault.action.IMPORT"
internal const val EXTRA_SAVED_NAMES = "com.molina.vault.extra.SAVED_NAMES"
internal const val EXTRA_SAVED_SHA256 = "com.molina.vault.extra.SAVED_SHA256"
internal const val EXTRA_SAVED_SIZES = "com.molina.vault.extra.SAVED_SIZES"

class MainActivity : BunkerActivity() {
    private val bg = Color.parseColor("#0E0E10")
    internal val amber = Color.parseColor("#FFB300")
    internal val red = Color.parseColor("#FF6E6E")
    internal val ui = Handler(Looper.getMainLooper())
    internal val worker = Executors.newSingleThreadExecutor()
    internal lateinit var store: VaultStore
    private lateinit var limiter: AttemptLimiter
    private var started = false
    /** MOLINA-H13-INTENT: URI content:// dari permintaan impor; hidup hanya selama satu kunjungan (dihapus saat onStop). */
    internal var pendingImport: List<Uri> = emptyList()
    internal val entries by lazy { EntryUi(this) }
    private val autoLock = Runnable { SessionLock.lock(); render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = VaultStore(this)
        limiter = AttemptLimiter(this)
        if (savedInstanceState == null) acceptImport(intent)   // MOLINA-H13-INTENT
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptImport(intent)
        if (started) render()
    }

    /** Hanya URI content:// (file:// ditolak). Pemanggil sudah disaring sistem lewat izin signature pada alias. */
    private fun acceptImport(i: Intent?) {
        if (i?.action != ACTION_IMPORT) return
        val out = ArrayList<Uri>()
        val clip = i.clipData
        if (clip != null) {
            for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { if (it.scheme == "content") out.add(it) }
        }
        pendingImport = out.take(500)
        setIntent(Intent())
    }

    override fun onStart() { super.onStart(); started = true; if (!(entries.holdScreen && SessionLock.isUnlocked)) render() }

    override fun onStop() {
        started = false
        pendingImport = emptyList()   // MOLINA-H13-INTENT: fail-closed, impor harus selesai dalam satu kunjungan
        ui.removeCallbacks(autoLock)
        super.onStop()
    }

    override fun onUserInteraction() {
        if (SessionLock.isUnlocked) { ui.removeCallbacks(autoLock); ui.postDelayed(autoLock, store.prefs.autoLockMs) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        entries.onResult(requestCode, resultCode, data)
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    internal fun render() {
        entries.clearSensitive()
        ui.removeCallbacks(autoLock)
        when {
            !store.exists() -> if (entries.hasPendingRestore) entries.showRestoreForm() else showSetup()
            !SessionLock.isUnlocked -> showUnlock()
            else -> { if (pendingImport.isNotEmpty()) entries.showExternalImport() else entries.show(); ui.postDelayed(autoLock, store.prefs.autoLockMs) }
        }
    }

    // ---- helpers UI ----
    internal fun screen(build: LinearLayout.() -> Unit) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22f), dp(48f), dp(22f), dp(32f)); build() }
        val root = ScrollView(this).apply { setBackgroundColor(bg); addView(col) }
        setContentView(root)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(140).start()
    }

    internal fun LinearLayout.label(t: String, size: Float = 15f, color: Int = Color.LTGRAY) =
        addView(TextView(context).apply { text = t; textSize = size; setTextColor(color); setPadding(0, dp(6f), 0, dp(6f)); setLineSpacing(0f, 1.15f) })

    internal fun LinearLayout.field(hintText: String): EditText {
        val e = EditText(context).apply {
            hint = hintText
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        frameInput(e)
        addView(e)
        return e
    }

    // MOLINA-H5-FRAME: bingkai emas tipis untuk tombol, kolom, dan kartu. Warna tetap hitam-amber.
    private val ghostLabels = setOf(
        "Batal", "Kembali", "Kunci sekarang", "Tutup pratinjau",
        "Impor banyak berkas", "Cadangan terenkripsi", "Ganti master password", "Pulihkan dari cadangan",
        "Ekspor file…", "Tampilkan 10 detik", "Pilih file…",
        "Pengaturan", "Pindah folder", "30 detik", "2 menit", "5 menit",
    )
    private val dangerLabels = setOf("Hapus entri")
    private val surface = Color.parseColor("#141416")
    private val frameDim = Color.parseColor("#8A6A00")
    private val cardLine = Color.parseColor("#4A3B00")

    internal fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    internal fun frameDrawable(fill: Int, stroke: Int, strokeDp: Float, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(fill)
            setStroke(maxOf(1, dp(strokeDp)), stroke)
        }

    private fun rippleMask(): Drawable = frameDrawable(Color.WHITE, Color.WHITE, 0f, 12f)

    /** Kartu berbingkai dengan efek sentuh amber redup, untuk baris daftar dan chip filter. */
    internal fun cardDrawable(): Drawable =
        RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 179, 0)), frameDrawable(surface, cardLine, 1f, 12f), rippleMask())

    internal fun frameInput(e: EditText) {
        fun style(focused: Boolean) {
            e.background = frameDrawable(surface, if (focused) amber else frameDim, if (focused) 1.5f else 1f, 12f)
            e.setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }
        style(false)
        e.setOnFocusChangeListener { _, has -> style(has) }
        e.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(8f), 0, dp(8f)) }
    }

    internal fun LinearLayout.button(t: String, onClick: (Button) -> Unit): Button {
        val ghost = t in ghostLabels
        val danger = t in dangerLabels
        val tone = if (danger) red else amber
        val b = object : Button(context) {
            override fun setEnabled(enabled: Boolean) {
                super.setEnabled(enabled)
                alpha = if (enabled) 1f else 0.45f
            }
        }
        b.text = t
        b.typeface = Typeface.DEFAULT_BOLD
        b.letterSpacing = 0.05f
        b.minHeight = dp(48f)
        b.minimumHeight = dp(48f)
        b.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        b.stateListAnimator = null
        val face: Drawable
        if (ghost || danger) {
            b.setTextColor(tone)
            face = frameDrawable(surface, tone, 1f, 12f)
        } else {
            b.setTextColor(Color.BLACK)
            val inner = GradientDrawable().apply { setColor(amber); cornerRadius = 9f * resources.displayMetrics.density }
            face = LayerDrawable(arrayOf<Drawable>(frameDrawable(Color.TRANSPARENT, amber, 1f, 12f), inner))
                .also { it.setLayerInset(1, dp(3f), dp(3f), dp(3f), dp(3f)) }
        }
        b.background = RippleDrawable(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)), face, rippleMask())
        b.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(8f), 0, dp(4f)) }
        b.setOnClickListener { onClick(b) }
        addView(b)
        return b
    }

    // MOLINA-H7-DESIGN: judul merek, warna jenis entri, dan badge pil.
    internal fun LinearLayout.brandHeader(subtitle: String = "Brankas offline \u00b7 terenkripsi") {
        addView(TextView(context).apply {
            text = "MOLINA VAULT"
            textSize = 24f
            setTextColor(amber)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.18f
        })
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(amber, Color.TRANSPARENT))
            layoutParams = LinearLayout.LayoutParams(dp(120f), dp(2f)).apply { setMargins(0, dp(10f), 0, dp(8f)) }
        })
        addView(TextView(context).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Color.GRAY)
            letterSpacing = 0.04f
        })
    }

    internal fun typeColor(t: RecordType): Int = when (t) {
        RecordType.PASSWORD -> Color.parseColor("#7FA3D6")
        RecordType.NOTE -> Color.parseColor("#8DBF9A")
        RecordType.KEYSTORE -> Color.parseColor("#D4A85F")
        RecordType.SECRET -> Color.parseColor("#C48FB5")
        RecordType.FILE -> Color.parseColor("#9AA0AA")
    }

    internal fun badgeDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10f).toFloat()
        setColor(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)))
    }

    // MOLINA-H7B: lihat/sembunyikan kolom password. Selama terlihat, menu seleksi (salin/potong) dimatikan.
    internal fun LinearLayout.revealToggle(vararg fields: EditText) {
        val cb = CheckBox(context).apply { text = "Tampilkan password"; setTextColor(Color.LTGRAY) }
        cb.setOnCheckedChangeListener { _, on ->
            val variation = if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            for (e in fields) {
                val sel = e.selectionEnd
                e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or variation
                e.customSelectionActionModeCallback = if (on) object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
                    override fun onDestroyActionMode(mode: ActionMode?) {}
                } else null
                if (sel >= 0) e.setSelection(sel)
            }
        }
        addView(cb)
    }

    internal fun LinearLayout.messageView(): TextView {
        val m = TextView(context).apply { setTextColor(red); setPadding(0, 16, 0, 16) }
        addView(m)
        return m
    }

    /** Ambil isi field sebagai CharArray lalu kosongkan Editable. */
    internal fun take(e: EditText): CharArray {
        val t = e.text
        val out = CharArray(t.length)
        t.getChars(0, t.length, out, 0)
        t.clear()
        return out
    }

    private fun tickLock(msg: TextView, btn: Button) {
        val ms = limiter.remainingLockMs()
        if (ms > 0) {
            btn.isEnabled = false
            msg.text = "Terkunci karena terlalu banyak percobaan: ${(ms + 999) / 1000} detik"
            ui.postDelayed({ tickLock(msg, btn) }, 1000)
        } else if (!btn.isEnabled && msg.text.startsWith("Terkunci")) {
            if (untrustedAccessibilityServices().isEmpty()) {
                btn.isEnabled = true
                msg.text = ""
            } else {
                msg.text = "Diblokir: accessibility service non-sistem aktif."
            }
        }
    }

    // ---- layar ----
    private fun showSetup() = screen {
        brandHeader()
        label("Buat vault baru. Tidak ada pemulihan: jika master password hilang atau ponsel di-reset, data tidak dapat dibuka. Minimal 14 karakter, atau 5 kata.")
        val p1 = field("Master password")
        val p2 = field("Ulangi master password")
        revealToggle(p1, p2)
        val ack = CheckBox(context).apply { text = "Saya paham tidak ada pemulihan"; setTextColor(Color.LTGRAY) }
        addView(ack)
        val msg = messageView()
        button("Buat vault") { b ->
            val a = take(p1)
            val c = take(p2)
            val same = a.contentEquals(c)
            c.fill(Char.MIN_VALUE)
            val problem = when {
                !ack.isChecked -> "Centang pernyataan di atas."
                !same -> "Password tidak sama."
                !PasswordPolicy.isAcceptable(a) -> "Password terlalu lemah."
                else -> null
            }
            if (problem != null) { a.fill(Char.MIN_VALUE); msg.text = problem; return@button }
            b.isEnabled = false
            msg.text = "Memproses…"
            worker.execute {
                val err: String? = try { store.create(a); null } catch (e: Exception) { e.message ?: "Gagal membuat vault" }
                a.fill(Char.MIN_VALUE)
                ui.post {
                    if (!started) SessionLock.lock()
                    if (err == null) render() else { msg.text = err; b.isEnabled = true }
                }
            }
        }
        button("Pulihkan dari cadangan") { entries.pickBackup() }
    }

    private fun showUnlock() = screen {
        brandHeader()
        val bad = untrustedAccessibilityServices()
        if (bad.isNotEmpty()) {
            label("PERINGATAN: accessibility service non-sistem aktif (${bad.joinToString()}). Aplikasi itu bisa membaca layar. Tombol Buka diblokir selama layanan itu aktif; nonaktifkan lalu buka ulang Vault.", 13f, red)
        }
        for (w in DeviceHealth.warnings()) label(w, 13f, amber)
        val p = field("Master password")
        revealToggle(p)
        val msg = messageView()
        val btn = button("Buka") { b ->
            val pw = take(p)
            if (untrustedAccessibilityServices().isNotEmpty()) {
                pw.fill(Char.MIN_VALUE)
                msg.text = "Diblokir: accessibility service non-sistem aktif."
                b.isEnabled = false
                return@button
            }
            if (limiter.remainingLockMs() > 0) { pw.fill(Char.MIN_VALUE); tickLock(msg, b); return@button }
            b.isEnabled = false
            msg.text = "Memproses…"
            worker.execute {
                val err: String? = try { store.unlock(pw); null }
                catch (e: WrongPasswordOrTamperedException) { "Password salah, atau vault bukan milik perangkat ini." }
                catch (e: InvalidVaultException) { "Vault tidak valid atau parameter tidak aman." }
                catch (e: Exception) { "Gagal membuka vault." }
                pw.fill(Char.MIN_VALUE)
                ui.post {
                    if (err == null) {
                        limiter.recordSuccess()
                        if (!started) SessionLock.lock() else render()
                    } else {
                        limiter.recordFailure()
                        msg.text = err
                        b.isEnabled = true
                        tickLock(msg, b)
                    }
                }
            }
        }
        tickLock(msg, btn)
        if (bad.isNotEmpty()) {
            btn.isEnabled = false
            if (limiter.remainingLockMs() <= 0) msg.text = "Diblokir: accessibility service non-sistem aktif."
        }
    }
}

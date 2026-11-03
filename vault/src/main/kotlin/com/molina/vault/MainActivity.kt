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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
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
import com.molina.vault.store.VaultStore
import java.util.concurrent.Executors

class MainActivity : BunkerActivity() {
    private val bg = Color.parseColor("#0E0E10")
    internal val amber = Color.parseColor("#FFB300")
    internal val red = Color.parseColor("#FF6E6E")
    internal val ui = Handler(Looper.getMainLooper())
    internal val worker = Executors.newSingleThreadExecutor()
    internal lateinit var store: VaultStore
    private lateinit var limiter: AttemptLimiter
    private var started = false
    internal val entries by lazy { EntryUi(this) }
    private val autoLock = Runnable { SessionLock.lock(); render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = VaultStore(this)
        limiter = AttemptLimiter(this)
    }

    override fun onStart() { super.onStart(); started = true; if (!(entries.holdScreen && SessionLock.isUnlocked)) render() }

    override fun onStop() {
        started = false
        ui.removeCallbacks(autoLock)
        super.onStop()
    }

    override fun onUserInteraction() {
        if (SessionLock.isUnlocked) { ui.removeCallbacks(autoLock); ui.postDelayed(autoLock, 120_000) }
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
            else -> { entries.show(); ui.postDelayed(autoLock, 120_000) }
        }
    }

    // ---- helpers UI ----
    internal fun screen(build: LinearLayout.() -> Unit) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(56, 120, 56, 56); build() }
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(col) })
    }

    internal fun LinearLayout.label(t: String, size: Float = 15f, color: Int = Color.LTGRAY) =
        addView(TextView(context).apply { text = t; textSize = size; setTextColor(color); setPadding(0, 16, 0, 16) })

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
    private val ghostLabels = setOf("Batal", "Kembali", "Kunci sekarang", "Tutup pratinjau")
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

    private fun rippleMask(): Drawable = frameDrawable(Color.WHITE, Color.WHITE, 0f, 8f)

    /** Kartu berbingkai dengan efek sentuh amber redup, untuk baris daftar dan chip filter. */
    internal fun cardDrawable(): Drawable =
        RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 179, 0)), frameDrawable(surface, cardLine, 1.2f, 8f), rippleMask())

    internal fun frameInput(e: EditText) {
        fun style(focused: Boolean) {
            e.background = frameDrawable(surface, if (focused) amber else frameDim, if (focused) 1.8f else 1.2f, 8f)
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
            face = frameDrawable(surface, tone, 1.5f, 8f)
        } else {
            b.setTextColor(Color.BLACK)
            val inner = GradientDrawable().apply { setColor(amber); cornerRadius = 6f * resources.displayMetrics.density }
            face = LayerDrawable(arrayOf<Drawable>(frameDrawable(Color.TRANSPARENT, amber, 1.5f, 9f), inner))
                .also { it.setLayerInset(1, dp(4f), dp(4f), dp(4f), dp(4f)) }
        }
        b.background = RippleDrawable(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)), face, rippleMask())
        b.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(8f), 0, dp(4f)) }
        b.setOnClickListener { onClick(b) }
        addView(b)
        return b
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
        label("MOLINA VAULT", 26f, amber)
        label("Buat vault baru. Tidak ada pemulihan: jika master password hilang atau ponsel di-reset, data tidak dapat dibuka. Minimal 14 karakter, atau 5 kata.")
        val p1 = field("Master password")
        val p2 = field("Ulangi master password")
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
        label("MOLINA VAULT", 26f, amber)
        val bad = untrustedAccessibilityServices()
        if (bad.isNotEmpty()) {
            label("PERINGATAN: accessibility service non-sistem aktif (${bad.joinToString()}). Aplikasi itu bisa membaca layar. Tombol Buka diblokir selama layanan itu aktif; nonaktifkan lalu buka ulang Vault.", 13f, red)
        }
        for (w in DeviceHealth.warnings()) label(w, 13f, amber)
        val p = field("Master password")
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

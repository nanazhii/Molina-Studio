package com.molina.vault

// MOLINA-UI-v1
import android.graphics.Color
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
    private val amber = Color.parseColor("#FFB300")
    private val red = Color.parseColor("#FF6E6E")
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: VaultStore
    private lateinit var limiter: AttemptLimiter
    private var started = false
    private val autoLock = Runnable { SessionLock.lock(); render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = VaultStore(this)
        limiter = AttemptLimiter(this)
    }

    override fun onStart() { super.onStart(); started = true; render() }

    override fun onStop() {
        started = false
        ui.removeCallbacksAndMessages(null)
        super.onStop()
    }

    override fun onUserInteraction() {
        if (SessionLock.isUnlocked) { ui.removeCallbacks(autoLock); ui.postDelayed(autoLock, 120_000) }
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    private fun render() {
        ui.removeCallbacks(autoLock)
        when {
            !store.exists() -> showSetup()
            !SessionLock.isUnlocked -> showUnlock()
            else -> { showVault(); ui.postDelayed(autoLock, 120_000) }
        }
    }

    // ---- helpers UI ----
    private fun screen(build: LinearLayout.() -> Unit) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(56, 120, 56, 56); build() }
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(col) })
    }

    private fun LinearLayout.label(t: String, size: Float = 15f, color: Int = Color.LTGRAY) =
        addView(TextView(context).apply { text = t; textSize = size; setTextColor(color); setPadding(0, 16, 0, 16) })

    private fun LinearLayout.field(hintText: String): EditText {
        val e = EditText(context).apply {
            hint = hintText
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        addView(e)
        return e
    }

    private fun LinearLayout.button(t: String, onClick: (Button) -> Unit): Button {
        val b = Button(context).apply {
            text = t
            setTextColor(Color.BLACK)
            setBackgroundColor(amber)
            setOnClickListener { onClick(this) }
        }
        addView(b)
        return b
    }

    private fun LinearLayout.messageView(): TextView {
        val m = TextView(context).apply { setTextColor(red); setPadding(0, 16, 0, 16) }
        addView(m)
        return m
    }

    /** Ambil isi field sebagai CharArray lalu kosongkan Editable. */
    private fun take(e: EditText): CharArray {
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
            btn.isEnabled = true
            msg.text = ""
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
    }

    private fun showUnlock() = screen {
        label("MOLINA VAULT", 26f, amber)
        val bad = untrustedAccessibilityServices()
        if (bad.isNotEmpty()) {
            label("PERINGATAN: accessibility service non-sistem aktif (${bad.joinToString()}). Aplikasi itu bisa membaca layar. Nonaktifkan sebelum membuka vault.", 13f, red)
        }
        val p = field("Master password")
        val msg = messageView()
        val btn = button("Buka") { b ->
            val pw = take(p)
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
    }

    private fun showVault() = screen {
        label("MOLINA VAULT", 26f, amber)
        label("Terbuka. Terkunci otomatis setelah 2 menit tanpa aktivitas atau saat keluar dari aplikasi.", 13f)
        val listing = try { store.list() } catch (e: Exception) { null }
        if (listing == null) {
            label("Gagal membaca daftar entri.", 14f, red)
        } else {
            val extra = if (listing.corrupt > 0) " (${listing.corrupt} rusak/dimanipulasi)" else ""
            label("${listing.items.size} entri$extra", 16f, Color.WHITE)
            listing.items.forEach { label("${it.type.name} · ${it.title}", 15f, Color.WHITE) }
        }
        button("Kunci sekarang") { SessionLock.lock(); render() }
    }
}

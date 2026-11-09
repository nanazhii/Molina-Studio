package com.molina.cinema

import android.content.Context
import android.graphics.Rect
import android.text.TextUtils
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * [TITLE-MARQUEE] TextView satu baris dengan marquee. Marquee bawaan Android hanya jalan pada view yang
 * fokus/terpilih; di panel kontrol view ini tidak pernah fokus, jadi dipicu lewat [restartMarquee].
 * Alur: teks diam dulu [START_DELAY_MS] (ellipsize END, tampil dari awal), lalu bergulir (ellipsize MARQUEE).
 * Android sendiri menambah jeda ~1,2 dtk sebelum gulir pertama, jadi total jeda awal sekitar 2 detik.
 * Teks pendek tidak bergerak dan tetap di tengah.
 */
class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val startRunnable = Runnable {
        ellipsize = TextUtils.TruncateAt.MARQUEE
        isSelected = true
    }

    /** Hentikan gulir, tampilkan teks dari awal, lalu mulai bergulir setelah [delayMs]. */
    fun restartMarquee(delayMs: Long = START_DELAY_MS) {
        removeCallbacks(startRunnable)
        isSelected = false
        ellipsize = TextUtils.TruncateAt.END
        postDelayed(startRunnable, delayMs)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartMarquee() // view dipindah/ditampilkan ulang (viewGroupReorder, rotasi): ulang dari awal
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(startRunnable)
        super.onDetachedFromWindow()
    }

    override fun isFocused(): Boolean = true

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        // abaikan perubahan fokus nyata agar marquee tidak berhenti
        if (focused) super.onFocusChanged(true, direction, previouslyFocusedRect)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        // tetap berjalan walau jendela kehilangan fokus (mis. dialog muncul)
        super.onWindowFocusChanged(true)
    }

    companion object {
        /** Jeda sebelum teks panjang mulai bergulir (ms). Ubah di sini bila perlu. */
        const val START_DELAY_MS = 800L
    }
}

package com.molina.cinema

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/**
 * [SPECTRUM] Visualizer spektrum frekuensi untuk layar "Now Playing" audio: batang membulat bergradasi warna aksen,
 * penanda puncak yang tertahan lalu jatuh, dan pantulan lembut di bawah garis dasar. Membaca [SpectrumTrack] menurut
 * [positionMs]; hanya menggambar ulang saat tampil, terpasang, dan ada gerak (hemat baterai).
 */
internal class SpectrumView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs), Runnable {

    /** Hasil analisis trek yang sedang diputar; null = tidak ada data (batang diam/idle). */
    var track: SpectrumTrack? = null
        set(value) { field = value; smoother.reset(); wake() }

    /** Posisi putar saat ini (ms) dan status memutar; disuplai Activity dari mpv. */
    var positionMs: () -> Long = { 0L }
    var isPlaying: () -> Boolean = { false }

    /** True = redupkan (mis. panel lirik tampil di atasnya); spektrum tetap hidup sebagai latar. */
    var dimWhen: () -> Boolean = { false }

    var accent: Int = Color.parseColor("#2ECC71")
        set(value) { field = value; rebuildShaders(); invalidate() }

    /** Mode warna batang: aksen tunggal, gradasi per frekuensi, atau Cinema (marun -> emas). */
    var colorMode: SpectrumColorMode = SpectrumColorMode.CINEMA
        set(value) { field = value; rebuildShaders(); invalidate() }

    private val bands = SpectrumMath.BANDS
    private val smoother = SpectrumSmoother(bands)
    private val target = FloatArray(bands)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val reflectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 255, 255, 255) }
    private val rect = RectF()
    private val barShaders = arrayOfNulls<Shader>(bands)
    private val reflectShaders = arrayOfNulls<Shader>(bands)
    private val capColors = IntArray(bands)
    private var lastTick = 0L
    private var running = false
    private val d = resources.displayMetrics.density

    private val latencyMs = 70 // keluaran audio Android terdengar sedikit setelah posisi putar mpv

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    private fun baseline() = height * 0.78f

    private fun rebuildShaders() {
        if (height <= 0) return
        val base = baseline()
        for (b in 0 until bands) {
            val bottom = SpectrumColors.bottom(colorMode, b, bands, accent)
            val top = SpectrumColors.top(colorMode, b, bands, accent)
            val r = Color.red(bottom); val g = Color.green(bottom); val bl = Color.blue(bottom)
            barShaders[b] = LinearGradient(0f, base, 0f, 0f, bottom, top, Shader.TileMode.CLAMP)
            reflectShaders[b] = LinearGradient(0f, base, 0f, height.toFloat(), Color.argb(90, r, g, bl), Color.argb(0, r, g, bl), Shader.TileMode.CLAMP)
            val cap = SpectrumColors.cap(colorMode, b, bands, accent)
            capColors[b] = Color.argb(215, Color.red(cap), Color.green(cap), Color.blue(cap))
        }
    }

    // --- siklus animasi: hanya jalan saat terlihat dan terpasang
    override fun onAttachedToWindow() { super.onAttachedToWindow(); wake() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); if (isVisible) wake() else stop() }

    private fun wake() {
        if (running || !isAttachedToWindow || !isShown) return
        running = true
        lastTick = SystemClock.uptimeMillis()
        postOnAnimation(this)
    }

    private fun stop() { running = false; removeCallbacks(this) }

    override fun run() {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastTick).toFloat()
        lastTick = now
        val t = track
        val live = t != null && isPlaying() && t.sample(positionMs() - latencyMs, target)
        smoother.step(if (live) target else null, dt)
        val a = if (dimWhen()) 0.35f else 1f
        if (alpha != a) alpha = a
        invalidate()
        if (live || !smoother.settled()) postOnAnimation(this)
        else postDelayed(this, 150) // diam: cek ringan apakah pemutaran dilanjutkan
    }

    override fun onDraw(canvas: Canvas) {
        if (hideWhen()) return
        val w = width.toFloat()
        val base = baseline()
        val slot = w / bands
        val barW = slot * 0.66f
        val radius = barW / 2f
        val minH = 3f * d
        val maxH = base - 4f * d
        val capH = 3f * d
        for (b in 0 until bands) {
            val x = b * slot + (slot - barW) / 2f
            val h = minH + (maxH - minH) * smoother.level[b]
            rect.set(x, base - h, x + barW, base)
            barPaint.shader = barShaders[b]
            canvas.drawRoundRect(rect, radius, radius, barPaint)
            // pantulan: cermin pendek di bawah garis dasar
            val rh = (h * 0.32f).coerceAtMost(height - base)
            rect.set(x, base + 2f * d, x + barW, base + 2f * d + rh)
            reflectPaint.shader = reflectShaders[b]
            canvas.drawRoundRect(rect, radius, radius, reflectPaint)
            // penanda puncak
            val py = base - (minH + (maxH - minH) * smoother.peak[b]) - capH - 2f * d
            if (smoother.peak[b] > 0.02f) {
                rect.set(x, py, x + barW, py + capH)
                capPaint.color = capColors[b]
                canvas.drawRoundRect(rect, capH / 2f, capH / 2f, capPaint)
            }
        }
    }
}

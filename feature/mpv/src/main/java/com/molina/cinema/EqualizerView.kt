package com.molina.cinema

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.sin

/**
 * [MOLINA-EQ] Tiga bar equalizer kecil penanda channel yang sedang tayang.
 * Animasi hanya berjalan saat view ter-attach dan VISIBLE, dihentikan otomatis saat di-recycle
 * atau dilepas dari window sehingga tidak ada animator yang bocor.
 */
class EqualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.player_playlist_active)
        style = Paint.Style.FILL
    }
    private val rect = RectF()
    private val speeds = intArrayOf(1, 2, 1)          // bilangan bulat => loop mulus
    private val offsets = floatArrayOf(0f, 0.35f, 0.7f)
    private var phase = 0f
    private var animator: ValueAnimator? = null

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(18f).toInt(), wSpec),
            resolveSize(dp(16f).toInt(), hSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val gap = dp(2.5f)
        val barW = (width - gap * 2) / 3f
        val radius = barW / 2f
        for (i in 0 until 3) {
            val wave = 0.5f + 0.5f * sin(2.0 * PI * (phase * speeds[i] + offsets[i])).toFloat()
            val level = 0.28f + 0.72f * wave
            val h = height * level
            val left = i * (barW + gap)
            rect.set(left, height - h, left + barW, height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    private fun updateAnimation() {
        val shouldRun = isAttachedToWindow && visibility == VISIBLE
        if (shouldRun) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1100L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { phase = it.animatedFraction; invalidate() }
                    start()
                }
            }
        } else {
            animator?.cancel()
            animator = null
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation() }
    override fun onDetachedFromWindow() { updateAnimation(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this) updateAnimation()
    }
}

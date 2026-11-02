package com.molina.cinema

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.SeekBar
import kotlin.math.abs

/**
 * SeekBar dengan titik emas penanda posisi (lihat VideoBookmarks). Ketukan singkat (tanpa geser) dalam radius
 * 16dp dari sebuah titik memanggil [onMarkTap]; geseran biasa tetap berfungsi seperti SeekBar normal.
 */
class MarkedSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
) : SeekBar(context, attrs, defStyleAttr) {
    private var marks: List<Double> = emptyList()
    private var unitsPerSec = 1
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE6B450.toInt()
        style = Paint.Style.FILL
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    /** Dipanggil dengan posisi penanda (detik) saat titiknya diketuk. */
    var onMarkTap: ((Double) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var downMark: Double? = null

    /** [secs] = posisi penanda (detik); [unitsPerSec] = skala progress SeekBar per detik. */
    fun setMarks(secs: List<Double>, unitsPerSec: Int) {
        marks = secs
        this.unitsPerSec = if (unitsPerSec > 0) unitsPerSec else 1
        invalidate()
    }

    private fun markX(sec: Double): Float? {
        val total = max
        if (total <= 0) return null
        val span = (width - paddingLeft - paddingRight).toFloat()
        if (span <= 0f) return null
        val frac = (sec * unitsPerSec / total).toFloat()
        if (frac < 0f || frac > 1f) return null
        return paddingLeft + span * frac
    }

    private fun markAt(x: Float): Double? {
        var best: Double? = null
        var bestDist = 16f * density
        for (s in marks) {
            val mx = markX(s) ?: continue
            val d = abs(mx - x)
            if (d <= bestDist) {
                bestDist = d
                best = s
            }
        }
        return best
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downMark = markAt(event.x)
            }
            MotionEvent.ACTION_MOVE ->
                if (downMark != null && abs(event.x - downX) > slop) downMark = null
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val m = downMark
            downMark = null
            if (m != null && abs(event.x - downX) <= slop && abs(event.y - downY) <= slop) onMarkTap?.invoke(m)
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            downMark = null
        }
        return handled
    }

    @Synchronized
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (marks.isEmpty()) return
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val r = 3.5f * density
        for (s in marks) {
            val cx = markX(s) ?: continue
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, edge)
        }
    }
}

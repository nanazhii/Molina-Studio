package com.molina.code.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import androidx.compose.ui.graphics.toArgb
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** [CODE-GUTTER] Hasil diff per baris isi sekarang: [kind] 0 = sama, 1 = baru, 2 = diubah. [deleted] indeks i = ada baris dihapus tepat sebelum baris i (indeks terakhir = setelah baris terakhir). */
internal class ChangeMarks(val kind: ByteArray, val deleted: BooleanArray)

/** Diff berbasis baris: buang awalan/akhiran yang sama, lalu LCS pada sisa (dibatasi 1 juta sel; lebih besar = semua dianggap diubah). */
internal object ChangeDiff {
    const val ADDED: Byte = 1
    const val MODIFIED: Byte = 2
    private const val MAX_CELLS = 1_000_000L

    fun compute(base: String, cur: String): ChangeMarks {
        val a = base.split('\n')
        val b = cur.split('\n')
        val kind = ByteArray(b.size)
        val deleted = BooleanArray(b.size + 1)
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        val aLen = a.size - p - s
        val bLen = b.size - p - s
        if (aLen == 0 && bLen == 0) return ChangeMarks(kind, deleted)
        if (aLen == 0) {
            for (j in 0 until bLen) kind[p + j] = ADDED
            return ChangeMarks(kind, deleted)
        }
        if (bLen == 0) {
            deleted[p] = true
            return ChangeMarks(kind, deleted)
        }
        if (aLen.toLong() * bLen > MAX_CELLS) {
            for (j in 0 until bLen) kind[p + j] = MODIFIED
            return ChangeMarks(kind, deleted)
        }
        val w = bLen + 1
        val dp = IntArray((aLen + 1) * w)
        for (i in aLen - 1 downTo 0) {
            for (j in bLen - 1 downTo 0) {
                dp[i * w + j] = if (a[p + i] == b[p + j]) dp[(i + 1) * w + j + 1] + 1
                else maxOf(dp[(i + 1) * w + j], dp[i * w + j + 1])
            }
        }
        var del = 0
        val adds = ArrayList<Int>()
        fun flush(next: Int) {
            if (del == 0 && adds.isEmpty()) return
            val mod = minOf(del, adds.size)
            for (k in adds.indices) kind[adds[k]] = if (k < mod) MODIFIED else ADDED
            if (del > adds.size) deleted[next] = true
            del = 0
            adds.clear()
        }
        var i = 0
        var j = 0
        while (i < aLen && j < bLen) {
            if (a[p + i] == b[p + j]) {
                flush(p + j)
                i++
                j++
            } else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) {
                del++
                i++
            } else {
                adds.add(p + j)
                j++
            }
        }
        del += aLen - i
        while (j < bLen) {
            adds.add(p + j)
            j++
        }
        flush(p + bLen)
        return ChangeMarks(kind, deleted)
    }
}

/** Editor Molina: sama dengan CodeEditor, tetapi renderer menggambar penanda perubahan di tepi kiri gutter. */
internal class MolinaCodeEditor(context: Context) : CodeEditor(context) {
    @Volatile
    internal var marks: ChangeMarks? = null

    override fun onCreateRenderer(): EditorRenderer = GutterRenderer(this)

    /** [CODE-PLAYGUTTER] True = baris 1 menampilkan tombol Play di gutter; [playRunning] = skrip sedang berjalan (ikon kotak). */
    @Volatile
    internal var playEnabled: Boolean = false
    @Volatile
    internal var playRunning: Boolean = false
    internal var onPlay: (() -> Unit)? = null
    private var playGesture = false
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    /** Tepi kanan gutter di layar (sama dengan awal area teks). */
    private fun gutterRight(): Float =
        measureTextRegionOffset() - (if (isWordwrap || !isLineNumberPinned) offsetX else 0)

    private fun playHit(x: Float, y: Float): Boolean {
        if (!playEnabled || !isLineNumberEnabled) return false
        if (x > gutterRight()) return false
        val top = (getRowTop(0) - offsetY).toFloat()
        val bottom = (getRowBottom(0) - offsetY).toFloat()
        return y >= top && y <= bottom
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (playEnabled && onPlay != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (playHit(event.x, event.y)) {
                    playGesture = true
                    downX = event.x
                    downY = event.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> if (playGesture) return true
                MotionEvent.ACTION_UP -> if (playGesture) {
                    playGesture = false
                    if (abs(event.x - downX) <= slop && abs(event.y - downY) <= slop) onPlay?.invoke()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> if (playGesture) {
                    playGesture = false
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}

private class GutterRenderer(private val ed: MolinaCodeEditor) : EditorRenderer(ed) {
    private val paint = Paint()
    private val colAdded = CodeColors.Clean.toArgb()
    private val colModified = 0xFF4A9EE0.toInt()
    private val colDeleted = CodeColors.Danger.toArgb()
    private val colPlay = CodeColors.Clean.toArgb()
    private val colStop = CodeColors.Danger.toArgb()
    private val playPath = Path()

    override fun drawLineNumber(canvas: Canvas, line: Int, row: Int, offsetX: Float, width: Float, color: Int) {
        val play = line == 0 && ed.playEnabled
        if (!play) super.drawLineNumber(canvas, line, row, offsetX, width, color)
        if (width + offsetX <= 0f) return
        val top = (ed.getRowTop(row) - ed.offsetY).toFloat()
        val bottom = (ed.getRowBottom(row) - ed.offsetY).toFloat()
        val dp = ed.dpUnit
        val left = maxOf(0f, offsetX)
        if (play) {
            // [CODE-PLAYGUTTER] menggantikan nomor baris 1: segitiga Play, atau kotak Stop saat berjalan
            val cx = offsetX + width / 2f
            val cy = (top + bottom) / 2f
            val sz = minOf((bottom - top) * 0.5f, width * 0.8f)
            if (ed.playRunning) {
                paint.color = colStop
                canvas.drawRect(cx - sz * 0.4f, cy - sz * 0.4f, cx + sz * 0.4f, cy + sz * 0.4f, paint)
            } else {
                paint.color = colPlay
                playPath.reset()
                playPath.moveTo(cx - sz * 0.35f, cy - sz * 0.5f)
                playPath.lineTo(cx - sz * 0.35f, cy + sz * 0.5f)
                playPath.lineTo(cx + sz * 0.55f, cy)
                playPath.close()
                canvas.drawPath(playPath, paint)
            }
        }
        val m = ed.marks ?: return
        if (line < 0 || line >= m.kind.size) return
        val k = m.kind[line]
        if (k != 0.toByte()) {
            paint.color = if (k == ChangeDiff.ADDED) colAdded else colModified
            canvas.drawRect(left, top, left + 3f * dp, bottom, paint)
        }
        paint.color = colDeleted
        if (m.deleted[line]) canvas.drawRect(left, top, left + 8f * dp, top + 2f * dp, paint)
        if (line == m.kind.size - 1 && m.deleted[m.kind.size]) canvas.drawRect(left, bottom - 2f * dp, left + 8f * dp, bottom, paint)
    }
}

/** Menjadwalkan hitung ulang penanda (debounce 400 ms, diff di thread latar). Dipanggil dari thread utama. */
internal object ChangeGutter {
    private const val DELAY_MS = 400L
    private const val MAX_CHARS = 400_000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun schedule(tab: EditorTab) {
        val ed = tab.editor as? MolinaCodeEditor ?: return
        tab.gutterTask?.let { ed.removeCallbacks(it) }
        tab.gutterJob?.cancel()
        val task = Runnable { start(tab, ed) }
        tab.gutterTask = task
        ed.postDelayed(task, DELAY_MS)
    }

    private fun start(tab: EditorTab, ed: MolinaCodeEditor) {
        if (tab.editor !== ed) return
        val cur: String? = if (tab.loading || ed.text.length > MAX_CHARS) null else ed.text.toString()
        tab.gutterJob = scope.launch {
            val marks = try { compute(tab, cur) } catch (e: Exception) { null }
            if (!isActive) return@launch
            ed.post {
                if (tab.editor === ed) {
                    ed.marks = marks
                    ed.invalidate()
                }
            }
        }
    }

    private fun compute(tab: EditorTab, cur: String?): ChangeMarks? {
        if (cur == null) return null
        var base = tab.baseText
        if (base == null) {
            // draf dipulihkan: teks tersimpan dibaca ulang dari disk, hanya dipakai bila sidik jarinya cocok
            val f = tab.file ?: return null
            val t = FileStore.readForOpen(f).text ?: return null
            if (t.length > MAX_CHARS || FileStore.fingerprint(t) != tab.baseFp) return null
            tab.baseText = t
            base = t
        }
        if (base.length > MAX_CHARS) return null
        return ChangeDiff.compute(base, cur)
    }
}

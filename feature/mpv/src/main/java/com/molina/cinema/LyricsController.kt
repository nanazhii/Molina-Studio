package com.molina.cinema

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Daftar lirik. Saat lirik tersinkron, sentuhan TIDAK dikonsumsi supaya gestur player (volume, seek, tampilkan
 * kontrol) tetap sampai ke activity seperti pada kartu sampul. Lirik tanpa timestamp dibuat bisa digulir.
 */
internal class LyricsRecycler @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {
    var interactive = false
    override fun onTouchEvent(e: MotionEvent): Boolean = interactive && super.onTouchEvent(e)
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = interactive && super.onInterceptTouchEvent(e)
}

private class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.VH>() {
    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    var items: List<String> = emptyList()
    var synced = true
    private var lines: List<LyricLine> = emptyList()
    private var active = -1
    private var fill = KaraokeFill(-1, 0)

    fun reset(newItems: List<String>, isSynced: Boolean, newLines: List<LyricLine> = emptyList()) {
        items = newItems; synced = isSynced; lines = newLines; active = -1; fill = KaraokeFill(-1, 0)
        notifyDataSetChanged()
    }

    fun setActive(index: Int) {
        val old = active
        active = index
        fill = KaraokeFill(-1, 0)
        if (old in items.indices) notifyItemChanged(old)
        if (index in items.indices) notifyItemChanged(index)
    }

    /** Isian karaoke pada baris aktif. Hanya menggambar ulang satu baris, dan hanya bila (kata, huruf) berubah. */
    fun setFill(f: KaraokeFill) {
        if (f == fill) return
        fill = f
        if (active in items.indices) notifyItemChanged(active)
    }

    private fun karaoke(segments: List<LyricSegment>): CharSequence = SpannableStringBuilder().apply {
        for (seg in segments) {
            val start = length
            append(seg.text)
            val color = if (seg.sung) LyricsController.COLOR_ACTIVE else LyricsController.COLOR_PENDING
            setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val d = parent.resources.displayMetrics.density
        val tv = TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), (8 * d).toInt())
        }
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val isActive = synced && position == active
        val line = lines.getOrNull(position)
        if (isActive && line != null && line.words.isNotEmpty()) {
            // LRC enhanced: kata yang sudah dinyanyikan berwarna aksen, sisanya warna teks utama
            holder.tv.text = karaoke(line.fillSegments(fill))
            holder.tv.setTextColor(LyricsController.COLOR_PENDING)
        } else {
            holder.tv.text = items[position].ifEmpty { "♪" } // baris kosong = jeda instrumental
            holder.tv.setTextColor(if (isActive) LyricsController.COLOR_ACTIVE else LyricsController.COLOR_DIM)
        }
        holder.tv.textSize = if (isActive) 24f else 18f
        holder.tv.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
        holder.tv.alpha = if (isActive || !synced) 1f else 0.8f
    }

    override fun getItemCount() = items.size
}

/**
 * [LYRICS] Menampilkan lirik .lrc lokal di mode audio, menggantikan kartu sampul. Posisi dibaca dengan
 * polling time-pos/full (Double): properti time-pos yang diobservasi pemutar hanya per detik, kurang untuk LRC.
 */
internal class LyricsController(
    private val scope: CoroutineScope,
    private val panel: View,
    private val list: LyricsRecycler,
    private val cover: View,
    private val controls: View,
    /** True bila lirik layak digambar/dipoll: activity di depan dan UI audio aktif. */
    private val isVisible: () -> Boolean,
) {
    private val adapter = LyricsAdapter()
    private var job: Job? = null
    private var path: String? = null
    private var doc: LrcDocument? = null
    private var tracker: LyricsTracker? = null
    private var lastIndex = -1
    private var centerY = 0
    private var lastHeight = -1

    var offsetMs = 0L
        private set
    private var shown = true

    // Offset per lagu (kunci = path), dibatasi 200 entri; lihat LyricsOffsets.
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(list.context.applicationContext) }
    private val offsets by lazy { LyricsOffsets.parse(prefs.getString(PREF_OFFSETS, null)) }

    /** Ada lirik untuk lagu ini (dipakai menu untuk menampilkan/menyembunyikan tombol). */
    val available: Boolean get() = doc != null

    init {
        list.layoutManager = LinearLayoutManager(list.context)
        list.adapter = adapter
        list.itemAnimator = null
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> relayout() }
    }

    /** Panggil dengan path lagu lokal, atau null bila bukan mode audio/bukan berkas lokal. Aman dipanggil berulang. */
    fun onTrack(newPath: String?) {
        if (newPath == path) return
        path = newPath
        clear()
        if (newPath == null) return
        job = scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    LyricsFiles.find(newPath)?.let { LyricsFiles.readText(it) }?.let { LrcParser.parse(it) }
                }.getOrNull()
            }
            if (path != newPath || parsed == null) return@launch
            if (!parsed.synced && parsed.plainLines.isEmpty()) return@launch
            display(parsed)
            poll()
        }
    }

    private fun clear() {
        job?.cancel(); job = null
        doc = null; tracker = null; offsetMs = 0L; lastIndex = -1
        list.interactive = false
        adapter.reset(emptyList(), true)
        applyVisibility()
    }

    private fun display(d: LrcDocument) {
        doc = d
        lastIndex = -1
        offsetMs = path?.let { offsets.get(it) } ?: 0L
        if (d.synced) {
            adapter.reset(d.lines.map { it.text }, true, d.lines)
            tracker = LyricsTracker(d.lines)
            list.interactive = false
        } else {
            val note = list.context.getString(R.string.lyrics_unsynced)
            adapter.reset(listOf(note, "") + d.plainLines, false)
            tracker = null
            list.interactive = true
        }
        applyVisibility()
        list.post { relayout() }
    }

    private fun applyVisibility() {
        val on = shown && doc != null
        panel.visibility = if (on) View.VISIBLE else View.GONE
        cover.visibility = if (on) View.INVISIBLE else View.VISIBLE
    }

    fun toggle(): Boolean {
        shown = !shown
        applyVisibility()
        return shown
    }

    fun setOffsetMs(ms: Long) {
        offsetMs = ms
        tracker?.reset() // paksa emisi berikutnya
        path?.let {
            offsets.put(it, ms)
            prefs.edit().putString(PREF_OFFSETS, offsets.serialize()).apply()
        }
    }

    fun showOffsetDialog(activity: Activity, restore: StateRestoreCallback) {
        SubStyleUi.showSlider(
            activity, R.string.lyrics_offset_title,
            SliderPickerDialog(-5.0, 5.0, 10, R.string.format_one_decimal),
            offsetMs / 1000.0, restore,
        ) { v -> setOffsetMs((v * 1000).roundToLong()) }
    }

    private suspend fun poll() {
        val tr = tracker ?: return
        val hasWords = doc?.lines?.any { it.words.isNotEmpty() } == true
        while (true) {
            val visible = isVisible()
            // list.height > 0 && centerY > 0: tunggu tata letak pertama supaya lirik langsung berpusat dengan benar
            if (shown && visible && list.height > 0 && centerY > 0) {
                val sec = MPVLib.getPropertyDouble("time-pos/full")
                if (sec != null) {
                    val posMs = (sec * 1000).toLong()
                    tr.update(posMs, offsetMs)?.let { onIndex(it) }
                    if (hasWords) updateWord(tr.current, posMs + offsetMs)
                }
            }
            delay(when {
                !visible -> 1_000L // di latar belakang: hemat daya
                MPVLib.getPropertyBoolean("pause") == true -> 400L
                hasWords -> 70L // karaoke: isian huruf demi huruf, perlu lebih rapat dari pergantian baris
                else -> 150L
            })
        }
    }

    private fun updateWord(lineIndex: Int, positionMs: Long) {
        val lines = doc?.lines ?: return
        val line = lines.getOrNull(lineIndex) ?: return
        if (line.words.isNotEmpty()) adapter.setFill(karaokeFill(line, lines.getOrNull(lineIndex + 1)?.startMs, positionMs))
    }

    private fun onIndex(index: Int) {
        adapter.setActive(index)
        if (index >= 0) scrollTo(index)
        lastIndex = index
    }

    /** Titik tengah daerah lirik = di atas bilah kontrol; padding memberi ruang agar baris pertama/terakhir bisa ke tengah. */
    private fun relayout() {
        val h = list.height
        if (h <= 0 || h == lastHeight) return
        lastHeight = h
        val d = list.resources.displayMetrics.density
        val top = (48 * d).toInt()
        val bottomReserve = if (controls.height > 0) controls.height + (60 * d).toInt() else (180 * d).toInt()
        centerY = (top + (h - top - bottomReserve).coerceAtLeast(0) / 2).coerceIn(top, h)
        list.clipToPadding = false
        list.setPadding(0, centerY, 0, (h - centerY).coerceAtLeast(0))
    }

    private fun scrollTo(index: Int) {
        val lm = list.layoutManager as? LinearLayoutManager ?: return
        if (lastIndex < 0 || abs(index - lastIndex) > 6) {
            // lompatan jauh (seek/awal): pindah langsung, lalu gulir halus untuk baris berikutnya
            lm.scrollToPositionWithOffset(index, (centerY - 24 * list.resources.displayMetrics.density).toInt())
            return
        }
        val scroller = object : LinearSmoothScroller(list.context) {
            override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int =
                centerY - (viewStart + viewEnd) / 2
            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float = 60f / displayMetrics.densityDpi
        }
        scroller.targetPosition = index
        lm.startSmoothScroll(scroller)
    }

    companion object {
        private const val PREF_OFFSETS = "lyrics_offsets"
        // Sesuai docs/branding/panduan-warna.md: Cinema = #B388FF (kontras 7,4:1), Muted = #9AA0A6.
        const val COLOR_ACTIVE = 0xFFB388FF.toInt()
        const val COLOR_DIM = 0xFF9AA0A6.toInt()
        const val COLOR_PENDING = 0xFFEDEDED.toInt() // OnBackground: kata yang belum dinyanyikan pada baris aktif
    }
}

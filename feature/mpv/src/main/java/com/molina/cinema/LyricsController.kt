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
import kotlinx.coroutines.isActive
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
        // Glow lembut aksen Cinema pada baris aktif; radius 8dp muat di padding vertikal item (tidak terpotong)
        val dens = holder.tv.resources.displayMetrics.density
        if (isActive) holder.tv.setShadowLayer(8f * dens, 0f, 0f, LyricsController.COLOR_GLOW)
        else holder.tv.setShadowLayer(0f, 0f, 0f, 0)
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
    private var autoPlain: List<String>? = null // [LYRICS-AUTO] lirik polos asal bila sedang ditampilkan sebagai estimasi
    private var autoDurMs = 0L
    private var alignJob: Job? = null  // analisis audio milik lirik sendiri (bila spektrum mati)
    private var refineJob: Job? = null // penyelarasan (DP) di thread Default
    private var readyTrack: SpectrumTrack? = null

    /** Diset Activity: true bila analisis spektrum lagu ini sedang berjalan (hasilnya dipakai bersama, tanpa decode ganda). */
    var spectrumRunning: () -> Boolean = { false }

    /** [SPECTRUM] Ruang bawah tambahan (px) untuk visualizer; diset Activity. */
    var extraBottomReserve: () -> Int = { 0 }

    /** [SPECTRUM] Tinggi (px) dari tepi bawah yang sudah dipotong dari daftar lirik (mis. zona spektrum); dikurangkan dari reserve bawah. */
    var clipBottom: () -> Int = { 0 }
    fun refreshLayout() { lastHeight = -1; list.post { relayout() } }

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
        MpvLog.d(TAG, "[LYRICS-DIAG] onTrack path=$newPath")
        if (newPath == null) return
        job = scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val f = LyricsFiles.find(newPath)
                    val dir = java.io.File(newPath).parentFile
                    MpvLog.d(TAG, "[LYRICS-DIAG] find=$f dir=${dir?.path} canList=${dir?.listFiles() != null}")
                    val text = f?.let { LyricsFiles.readText(it) } ?: readEmbedded(newPath)
                    text?.let { LrcParser.parse(it) }
                }.onFailure { MpvLog.e(TAG, "[LYRICS-DIAG] gagal baca/parse", it) }.getOrNull()
            }
            MpvLog.d(TAG, "[LYRICS-DIAG] parsed synced=${parsed?.synced} lines=${parsed?.lines?.size} plain=${parsed?.plainLines?.size}")
            if (path != newPath || parsed == null) return@launch
            if (!parsed.synced && parsed.plainLines.isEmpty()) return@launch
            val shownDoc = autoSynced(parsed) // [LYRICS-AUTO]
            if (path != newPath) return@launch
            display(shownDoc)
            startRefine() // [LYRICS-AUTO]
            poll()
        }
    }

    /**
     * [LYRICS] Cadangan: lirik di tag audio lewat properti metadata mpv. Metadata bisa terlambat terisi
     * setelah file dimuat, jadi dicoba hingga 3 kali (jeda 300 ms); berhenti bila lagu sudah berganti.
     */
    private suspend fun readEmbedded(forPath: String): String? {
        repeat(3) { attempt ->
            if (path != forPath) return null
            val n = MPVLib.getPropertyInt("metadata/list/count") ?: 0
            val entries = ArrayList<Pair<String, String>>()
            for (i in 0 until n.coerceAtMost(200)) {
                val k = MPVLib.getPropertyString("metadata/list/$i/key") ?: continue
                val v = MPVLib.getPropertyString("metadata/list/$i/value") ?: continue
                entries.add(k to v)
            }
            val hit = LyricsEmbedded.pick(entries)
            MpvLog.d(TAG, "[LYRICS-DIAG] embedded percobaan=${attempt + 1} tag=$n hasil=${hit?.length}")
            if (hit != null) return hit
            delay(300L)
        }
        return null
    }

    /** [LYRICS-AUTO] Dipanggil Activity saat analisis spektrum [forPath] selesai (energi vokal dipakai menyelaraskan lirik). */
    fun onSpectrumComplete(forPath: String, track: SpectrumTrack) {
        if (forPath != path) return
        readyTrack = track
        if (autoPlain != null) refine(track)
    }

    /** [LYRICS-AUTO] Setelah estimasi tampil: pakai data spektrum bila ada; bila spektrum mati, decode sendiri. */
    private fun startRefine() {
        val p = path ?: return
        val plain = autoPlain ?: return
        readyTrack?.let { refine(it); return }
        if (spectrumRunning()) return // menunggu analisis spektrum yang sama
        alignJob?.cancel()
        alignJob = scope.launch {
            var produced: SpectrumTrack? = null
            val ok = withContext(Dispatchers.Default) {
                SpectrumDecoder.analyze(p, { isActive && path == p }) { t -> produced = t }
            }
            val t = produced
            if (ok && t != null && path == p && autoPlain === plain) refine(t)
        }
    }

    private fun refine(track: SpectrumTrack) {
        val p = path ?: return
        val plain = autoPlain ?: return
        val dur = autoDurMs
        if (dur <= 0L) return
        refineJob?.cancel()
        refineJob = scope.launch {
            val lines = withContext(Dispatchers.Default) { LyricsAutoSync.alignFromTrack(plain, track, dur) }
            if (lines == null || path != p || autoPlain !== plain || tap != null) return@launch
            val d = doc ?: return@launch
            doc = d.copy(lines = lines)
            lastIndex = -1
            adapter.reset(lines.map { it.text }, true, lines)
            tracker = LyricsTracker(lines)
            job?.cancel()
            job = scope.launch { poll() } // poll() menangkap tracker saat mulai: jalankan ulang dengan tracker baru
            MpvLog.d(TAG, "[LYRICS-AUTO] diselaraskan dari spektrum: ${lines.size} baris")
        }
    }

    /** [LYRICS-AUTO] Lirik polos -> perkiraan tersinkron otomatis (butuh durasi dari mpv, maks ~3 dtk menunggu). */
    private suspend fun autoSynced(d: LrcDocument): LrcDocument {
        autoPlain = null
        if (d.synced || d.plainLines.isEmpty()) return d
        var durSec = 0.0
        var tries = 0
        while (durSec <= 0.0 && tries < 15) {
            durSec = MPVLib.getPropertyDouble("duration") ?: 0.0
            if (durSec <= 0.0) delay(200L)
            tries++
        }
        val lines = LyricsAutoSync.estimate(d.plainLines, (durSec * 1000).toLong())
        if (lines.isEmpty()) return d
        autoPlain = d.plainLines
        autoDurMs = (durSec * 1000).toLong()
        return d.copy(lines = lines, plainLines = emptyList())
    }

    private fun clear() {
        job?.cancel(); job = null
        if (tap != null) { tap = null; onTapEnded?.invoke() } // [LYRICS-TAP] reset di clear
        doc = null; tracker = null; offsetMs = 0L; lastIndex = -1
        autoPlain = null // [LYRICS-AUTO]
        alignJob?.cancel(); alignJob = null; refineJob?.cancel(); refineJob = null; readyTrack = null
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
        MpvLog.d(TAG, "[LYRICS-DIAG] applyVisibility on=$on shown=$shown doc=${doc != null}")
        panel.visibility = if (on) View.VISIBLE else View.GONE
        cover.visibility = if (on) View.INVISIBLE else View.VISIBLE
    }

    // ---- [LYRICS-TAP] editor cap waktu: lirik polos + ketukan -> .lrc tersinkron (logika di LyricsTapSync.kt) ----
    private var tap: TapSession? = null

    /** Dipanggil saat sesi tap berakhir tanpa lewat tombol (ganti lagu) atau setelah simpan berhasil. */
    var onTapEnded: (() -> Unit)? = null

    data class TapStatus(val stamped: Int, val total: Int, val complete: Boolean)

    val tapActive: Boolean get() = tap != null

    /** Lirik polos siap dicap: bukan tersinkron, ada path lagu lokal. */
    val canTapSync: Boolean
        get() = path != null && (autoPlain != null || doc?.let { !it.synced && it.plainLines.isNotEmpty() } == true)

    val tapStatus: TapStatus? get() = tap?.let { TapStatus(it.stampedCount, it.size, it.isComplete) }

    fun startTap(): Boolean {
        val d = doc ?: return false
        if (!canTapSync) return false
        val s = TapSession(autoPlain ?: d.plainLines)
        if (s.size == 0) return false
        tap = s
        lastIndex = -1
        shown = true
        list.interactive = false
        applyVisibility()
        refreshTap()
        // mulai dari awal lagu dan putar; ketukan dihitung dari time-pos saat itu
        MPVLib.command(arrayOf("seek", "0", "absolute"))
        MPVLib.setPropertyBoolean("pause", false)
        return true
    }

    private fun refreshTap() {
        val s = tap ?: return
        adapter.reset(List(s.size) { s.textOf(it) }, true)
        if (s.isComplete) adapter.setActive(-1) else onIndex(s.cursor)
    }

    private fun nowMs(): Long? = MPVLib.getPropertyDouble("time-pos/full")?.let { (it * 1000).toLong() }

    /** Cap baris berikutnya pada posisi putar sekarang. True bila ketukan tercatat. */
    fun tapNow(): Boolean {
        val s = tap ?: return false
        val pos = nowMs() ?: return false
        if (s.tap(pos) !is TapResult.Ok) return false
        refreshTap()
        return true
    }

    fun tapBreakNow(): Boolean {
        val s = tap ?: return false
        val pos = nowMs() ?: return false
        if (s.tapBreak(pos) !is TapResult.Ok) return false
        refreshTap()
        return true
    }

    /** Urungkan ketukan terakhir, lalu putar ulang dari awal baris sebelumnya supaya bisa diketuk lagi. */
    fun undoTap(): Boolean {
        val s = tap ?: return false
        if (!s.undo()) return false
        val back = if (s.cursor > 0) s.stampOf(s.cursor - 1) ?: 0L else 0L
        MPVLib.command(arrayOf("seek", (back / 1000.0).toString(), "absolute"))
        refreshTap()
        return true
    }

    fun cancelTap() {
        if (tap == null) return
        tap = null
        doc?.let { display(it) }
        onTapEnded?.invoke()
    }

    /** Simpan hasil ketukan (hanya bila lengkap). Hasil dilaporkan lewat [onResult] di thread utama. */
    fun saveTap(onResult: (LyricsSaver.Result) -> Unit): Boolean {
        val s = tap ?: return false
        val p = path ?: return false
        val lrc = s.toLrc() ?: return false
        scope.launch {
            val r = withContext(Dispatchers.IO) { LyricsSaver.save(p, lrc) }
            if (r is LyricsSaver.Result.Saved && path == p && tap === s) {
                tap = null
                reload()
                onTapEnded?.invoke()
            }
            onResult(r)
        }
        return true
    }

    /** Muat ulang lirik lagu yang sedang aktif (setelah berkas .lrc ditulis). */
    private fun reload() {
        val p = path
        path = null
        onTrack(p)
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
        val controlsBase = when {
            controls.visibility != View.VISIBLE -> (24 * d).toInt() // [SPECTRUM] kontrol tersembunyi (landscape): lirik pakai hampir seluruh layar
            controls.height > 0 -> controls.height + (60 * d).toInt()
            else -> (180 * d).toInt()
        }
        val bottomReserve = (controlsBase - clipBottom()).coerceAtLeast(0) + extraBottomReserve()
        centerY = (top + (h - top - bottomReserve).coerceAtLeast(0) / 2).coerceIn(minOf(top, h), h) // [FIX] h < top (daftar sangat pendek, mis. landscape) melempar IllegalArgumentException
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
        private const val TAG = "LyricsController"
        // Sesuai docs/branding/panduan-warna.md: Cinema = #B388FF (kontras 7,4:1), Muted = #9AA0A6.
        const val COLOR_ACTIVE = 0xFFB388FF.toInt()
        const val COLOR_DIM = 0xFF9AA0A6.toInt()
        val COLOR_GLOW = 0x99B388FF.toInt() // aksen Cinema ~60% alpha untuk glow baris aktif
        const val COLOR_PENDING = 0xFFEDEDED.toInt() // OnBackground: kata yang belum dinyanyikan pada baris aktif
    }
}

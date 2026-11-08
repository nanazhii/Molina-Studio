package com.molina.cinema

import com.molina.cinema.databinding.DialogPlaylistBinding
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Baris item hasil pencarian yang belum di-resolve (belum ada di antrean mpv). */
internal data class PendingRow(val planIndex: Int, val title: String)

/** Satu baris "channel": nomor tetap (tidak berubah saat difilter). */
internal class PlaylistRow(
    val number: Int,
    val text: String,
    val selected: Boolean,
    val item: MPVView.PlaylistItem?,
    val planIndex: Int,
)

internal class PlaylistDialog(
    private val player: MPVView,
    /** URL stream -> judul; menggantikan nama berkas mentah. */
    private val titles: () -> Map<String, String> = { emptyMap() },
    private val pending: () -> List<PendingRow> = { emptyList() },
) {
    private lateinit var binding: DialogPlaylistBinding

    private var playlist = listOf<MPVView.PlaylistItem>()
    private var pendingRows = listOf<PendingRow>()
    private var selectedIndex = -1
    private var allRows = listOf<PlaylistRow>()
    private var visibleRows = listOf<PlaylistRow>()
    private var query = ""

    interface Listeners {
        fun openUrl()
        fun onItemPicked(item: MPVView.PlaylistItem)
        fun onPendingPicked(planIndex: Int) {}
        /** Tombol Exit pada action bar. */
        fun onExit() {}
        /** Tombol Menu pada action bar (buka menu atas). */
        fun onMenu() {}
    }

    var listeners: Listeners? = null

    fun buildView(layoutInflater: LayoutInflater): View {
        binding = DialogPlaylistBinding.inflate(layoutInflater)

        binding.list.adapter = CustomAdapter(this)
        binding.list.setHasFixedSize(true)
        refresh()

        binding.urlBtn.setOnClickListener { listeners?.openUrl() }
        binding.exitBtn.setOnClickListener { listeners?.onExit() }
        binding.menuBtn.setOnClickListener { listeners?.onMenu() }
        binding.infoBtn.setOnClickListener { showInfo() }
        binding.searchBtn.setOnClickListener { toggleSearch() }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                applyFilter()
            }
        })
        // Enter/Done: angka = putar channel itu; teks = putar bila hasil tepat satu
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val q = query.trim()
                val target = if (q.isNotEmpty() && q.all { it.isDigit() })
                    visibleRows.firstOrNull { it.number == q.toIntOrNull() }
                else visibleRows.singleOrNull()
                if (target != null) pick(target)
                true
            } else false
        }

        binding.shuffleBtn.setOnClickListener {
            player.changeShuffle(true)
            refresh()
        }
        binding.repeatBtn.setOnClickListener {
            player.cycleRepeat()
            refresh()
        }

        Utils.handleInsetsAsPadding(binding.root)
        return binding.root
    }

    fun refresh() {
        selectedIndex = MPVLib.getPropertyInt("playlist-pos") ?: -1
        playlist = player.loadPlaylist()
        pendingRows = pending()
        MpvLog.v(TAG, "PlaylistDialog: loaded ${playlist.size} items")

        val titleMap = titles()
        val rows = ArrayList<PlaylistRow>(playlist.size + pendingRows.size)
        playlist.forEachIndexed { i, item ->
            val text = titleMap[item.filename] ?: item.title ?: Utils.fileBasename(item.filename)
            rows.add(PlaylistRow(i + 1, text, item.index == selectedIndex, item, -1))
        }
        pendingRows.forEachIndexed { i, p ->
            rows.add(PlaylistRow(playlist.size + i + 1, p.title, false, null, p.planIndex))
        }
        allRows = rows
        applyFilter(scrollToPlaying = true)

        /*
         * At least on api 33 there is in some cases a (reproducible) bug, where the space below the
         * recycler view for the buttons is not taken into account and they go out-of-bounds of the
         * alert dialog. This fixes it.
         */
        binding.list.post {
            binding.list.parent.requestLayout()
        }

        val ctx = binding.root.context
        val accent = ContextCompat.getColor(ctx, R.color.player_playlist_active)
        val normal = ContextCompat.getColor(ctx, R.color.player_dialog_text)
        val disabled = ContextCompat.getColor(ctx, R.color.alpha_disabled)

        val playingPos = rows.indexOfFirst { it.selected } + 1
        binding.countText.text = String.format("%03d/%03d", playingPos, rows.size)

        binding.shuffleBtn.apply {
            isEnabled = playlist.size > 1
            val c = if (!isEnabled) disabled else if (player.getShuffle()) accent else normal
            setTextColor(c)
            compoundDrawableTintList = ColorStateList.valueOf(c)
        }
        val repeatState = player.getRepeat()
        binding.repeatBtn.apply {
            val c = if (repeatState > 0) accent else normal
            setTextColor(c)
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, if (repeatState == 2) R.drawable.ic_repeat_one_24dp else R.drawable.ic_repeat_24dp, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(c)
        }
        updateSearchTint()
    }

    private fun updateSearchTint() {
        val ctx = binding.root.context
        val c = ContextCompat.getColor(ctx,
            if (binding.searchInput.isVisible) R.color.player_playlist_active else R.color.player_dialog_text)
        binding.searchBtn.setTextColor(c)
        binding.searchBtn.compoundDrawableTintList = ColorStateList.valueOf(c)
    }

    private fun toggleSearch() {
        val show = !binding.searchInput.isVisible
        val imm = binding.root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) {
            binding.searchInput.isVisible = true
            binding.searchInput.requestFocus()
            binding.searchInput.post { imm.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT) }
        } else {
            binding.searchInput.setText("")
            binding.searchInput.isVisible = false
            imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        }
        updateSearchTint()
    }

    /**
     * Teks biasa = saring berdasarkan nama. Hanya angka = lompat ke nomor channel tersebut
     * (daftar tidak disaring, supaya nomor tetap terbaca seperti di TV).
     */
    private fun applyFilter(scrollToPlaying: Boolean = false) {
        val q = query.trim()
        val numeric = q.isNotEmpty() && q.all { it.isDigit() }
        visibleRows = if (q.isEmpty() || numeric) allRows
            else allRows.filter { it.text.contains(q, ignoreCase = true) }
        binding.list.adapter!!.notifyDataSetChanged()

        val lm = binding.list.layoutManager as LinearLayoutManager
        when {
            numeric -> {
                val idx = visibleRows.indexOfFirst { it.number == q.toIntOrNull() }
                if (idx >= 0) lm.scrollToPositionWithOffset(idx, 0)
            }
            scrollToPlaying && q.isEmpty() -> {
                val idx = visibleRows.indexOfFirst { it.selected }
                if (idx >= 0) binding.list.scrollToPosition(idx)
            }
        }
    }

    private fun showInfo() {
        val ctx = binding.root.context
        val pos = allRows.indexOfFirst { it.selected } + 1
        val on = "ON"
        val off = "OFF"
        val rep = when (player.getRepeat()) { 2 -> "1"; 1 -> on; else -> off }
        Toast.makeText(ctx, ctx.getString(R.string.tv_info_format, pos.coerceAtLeast(1), allRows.size,
            if (player.getShuffle()) on else off, rep), Toast.LENGTH_LONG).show()
    }

    private fun pick(row: PlaylistRow) {
        val item = row.item
        if (item != null) listeners?.onItemPicked(item)
        else listeners?.onPendingPicked(row.planIndex)
    }

    private fun clickItem(position: Int) {
        if (position < 0) return
        visibleRows.getOrNull(position)?.let { pick(it) }
    }

    class CustomAdapter(private val parent: PlaylistDialog) :
        RecyclerView.Adapter<CustomAdapter.ViewHolder>() {

        class ViewHolder(private val parent: PlaylistDialog, view: View) :
            RecyclerView.ViewHolder(view) {
            private val textView: TextView = view.findViewById(android.R.id.text1)
            private val numberView: TextView = view.findViewById(R.id.channelNumber)

            init {
                view.setOnClickListener {
                    parent.clickItem(bindingAdapterPosition)
                }
            }

            fun bind(row: PlaylistRow) {
                val ctx = textView.context
                val dim = row.item == null
                numberView.text = String.format("%03d", row.number)
                textView.text = row.text
                itemView.alpha = if (dim) 0.5f else 1f
                // Baris terpilih: kotak biru ala menu TV; lainnya: baris gelap
                itemView.setBackgroundResource(
                    if (row.selected) R.drawable.bg_channel_selected else R.drawable.bg_channel_row)
                textView.setTypeface(null, if (row.selected) Typeface.BOLD else Typeface.NORMAL)
                val textColor = ContextCompat.getColor(ctx, R.color.player_dialog_text)
                textView.setTextColor(textColor)
                numberView.setTextColor(if (row.selected) textColor else 0xFFC8E600.toInt())
            }
        }

        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(viewGroup.context)
                .inflate(R.layout.dialog_playlist_item, viewGroup, false)
            return ViewHolder(parent, view)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
            viewHolder.bind(parent.visibleRows[position])
        }

        override fun getItemCount() = parent.visibleRows.size
    }

    companion object {
        private const val TAG = "mpv"
    }
}

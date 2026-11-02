package com.molina.cinema

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
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
import com.molina.cinema.databinding.DialogSearchResultsBinding
import com.molina.core.common.StreamSearchItem

/** Satu baris hasil: nomor tetap (tidak berubah saat disaring). */
internal class SearchEntry(val number: Int, val item: StreamSearchItem)

/**
 * Daftar hasil pencarian di player, gaya sama dengan playlist dan Cinema Home:
 * nomor 001.., baris terpilih kotak biru, Prev/Next (satu halaman), Cari (teks = saring,
 * angka = lompat ke nomor, Enter = pilih), Info, Exit, penghitung + jam.
 */
internal class SearchResultsDialog(
    private val items: List<StreamSearchItem>,
    private val metaOf: (StreamSearchItem) -> String,
    /** Dipanggil dengan indeks item pada daftar asli. */
    private val onPick: (Int) -> Unit,
    private val onClose: () -> Unit,
) {
    private lateinit var b: DialogSearchResultsBinding
    private val entries = items.mapIndexed { i, r -> SearchEntry(i + 1, r) }
    private var visible = entries
    private var query = ""
    private var selected = 1

    fun buildView(inflater: LayoutInflater): View {
        b = DialogSearchResultsBinding.inflate(inflater)
        val ctx = b.root.context
        b.headerTitle.text = ctx.getString(R.string.search_results_title, entries.size)
        b.list.adapter = CustomAdapter()
        updateCount()

        b.prevBtn.setOnClickListener { page(-PAGE_SIZE) }
        b.nextBtn.setOnClickListener { page(PAGE_SIZE) }
        b.searchBtn.setOnClickListener { toggleSearch() }
        b.infoBtn.setOnClickListener {
            Toast.makeText(ctx, ctx.getString(R.string.tv_info_results, selected, entries.size),
                Toast.LENGTH_SHORT).show()
        }
        b.closeBtn.setOnClickListener { onClose() }

        b.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                applyFilter()
            }
        })
        b.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val q = query.trim()
                val target = if (q.isNotEmpty() && q.all { it.isDigit() })
                    visible.firstOrNull { it.number == q.toIntOrNull() }
                else visible.singleOrNull()
                if (target != null) onPick(target.number - 1)
                true
            } else false
        }

        Utils.handleInsetsAsPadding(b.root)
        return b.root
    }

    private fun lm() = b.list.layoutManager as LinearLayoutManager

    private fun updateCount() {
        b.countText.text = String.format("%03d/%03d", selected, entries.size)
    }

    private fun select(number: Int) {
        if (number == selected) return
        val old = visible.indexOfFirst { it.number == selected }
        val new = visible.indexOfFirst { it.number == number }
        selected = number
        if (old >= 0) b.list.adapter!!.notifyItemChanged(old)
        if (new >= 0) b.list.adapter!!.notifyItemChanged(new)
        updateCount()
    }

    /** Prev/Next: geser sorotan satu halaman dan gulir ke sana. */
    private fun page(delta: Int) {
        if (visible.isEmpty()) return
        val cur = visible.indexOfFirst { it.number == selected }.coerceAtLeast(0)
        val idx = (cur + delta).coerceIn(0, visible.lastIndex)
        select(visible[idx].number)
        lm().scrollToPositionWithOffset(idx, 0)
    }

    private fun toggleSearch() {
        val show = !b.searchInput.isVisible
        val imm = b.root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) {
            b.searchInput.isVisible = true
            b.searchInput.requestFocus()
            b.searchInput.post { imm.showSoftInput(b.searchInput, InputMethodManager.SHOW_IMPLICIT) }
        } else {
            b.searchInput.setText("")
            b.searchInput.isVisible = false
            imm.hideSoftInputFromWindow(b.searchInput.windowToken, 0)
        }
        val ctx = b.root.context
        val c = ContextCompat.getColor(ctx,
            if (show) R.color.player_playlist_active else R.color.player_dialog_text)
        b.searchBtn.setTextColor(c)
        b.searchBtn.compoundDrawableTintList = ColorStateList.valueOf(c)
    }

    private fun applyFilter() {
        val q = query.trim()
        val numeric = q.isNotEmpty() && q.all { it.isDigit() }
        visible = if (q.isEmpty() || numeric) entries
            else entries.filter { it.item.title.contains(q, ignoreCase = true) }
        var jumpTo = -1
        if (numeric) {
            jumpTo = visible.indexOfFirst { it.number == q.toIntOrNull() }
            if (jumpTo >= 0) selected = visible[jumpTo].number
        }
        b.list.adapter!!.notifyDataSetChanged()
        updateCount()
        if (jumpTo >= 0) lm().scrollToPositionWithOffset(jumpTo, 0)
    }

    private inner class CustomAdapter : RecyclerView.Adapter<CustomAdapter.Holder>() {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val numberView: TextView = view.findViewById(R.id.channelNumber)
            private val titleView: TextView = view.findViewById(R.id.resultTitle)
            private val metaView: TextView = view.findViewById(R.id.resultMeta)

            init {
                // Sorot saat disentuh (seperti menggeser kursor di TV), lalu klik memilih.
                view.setOnTouchListener { _, ev ->
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                        val pos = bindingAdapterPosition
                        if (pos >= 0) select(visible[pos].number)
                    }
                    false
                }
                view.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos >= 0) onPick(visible[pos].number - 1)
                }
            }

            fun bind(e: SearchEntry) {
                val ctx = titleView.context
                val sel = e.number == selected
                val textColor = ContextCompat.getColor(ctx, R.color.player_dialog_text)
                numberView.text = String.format("%03d", e.number)
                numberView.setTextColor(if (sel) textColor else 0xFFC8E600.toInt())
                titleView.text = e.item.title
                titleView.setTextColor(textColor)
                titleView.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
                val meta = metaOf(e.item)
                metaView.text = meta
                metaView.setTextColor(textColor)
                metaView.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
                itemView.setBackgroundResource(
                    if (sel) R.drawable.bg_channel_selected else R.drawable.bg_channel_row)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.dialog_search_result_row, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(visible[position])

        override fun getItemCount() = visible.size
    }

    companion object {
        /** Jumlah baris per Prev/Next. */
        private const val PAGE_SIZE = 10
    }
}

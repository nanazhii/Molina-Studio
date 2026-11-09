package com.molina.code.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.molina.code.guard.DiskGuard
import com.molina.code.guard.DiskVerdict
import com.molina.code.guard.ProjectCommit
import com.molina.code.guard.ProjectLog
import com.molina.code.guard.ProjectRestore
import com.molina.code.guard.ProjectStore
import com.molina.code.guard.RunReport
import com.molina.code.guard.ScriptPrecheck
import com.molina.code.history.HistoryKind
import com.molina.code.history.HistoryStore
import com.molina.code.preview.PreviewKind
import com.molina.code.preview.PythonTerminalRun
import com.molina.code.preview.TerminalRunLang
import com.molina.code.preview.RunPlans
import com.molina.code.run.RunTimeout
import com.molina.code.run.ScriptExecutor
import com.molina.code.run.ScriptShare
import com.molina.code.run.ScriptSink
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Satu tab editor. Tiap tab punya instance editor sendiri (dibuat malas) sehingga isi, posisi
 * kursor, dan riwayat undo/redo terpisah per tab (blueprint bagian 7).
 *
 * Penanda dirty dibandingkan dengan isi tersimpan (baseline berupa sidik jari teks), jadi undo
 * sampai kembali ke isi awal menghapus titik emas (blueprint bagian 31.2).
 */
class EditorTab internal constructor(val id: Int, initialTitle: String) {
    var title by mutableStateOf(initialTitle)
        internal set

    /** True bila isi berbeda dari yang tersimpan/dibuka. Menjadi titik emas pada judul tab. */
    var dirty by mutableStateOf(false)
        internal set

    /** Berkas di disk. Null = belum pernah disimpan (Simpan membuka dialog Simpan sebagai). */
    var file by mutableStateOf<File?>(null)
        internal set

    /** True selama isi tab masih dibaca dari disk/draf; editor dikunci agar tidak tertimpa. */
    var loading by mutableStateOf(false)
        internal set

    /** Naik setiap isi berubah (setelah event selesai). Memicu hitung ulang state Undo/Redo. */
    var revision by mutableIntStateOf(0)
        internal set

    /** Pembungkus kata per tab (default aktif, blueprint v2 bagian 12). Diubah dari menu titik tiga. */
    var wordWrap by mutableStateOf(true)

    /** Hanya baca per tab. Diubah dari menu titik tiga. */
    var readOnly by mutableStateOf(false)

    /** [CODE-GUARD] True bila berkas di disk berubah dari luar sementara tab punya perubahan sendiri. */
    var staleDisk by mutableStateOf(false)
        internal set

    /** Kunci entri Script Execution bila tab ini hasil EDIT dari Settings; dipakai saat Kirim ke Script Execution. */
    internal var scriptKey: String? = null

    /** Naik saat hasil pencarian atau seleksi berubah; memicu hitung ulang penghitung find bar. */
    var findRevision by mutableIntStateOf(0)
        internal set

    /** Akhir baris yang dipakai saat menulis ke disk. Editor sendiri selalu memegang teks LF. */
    internal var eol by mutableStateOf(Eol.LF)

    /** State agar find bar dan toolbar ikut diperbarui begitu editor tab selesai dibuat. */
    internal var editor by mutableStateOf<CodeEditor?>(null)

    /** Sidik jari isi tersimpan/dibuka (pembanding dirty). Tab "Baru" = sidik jari teks kosong. */
    internal var baseFp: Long = FileStore.EMPTY_FP

    /** Akhir baris pada isi tersimpan/dibuka. Beda dari [eol] = dirty (hanya akhir baris yang berubah). */
    internal var baseEol: Eol = Eol.LF

    /** [CODE-GUTTER] Teks tersimpan (LF) untuk penanda perubahan di gutter; null = dimuat malas dari disk. */
    @Volatile
    internal var baseText: String? = null
    internal var gutterTask: Runnable? = null
    internal var gutterJob: kotlinx.coroutines.Job? = null

    /** Teks hasil pemulihan yang menunggu editor dibuat. */
    internal var pendingText: String? = null

    /** Teks dari Terminal yang menunggu editor dibuat (lihat CodeSession.insertAtCursor). */
    internal var queuedInsert: String? = null

    /** Dipasang sesi: dipanggil tiap perubahan status isi untuk menjadwalkan autosave draf. */
    internal var onEdited: (() -> Unit)? = null

    private val checkTask = Runnable { recomputeDirty() }

    /** Isi tab saat ini; null bila lebih panjang dari [maxChars] atau belum ada. */
    internal fun currentTextOrNull(maxChars: Int): String? {
        val ed = editor
        if (ed != null) {
            val content = ed.text
            return if (content.length > maxChars) null else content.toString()
        }
        val p = pendingText ?: return null
        return if (p.length > maxChars) null else p
    }

    /** Tab kosong yang belum pernah disentuh; boleh dipakai ulang saat membuka berkas. */
    internal fun isPristine(): Boolean {
        if (file != null || dirty || loading || queuedInsert != null) return false
        val len = editor?.text?.length ?: (pendingText?.length ?: 0)
        return len == 0
    }

    /** Bandingkan isi sekarang dengan baseline. Dipanggil setelah edit (ditunda) dan setelah simpan/muat. */
    internal fun recomputeDirty() {
        if (loading) return
        val ed = editor
        val text: String? = if (ed != null) {
            val c = ed.text
            if (c.length > MAX_COMPARE_CHARS) null else c.toString()
        } else {
            pendingText ?: ""
        }
        val now = text == null || FileStore.fingerprint(text) != baseFp || eol != baseEol
        if (now != dirty) {
            dirty = now
            onEdited?.invoke()
        }
    }

    /** Tunda hitung ulang dirty sampai edit berhenti (trailing debounce), supaya mengetik tetap ringan. */
    internal fun scheduleDirtyCheck(view: View) {
        view.removeCallbacks(checkTask)
        view.postDelayed(checkTask, DIRTY_CHECK_MS)
    }

    /** Isi [text] kini tersimpan di disk dengan akhir baris [savedEol]. */
    internal fun markSaved(text: String, savedEol: Eol) {
        baseFp = FileStore.fingerprint(text)
        baseEol = savedEol
        baseText = if (text.length <= 400_000) text else null
        staleDisk = false
        recomputeDirty()
        ChangeGutter.schedule(this)
    }

    /** Pasang isi bersih dari disk (buka atau muat ulang). Teks sudah LF. */
    internal fun applyDiskText(text: String, fileEol: Eol) {
        setBody(text)
        eol = fileEol
        baseEol = fileEol
        baseFp = FileStore.fingerprint(text)
        baseText = if (text.length <= 400_000) text else null
        dirty = false
        staleDisk = false
        loading = false
        editor?.setEditable(!readOnly)
        ChangeGutter.schedule(this)
    }

    /** Pasang isi dari draf: baseline dan akhir baris dikembalikan, lalu dirty dihitung ulang. */
    internal fun applyDraft(text: String, draftEol: Eol, base: Long, baseEolSaved: Eol) {
        setBody(text)
        eol = draftEol
        baseFp = base
        baseEol = baseEolSaved
        baseText = null
        loading = false
        editor?.setEditable(!readOnly)
        dirty = true
        recomputeDirty()
        ChangeGutter.schedule(this)
    }

    /** Berkas tab dihapus dari disk: isi tinggal di editor, jadi tab ditandai belum tersimpan. */
    internal fun markOrphaned() {
        file = null
        baseFp = FileStore.ORPHAN_FP
        dirty = true
        onEdited?.invoke()
    }

    private fun setBody(text: String) {
        val ed = editor
        if (ed == null) pendingText = text else ed.setText(text)
    }

    internal fun disposeEditor() {
        editor?.release()
        editor = null
    }

    companion object {
        private const val DIRTY_CHECK_MS = 150L
        private const val MAX_COMPARE_CHARS = 2_000_000
    }
}

/** Permintaan dialog Simpan sebagai. [after] dipanggil setelah berkas benar-benar tersimpan. */
class SaveAsRequest internal constructor(val tab: EditorTab, val after: (() -> Unit)?)

/**
 * Status layar Code: daftar tab, tab aktif, dan panel direktori. Dibuat SATU kali di tingkat
 * Activity supaya tab tidak hilang saat pengguna pindah ke tab bawah lain.
 *
 * Bila [draftDir] diberikan, tab beserta isi yang belum disimpan ditulis ke folder itu (autosave
 * berjeda 1,5 detik, plus [flushDrafts] saat Activity berhenti) dan dipulihkan saat sesi dibuat
 * lagi, jadi tab "Baru" tidak hilang ketika proses dimatikan atau layar diputar.
 */
class CodeSession(val workDir: String, draftDir: File? = null) {
    val tabs = mutableStateListOf<EditorTab>()

    val find = FindState()

    /** Dialog Pergi ke baris terbuka. */
    var gotoOpen by mutableStateOf(false)

    /** [CODE-PALETTE] Palet Perintah terbuka. */
    var paletteOpen by mutableStateOf(false)

    /** [CODE-SEARCH] Dialog Cari di berkas dan Struktur berkas terbuka. */
    var findFilesOpen by mutableStateOf(false)
    var outlineOpen by mutableStateOf(false)

    /** [CODE-SYMBOL] Dialog Pergi ke simbol terbuka, dan indeks simbol folder utama (cache per berkas, hidup selama sesi). */
    var symbolOpen by mutableStateOf(false)
    internal val symbolIndex = SymbolIndex()

    /** [CODE-INFO] Layar Informasi berkas (statistik teks dan jenis berkas) terbuka. */
    var infoOpen by mutableStateOf(false)

    /** [CODE-FONT] Dialog Ukuran font editor terbuka. */
    var fontOpen by mutableStateOf(false)

    /** [CODE-FONT] Ukuran font semua editor (sp); diingat lewat [runStore], dimuat saat runStore dipasang. */
    var editorFontSp by mutableIntStateOf(FONT_DEFAULT_SP)
        private set

    fun applyEditorFontSp(sp: Int) {
        val v = sp.coerceIn(FONT_MIN_SP, FONT_MAX_SP)
        if (v == editorFontSp) return
        editorFontSp = v
        runStore?.put(RunPrefs.KEY_FONT_SP, v.toString())
    }

    /** [CODE-STYLE] Keluarga font, ligature, dan sticky scroll semua editor; diingat lewat [runStore]. */
    internal var editorFont by mutableStateOf(EditorFont.JETBRAINS)
        private set
    var editorLigature by mutableStateOf(true)
        private set
    var editorSticky by mutableStateOf(true)
        private set

    internal fun applyEditorFont(f: EditorFont) {
        if (f == editorFont) return
        editorFont = f
        runStore?.put(RunPrefs.KEY_FONT_FAMILY, f.id)
    }

    fun toggleEditorLigature() {
        editorLigature = !editorLigature
        runStore?.put(RunPrefs.KEY_LIGATURE, if (editorLigature) "1" else "0")
    }

    fun toggleEditorSticky() {
        editorSticky = !editorSticky
        runStore?.put(RunPrefs.KEY_STICKY, if (editorSticky) "1" else "0")
    }

    /** [CODE-TM] Dialog Tema kode, tema token aktif, dan saklar highlight TextMate. */
    var themeOpen by mutableStateOf(false)
    var editorTheme by mutableStateOf(MolinaTextMate.DEFAULT_THEME)
        private set
    var tmEnabled by mutableStateOf(true)
        private set

    fun applyEditorTheme(id: String) {
        if (id == editorTheme) return
        editorTheme = id
        runStore?.put(RunPrefs.KEY_TM_THEME, id)
    }

    fun toggleTextMate() {
        tmEnabled = !tmEnabled
        runStore?.put(RunPrefs.KEY_TM_ON, if (tmEnabled) "1" else "0")
    }

    private fun loadEditorFont() {
        runStore?.get(RunPrefs.KEY_FONT_SP)?.toIntOrNull()?.let { editorFontSp = it.coerceIn(FONT_MIN_SP, FONT_MAX_SP) }
        runStore?.get(RunPrefs.KEY_FONT_FAMILY)?.let { editorFont = EditorFont.fromId(it) }
        runStore?.get(RunPrefs.KEY_LIGATURE)?.let { editorLigature = it != "0" }
        runStore?.get(RunPrefs.KEY_STICKY)?.let { editorSticky = it != "0" }
        runStore?.get(RunPrefs.KEY_TM_THEME)?.let { editorTheme = MolinaTextMate.themeOf(it).id }
        runStore?.get(RunPrefs.KEY_TM_ON)?.let { tmEnabled = it != "0" }
        runStore?.get(RunPrefs.KEY_FORMAT_SAVE)?.let { formatOnSave = it == "1" } // [CODE-FORMAT]
        runStore?.get(RunPrefs.KEY_DIFF_CONTRAST)?.let { diffContrast = it == "1" } // [CODE-HISTORY2]
        runStore?.get(RunPrefs.KEY_RUN_TIMEOUT)?.let { run.timeoutMs = RunTimeout.parse(it) } // [CODE-RUNTIMEOUT]
        runStore?.get(RunPrefs.KEY_CHECK_SAVE)?.let { checkOnSave = it == "1" } // [CODE-CHECK-SAVE]
        runStore?.get(RunPrefs.KEY_LSP_LIVE)?.let { liveEnabled = it == "1"; live.setEnabled(liveEnabled) } // [CODE-LSP3]
    }

    /** [CODE-FORMAT] Format otomatis sebelum menyimpan berkas (bawaan mati). Galat format tidak pernah menahan simpan. */
    var formatOnSave by mutableStateOf(false)
        private set

    /** [CODE-CHECK-SAVE] Periksa kode otomatis SETELAH berkas tersimpan (bawaan mati). Tidak pernah menahan atau mengubah simpan. */
    var checkOnSave by mutableStateOf(false)
        private set

    fun toggleCheckOnSave() {
        checkOnSave = !checkOnSave
        runStore?.put(RunPrefs.KEY_CHECK_SAVE, if (checkOnSave) "1" else "0")
    }

    /** [CODE-CHECK-SAVE] Galat pemeriksa saat simpan hanya dimunculkan sekali per sesi (mis. shellcheck belum terpasang). */
    internal var checkFailNoted = false

    /** [CODE-HISTORY2] Diff kontras tinggi di Riwayat versi (Tema kode). Bawaan mati: memakai token Clean/Danger transparan. */
    var diffContrast by mutableStateOf(false)
        private set

    fun toggleDiffContrast() {
        diffContrast = !diffContrast
        runStore?.put(RunPrefs.KEY_DIFF_CONTRAST, if (diffContrast) "1" else "0")
    }

    /** [CODE-RUNTIMEOUT] Ganti batas waktu skrip (berlaku untuk run berikutnya) dan ingat lewat [runStore]. */
    fun cycleRunTimeout() {
        val next = RunTimeout.next(run.timeoutMs)
        run.timeoutMs = next
        runStore?.put(RunPrefs.KEY_RUN_TIMEOUT, next.toString())
        toast("Batas waktu skrip: ${RunTimeout.short(next)} (berlaku untuk run berikutnya)")
    }

    /** Catatan galat format untuk toast "Tersimpan" berikutnya. */
    private var saveNote: String? = null

    fun toggleFormatOnSave() {
        formatOnSave = !formatOnSave
        runStore?.put(RunPrefs.KEY_FORMAT_SAVE, if (formatOnSave) "1" else "0")
    }

    /** [CODE-REPLACE] Penggantian terakhir di berkas, untuk Urungkan (hanya di memori). */
    internal var lastReplace by mutableStateOf<ReplaceUndo?>(null)

    /** [CODE-TEMPLATE] Dialog Live Template dan pemilih jenis Scratch terbuka. */
    var templatesOpen by mutableStateOf(false)
    var scratchOpen by mutableStateOf(false)

    /** Dialog Simpan sebagai yang sedang terbuka (null = tertutup). */
    var saveAs by mutableStateOf<SaveAsRequest?>(null)
        private set

    /** Dialog aksi berkas (menu, ganti nama, hapus, baru). Dirender oleh [FileDialogs]. */
    internal var fileDialog by mutableStateOf<FileDialog?>(null)

    /** Pemilih berkas/folder yang sedang terbuka (null = tertutup). */
    internal var picker by mutableStateOf<PickerRequest?>(null)

    /** Tab dirty yang menunggu konfirmasi Muat ulang. */
    internal var reloadConfirm by mutableStateOf<EditorTab?>(null)
        private set

    var activeId by mutableIntStateOf(-1)
        private set

    /** Pesan singkat untuk pengguna (Toast). Dipasang oleh CodeScreen. */
    var toast: (String) -> Unit = {}

    private var nextId = 1
    private var lastSaveDir: File? = null

    internal val mainHandler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "molina-code-io").apply { isDaemon = true }
    }
    private val store: DraftStore? = draftDir?.let { DraftStore(it) }

    /** [CODE-HISTORY] Riwayat versi per berkas (folder privat, bersama folder draf); null bila sesi tanpa folder draf. */
    internal val history: HistoryStore? = draftDir?.let { HistoryStore(File(it, "history")) }
    var historyOpen by mutableStateOf(false)
    /** [CODE-HISTORY6] Bila terisi, dialog Riwayat langsung membuka versi terbaru berjenis ini (dari kartu perubahan skrip). */
    var historyFocus by mutableStateOf<HistoryKind?>(null)
    /** [CODE-HISTORY8] Blok perubahan yang sedang ditampilkan di popup gutter (null = tertutup). */
    internal var gutterTap by mutableStateOf<GutterTap?>(null)
    private var historyTrimmed = false

    /** [CODE-TEMPLATE] Template buatan pengguna (disimpan di folder draf privat). */
    internal val templates = TemplateStore(draftDir?.let { File(it, "live_templates.json") })
    private val draftTask = Runnable { persistDrafts() }

    /** Panel direktori (F4). Folder utama awal = folder kerja. */
    internal val dir = DirectoryState(this, File(workDir))

    /** Panel pratinjau langsung / keluaran skrip di bawah editor (tombol Play). */
    internal val preview = PreviewController()

    /** True saat halaman penuh terbuka (Tinjauan Markdown/JSON/HTML atau Keluaran skrip); :app menyembunyikan bottom nav. */
    val previewFullScreen: Boolean
        get() = preview.open

    /** Opsi Settings "Pakai WebView untuk HTML"; dipasang :app. */
    var htmlWebView: Boolean
        get() = preview.htmlWebView
        set(value) { preview.htmlWebView = value }

    /** Status eksekusi skrip untuk panel Keluaran. */
    internal val run = RunController(mainHandler).also { it.onRunFinished = { refreshExternal(); collectRunChanges() } } // [CODE-GUARD]

    /** [CODE-GUARD] Simpan ditahan: berkas berubah di disk sejak dibuka/disimpan. Dialog: Batal/Bandingkan/Muat ulang/Timpa. */
    internal var staleConflict by mutableStateOf<StaleConflict?>(null)

    /** [CODE-GUARD] Skrip yang tampak rusak (terpotong, kutip/kurung terbuka). Dialog: Tetap jalankan/Batal. */
    internal var scriptCheck by mutableStateOf<ScriptCheckPrompt?>(null)

    /** [CODE-RUNREPORT] Perubahan berkas akibat skrip terakhir (kartu di panel Keluaran) dan dialog konfirmasi Urungkan. */
    internal var runChanges by mutableStateOf<RunChanges?>(null)
    internal var undoRunOpen by mutableStateOf(false)
    private var runSnap: RunReport.Snapshot? = null
    private var runTitle: String = "" // [CODE-PROJLOG] judul skrip yang sedang jalan, untuk pesan catatan proyek
    /** [CODE-PROJLOG] Potret Riwayat proyek yang sedang ditampilkan (null = dialog tertutup). */
    internal var projectLogView by mutableStateOf<ProjectLogView?>(null)
    private var runStarting = false

    /** Dipasang :app (Debian PRoot). Null = tombol Play untuk skrip memberi tahu bahwa belum tersedia. */
    var scriptExecutor: ScriptExecutor?
        get() = run.executor
        set(value) { run.executor = value }

    /** [CODE-FORMAT] Pemformat kode (bawaan + Debian). */
    internal val format = FormatController(mainHandler)

    /** [CODE-FORMAT] Dipasang :app (Debian PRoot, jalur FORMAT). Null = pemformat Debian memberi tahu belum tersedia; JSON/XML tetap jalan. */
    var formatExecutor: ScriptExecutor?
        get() = format.executor
        set(value) { format.executor = value }

    /** [CODE-CHECK] Pemeriksa kode sekali jalan (bawaan + Debian), hasil terakhir, dan dialog daftar masalah. */
    internal val checker = CheckController(mainHandler)
    internal var problems by mutableStateOf<ProblemList?>(null)
    var problemsOpen by mutableStateOf(false)

    /** [CODE-CHECK] Dipasang :app (Debian PRoot, jalur CHECK). Null = pemeriksa Debian memberi tahu belum tersedia; JSON/XML tetap jalan. */
    var checkExecutor: ScriptExecutor?
        get() = checker.executor
        set(value) { checker.executor = value }

    /** [CODE-LSP2] Dipasang :app (Debian PRoot, jalur LSP): proses menetap dua arah untuk language server. Null = LSP tidak tersedia. */
    var lspExecutor: com.molina.code.lsp.StreamExecutor? = null

    /** [CODE-LSP3] Dipasang :app: penerjemah path Android <-> Debian (aturan terlihat mengikuti Terminal; dibaca ulang tiap sesi mulai). */
    var lspPathMap: (() -> com.molina.code.lsp.LspPathMap)? = null

    /** [CODE-LSP3] Saklar "Diagnostik langsung (Python)" (bawaan mati) dan status sesi untuk lencana di bilah status. */
    var liveEnabled by mutableStateOf(false)
        private set
    var liveState by mutableStateOf(com.molina.code.lsp.LiveState.OFF)
        private set
    var liveDetail by mutableStateOf<String?>(null)
        private set

    private val liveEvents = object : com.molina.code.lsp.LspLiveEvents {
        override fun onState(state: com.molina.code.lsp.LiveState, detail: String?) {
            liveState = state
            liveDetail = detail
        }

        override fun onNotice(message: String) = toast(message)

        override fun onDiagnostics(tabId: Int, hostPath: String, items: List<com.molina.code.check.Diagnostic>, stamp: com.molina.code.lsp.TextStamp?) =
            applyLiveDiagnostics(tabId, items, stamp)
    }

    internal val live = com.molina.code.lsp.LspLive(
        env = AndroidLiveEnv(mainHandler),
        events = liveEvents,
        executorProvider = { if (lspPathMap != null) lspExecutor else null },
        pathsProvider = { lspPathMap!!.invoke() },
    )

    /**
     * [CODE-LSP5] Pintu completion untuk bahasa editor [tab]; null bila saklar mati, berkas belum punya path, atau bukan
     * Python. Dipanggil dari thread latar Sora: hanya membaca nilai, tidak menyentuh editor.
     */
    internal fun completionBackendFor(tab: EditorTab): LspCompletionBackend? {
        if (!liveEnabled || tab.loading) return null
        val f = tab.file ?: return null
        if (isLibraryFile(f)) return null // [CODE-DEBLIB]
        val spec = com.molina.code.lsp.LspServers.of(f.name, null) ?: return null
        val path = f.absolutePath
        return LspCompletionBackend { text, line, column ->
            live.completeBlocking(path, spec.languageId, text, line, column, COMPLETION_TIMEOUT_MS)
        }
    }

    /** [CODE-LSP6] Permintaan definisi sedang menunggu server; menu "Pergi ke definisi" redup selama itu. */
    var definitionBusy by mutableStateOf(false)
        private set

    /** [CODE-LSP6] Daftar tujuan bila definisi punya lebih dari satu hasil; null = dialog tertutup. */
    var definitionChoices by mutableStateOf<List<com.molina.code.lsp.DefinitionChoice>?>(null)

    /** [CODE-LSP6] Pergi ke definisi nama di kursor lewat pylsp (Jedi): lompat, pilih dari daftar, atau toast alasannya. */
    fun goToDefinition() {
        if (definitionBusy) return
        val tab = active ?: return
        val ed = tab.editor ?: return
        val f = tab.file
        if (!liveEnabled) return toast("Nyalakan \"Python langsung\" di menu titik tiga dulu")
        if (f == null) return toast("Simpan berkas dulu agar server bisa membacanya")
        if (isLibraryFile(f)) return toast("Pergi ke definisi belum didukung dari berkas pustaka Debian") // [CODE-DEBLIB]
        if (tab.loading) return
        val spec = com.molina.code.lsp.LspServers.of(f.name, tab.firstLineOrNull())
            ?: return toast("Pergi ke definisi saat ini hanya untuk Python")
        val text = tab.currentTextOrNull(MAX_LIVE_CHARS) ?: return toast("Berkas terlalu besar untuk server")
        val pos = try {
            val l = ed.cursor.leftLine
            Triple(l, ed.cursor.leftColumn, ed.text.getLineString(l))
        } catch (e: Exception) {
            return // editor sedang dibuat ulang
        }
        val line = pos.first
        val word = com.molina.code.lsp.LspDefinitionLogic.wordAt(pos.third, pos.second)
            ?: return toast("Letakkan kursor pada sebuah nama")
        val stamp = com.molina.code.lsp.TextStamp(text.length, text.hashCode())
        val path = f.absolutePath
        definitionBusy = true
        liveSync(tab) // daftarkan dokumen supaya sesi yang tidur bisa dibangunkan
        live.requestDefinition(path, spec.languageId, text, line, word.requestColumn, DEFINITION_TIMEOUT_MS) { reply ->
            definitionBusy = false
            onDefinitionReply(tab, stamp, path, word, line, reply)
        }
    }

    private fun onDefinitionReply(
        tab: EditorTab, stamp: com.molina.code.lsp.TextStamp, path: String,
        word: com.molina.code.lsp.WordSpan, line: Int, reply: com.molina.code.lsp.LspDefinitionReply,
    ) {
        when (reply) {
            is com.molina.code.lsp.LspDefinitionReply.Error -> toast(reply.reason)
            is com.molina.code.lsp.LspDefinitionReply.Targets -> {
                if (tabs.none { it === tab } || activeId != tab.id) return // pengguna sudah pindah tab: jangan melompat
                val now = tab.currentTextOrNull(MAX_LIVE_CHARS)
                if (now == null || !stamp.matches(now)) return toast("Teks berubah saat mencari, ketuk lagi")
                val first = com.molina.code.lsp.LspDefinitionLogic.classify(reply.list, path, word, line, dir.root.path) { java.io.File(it).isFile }
                // [CODE-DEBLIB] tujuan hanya di Debian: buka lewat path rootfs (hanya-baca)
                val debRoot = if (first is com.molina.code.lsp.DefinitionOutcome.OnlyInDebian) debianRoot?.invoke() else null
                val outcome = if (debRoot != null) {
                    com.molina.code.lsp.LspDefinitionLogic.classifyDebian(reply.list) { com.molina.code.nav.RootfsPaths.resolve(debRoot, it) }
                } else {
                    first
                }
                when (outcome) {
                    is com.molina.code.lsp.DefinitionOutcome.Jump -> openLocation(outcome.hostPath, outcome.line + 1, outcome.column)
                    is com.molina.code.lsp.DefinitionOutcome.Choose -> definitionChoices = outcome.choices
                    else -> com.molina.code.lsp.LspDefinitionLogic.message(outcome)?.let { toast(it) }
                }
            }
        }
    }

    /** [CODE-LSP6] Pilihan dari daftar tujuan: tutup dialog lalu buka berkas dan lompat. */
    internal fun openDefinitionChoice(c: com.molina.code.lsp.DefinitionChoice) {
        definitionChoices = null
        openLocation(c.hostPath, c.line + 1, c.column)
    }

    // ---------------------------------------------------------------- [CODE-BACK] Kembali setelah lompat

    private val jumps = com.molina.code.nav.JumpStack()

    /** Jumlah posisi yang bisa dikembalikan; menu Kembali redup bila 0. */
    var backDepth by mutableIntStateOf(0)
        private set

    /** Label "nama:baris" untuk pil Kembali yang tampil sebentar setelah lompat; null = pil tersembunyi. */
    var backHint by mutableStateOf<String?>(null)

    private fun tabAlive(id: Int): Boolean = tabs.any { it.id == id }

    /** Posisi kursor tab aktif untuk dicatat sebelum melompat. Null bila tab kosong, memuat, atau editor belum ada. */
    private fun currentJumpPoint(): com.molina.code.nav.JumpPoint? {
        val tab = active ?: return null
        val ed = tab.editor ?: return null
        if (tab.loading || tab.isPristine()) return null
        return try {
            com.molina.code.nav.JumpPoint(tab.id, ed.cursor.leftLine, ed.cursor.leftColumn + 1)
        } catch (e: Exception) {
            null // editor sedang dibuat ulang
        }
    }

    private fun recordJump(origin: com.molina.code.nav.JumpPoint?, destTabId: Int, destLine: Int) {
        if (jumps.push(origin, com.molina.code.nav.JumpPoint(destTabId, destLine, 0))) {
            backDepth = jumps.size
            backHint = backLabel()
        }
    }

    private fun backLabel(): String? {
        val p = jumps.peek { tabAlive(it) } ?: return null
        val t = tabs.firstOrNull { it.id == p.tabId } ?: return null
        return (t.file?.name ?: t.title) + ":" + (p.line + 1)
    }

    /** Kembali ke posisi sebelum lompatan terakhir; tab yang sudah ditutup dilewati. */
    fun jumpBack() {
        val p = jumps.pop { tabAlive(it) }
        val tab = if (p == null) null else tabs.firstOrNull { it.id == p.tabId }
        backDepth = jumps.size
        backHint = if (backDepth > 0) backLabel() else null
        if (p == null || tab == null) return toast("Tidak ada posisi sebelumnya")
        preview.open = false
        activeId = tab.id
        jumpWhenReady(tab, p.line, p.column, 40)
    }

    // ---------------------------------------------------------------- [CODE-DEBLIB] Berkas pustaka Debian (hanya-baca)

    /** Dipasang :app. Folder rootfs Debian di Android; null = fitur buka pustaka Debian tidak tersedia. */
    var debianRoot: (() -> File?)? = null

    private val libCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile private var libRootPath: String? = null

    /** True bila [f] berada di dalam rootfs Debian: tab-nya dikunci hanya-baca, tanpa Aksi file, Simpan, dan LSP. */
    internal fun isLibraryFile(f: File?): Boolean {
        if (f == null) return false
        val root = debianRoot?.invoke() ?: return false
        if (libRootPath != root.path) { libRootPath = root.path; libCache.clear() }
        return libCache.getOrPut(f.path) { com.molina.code.nav.RootfsPaths.isUnder(root, f) }
    }

    fun toggleLive() {
        if (!liveEnabled && (lspExecutor == null || lspPathMap == null)) return toast("Diagnostik langsung belum tersedia di perangkat ini")
        liveEnabled = !liveEnabled
        runStore?.put(RunPrefs.KEY_LSP_LIVE, if (liveEnabled) "1" else "0")
        live.setEnabled(liveEnabled)
        if (liveEnabled) active?.let { liveSync(it) }
        else tabs.forEach { t -> // matikan: buang garis bawah milik server
            if (problems?.tabId == t.id && problems?.label == LIVE_LABEL) clearProblems(t)
        }
    }

    /**
     * [CODE-LSP3] Kabari sesi LSP bahwa isi [tab] berubah/dibuka (debounce di [com.molina.code.lsp.LspLive]). Hanya berkas
     * Python yang sudah punya path di disk (server membaca path guest); selain itu dilepas dari sesi.
     */
    internal fun liveSync(tab: EditorTab) {
        if (!liveEnabled) return
        val f = tab.file
        if (f == null || tab.loading) { if (f == null) live.release(tab.id); return }
        if (isLibraryFile(f)) { live.release(tab.id); return } // [CODE-DEBLIB] pustaka Debian tidak dikirim ke pylsp
        if (tab.editor == null) return
        val spec = com.molina.code.lsp.LspServers.of(f.name, tab.firstLineOrNull())
        if (spec == null) { live.release(tab.id); return }
        live.touch(tab.id, f.absolutePath, spec) { tab.currentTextOrNull(MAX_LIVE_CHARS) }
    }

    /** Pasang diagnostik server hanya bila isi editor masih persis yang dikirim; selain itu menunggu hasil yang lebih baru. */
    private fun applyLiveDiagnostics(tabId: Int, items: List<com.molina.code.check.Diagnostic>, stamp: com.molina.code.lsp.TextStamp?) {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        val ed = tab.editor ?: return
        val text = tab.currentTextOrNull(MAX_LIVE_CHARS) ?: return
        if (stamp == null || !stamp.matches(text)) return
        if (items.isEmpty()) {
            clearProblems(tab)
            return
        }
        try {
            ed.diagnostics = diagnosticsContainer(text, items)
        } catch (e: Exception) {
            return // editor sedang dibuat ulang
        }
        problems = ProblemList(tab.id, tab.file?.name ?: tab.title, LIVE_LABEL, items, null)
    }

    /** [CODE-CHECK] Lompat ke masalah ([line], [col] basis 1) di tab [tabId]. */
    internal fun jumpToProblem(tabId: Int, line: Int, col: Int) {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        preview.open = false
        activeId = tab.id
        jumpWhenReady(tab, (line - 1).coerceAtLeast(0), (col - 1).coerceAtLeast(0), 40)
    }

    /** Dipasang :app. Null = menu \"Kirim ke Script Execution\" redup. */
    var scriptSink: ScriptSink? = null

    /** Dipasang :app. (teks, jalankan) -> pesan galat atau null. Null = menu Terminal redup. */
    var terminalSink: ((String, Boolean) -> String?)? = null

    /** MOLINA-H13-BRIDGE: dipasang :app. (berkas) -> pesan galat atau null bila Vault berhasil dibuka. Null = menu redup. */
    var vaultSink: ((File) -> String?)? = null

    /** Menu titik tiga: simpan berkas tab aktif ke Molina Vault. Vault membaca dari disk, jadi tab harus sudah tersimpan. */
    internal fun saveToVault() {
        val sink = vaultSink ?: run { toast("Molina Vault belum tersedia"); return }
        val tab = active ?: return
        if (tab.loading) return
        val f = tab.file ?: run { toast("Simpan berkas ke disk dulu"); return }
        if (tab.dirty) { toast("Ada perubahan belum disimpan. Simpan dulu: Vault membaca berkas dari disk"); return }
        sink(f)?.let { toast(it) }
    }

    /** Teks seleksi aktif, atau baris kursor bila tidak ada seleksi. Kosong bila tidak ada editor. */
    internal fun selectionOrLine(): String {
        val ed = active?.editor ?: return ""
        return try {
            val c = ed.cursor
            if (c.isSelected) ed.text.substring(c.left, c.right) else ed.text.getLineString(c.leftLine)
        } catch (e: Exception) {
            ""
        }
    }

    /** Kirim seleksi (atau baris kursor) ke Terminal; [execute] true = langsung Enter. */
    fun sendToTerminal(execute: Boolean) {
        val sink = terminalSink
        if (sink == null) { toast("Terminal belum tersedia"); return }
        val text = selectionOrLine().trimEnd('\n', '\r')
        if (text.isBlank()) { toast("Tidak ada teks untuk dikirim"); return }
        sink(text, execute)?.let { toast(it) }
    }

    // ---------------------------------------------------------------- [CODE-PYRUN2] Jalankan Python/Shell di Terminal

    /** Dialog "Jalankan di Terminal" untuk .py/.sh terbuka. */
    var pyRunOpen by mutableStateOf(false)

    /** Dipasang :app. Berkas host -> jalur guest Debian, atau null bila foldernya tidak terlihat di PRoot. */
    var guestPathResolver: ((File) -> String?)? = null

    /** Dipasang :app. Menyimpan pilihan dialog (mode terakhir, tanya/langsung). Null = tidak diingat. */
    var runStore: RunStore? = null
        set(value) { field = value; loadEditorFont() } // [CODE-FONT]

    /** Penulisan berkas terakhir oleh Jalankan di Terminal, untuk Urungkan (hanya di memori). */
    internal var lastTerminalRun by mutableStateOf<TerminalRunUndo?>(null)

    /** Bahasa tab aktif (.py / .sh) dari berkas, atau judul untuk tab tanpa berkas; null = bukan skrip yang dikenal. */
    internal fun runLang(): TerminalRunLang? {
        val t = active ?: return null
        return PythonTerminalRun.langOf(t.file?.name ?: t.title)
    }

    /** True = dialog tampil (bawaan). False = pengguna memilih "ingat pilihan", langsung jalankan dengan mode terakhir. */
    internal fun runAsk(): Boolean = runStore?.get(RunPrefs.KEY_ASK) != "0"

    internal fun saveRunAsk(ask: Boolean) {
        val s = runStore ?: return
        s.put(RunPrefs.KEY_ASK, if (ask) "1" else "0")
    }

    /** [CODE-ROLES] Untuk halaman Pengaturan Editor Code: tanya dulu (dialog) atau langsung jalankan. */
    var runDialogAsks: Boolean
        get() = runAsk()
        set(v) { saveRunAsk(v) }

    /** [CODE-ROLES] Ada penulisan berkas terakhir dari Jalankan di Terminal yang bisa diurungkan. */
    val terminalRunUndoReady: Boolean get() = lastTerminalRun != null

    fun undoTerminalRun() = undoLastTerminalRun()

    /**
     * Titik masuk menu "Jalankan di Terminal" untuk tab .py/.sh. Dialog tampil kecuali pengguna memilih langsung;
     * mode berkas langsung hanya untuk tab yang sudah punya berkas (tab baru tetap membuka dialog untuk memberi nama).
     */
    fun runInTerminalForLang() {
        val lang = runLang()
        val tab = active
        if (lang == null || tab == null) { sendToTerminal(true); return }
        val own = tab.file // [CODE-ROLES] heredoc dipensiunkan: selalu dari berkas, diperiksa molina-run
        if (runAsk() || own == null) { pyRunOpen = true; return }
        runScriptFileAt(tab, own, lang)
    }

    internal fun scriptHasSelection(): Boolean = try {
        active?.editor?.cursor?.isSelected == true
    } catch (e: Exception) {
        false
    }

    /** Berkas tujuan mode berkas: folder berkas tab (atau folder simpan bawaan) + [fileName]. */
    internal fun scriptTarget(tab: EditorTab, fileName: String?): File? {
        if (fileName == null) return null
        return File(tab.file?.parentFile ?: defaultSaveDir(tab), fileName)
    }

    /** Mode 2: nama dari dialog (ekstensi otomatis) lalu [runScriptFileAt]. */
    fun runScriptFile(rawName: String, lang: TerminalRunLang) {
        val tab = active ?: return
        val n = PythonTerminalRun.normalizeName(rawName, lang)
        val target = scriptTarget(tab, n.name)
        if (target == null) { toast(n.error ?: "Nama tidak valid"); return }
        runScriptFileAt(tab, target, lang)
    }

    /**
     * Tulis isi tab ke [target] lalu jalankan dari foldernya. Isi lama dicadangkan ([lastTerminalRun]) sebelum
     * ditimpa. Tab bersih yang sudah berupa [target] langsung dijalankan tanpa menulis.
     */
    internal fun runScriptFileAt(tab: EditorTab, target: File, lang: TerminalRunLang) {
        val sink = terminalSink
        if (sink == null) { toast("Terminal belum tersedia"); return }
        if (tab.loading) { toast("Editor sedang memuat"); return }
        val guest = guestPathResolver?.invoke(target)
        if (guest == null) {
            toast("Folder ini tidak terlihat di Debian. Simpan di folder kerja Molina atau aktifkan akses penuh storage.")
            return
        }
        val cmd = PythonTerminalRun.fileCommand(guest, lang)
        if (tab.file == target && !tab.dirty) { sink(cmd, true)?.let { toast(it) }; return }
        val raw = FileStore.applyEol(tab.currentTextOrNull(Int.MAX_VALUE) ?: "", tab.eol)
        if (raw.isBlank()) { toast("Tidak ada kode untuk dijalankan"); return }
        checkedByMolinaRun { // [CODE-ROLES]
            val content = raw // [CODE-ROLES]
            if (content.isBlank()) { toast("Tidak ada kode untuk dijalankan"); return@checkedByMolinaRun }
            runIo {
                var undo: TerminalRunUndo? = null
                var err: String? = null
                try {
                    undo = TerminalRunEngine.begin(target, content)
                    FileStore.writeUtf8Atomic(target, content)
                } catch (e: Exception) {
                    err = e.message ?: "Gagal menulis berkas"
                }
                val failure = err
                val made = undo
                mainHandler.post {
                    if (failure != null) { toast(failure); return@post }
                    lastTerminalRun = made
                    dir.refresh()
                    sink(cmd, true)?.let { toast(it) }
                }
            }
        }
    }

    /** Dipasang :app. (nama, isi) -> pesan hasil untuk toast. Null = menu Simpan sebagai snippet redup. */
    var snippetSink: ((String, String) -> String)? = null

    /** Simpan seleksi (atau baris kursor) sebagai snippet Terminal. Nama = baris pertama yang tidak kosong. */
    fun saveSelectionAsSnippet() {
        val sink = snippetSink
        if (sink == null) { toast("Snippet belum tersedia"); return }
        val text = selectionOrLine().trimEnd('\n', '\r')
        if (text.isBlank()) { toast("Tidak ada teks untuk disimpan"); return }
        if (text.length > 4000) { toast("Teks terlalu panjang untuk snippet (maks. 4000 karakter)"); return }
        val name = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(40)
        toast(sink(name, text))
    }

    /**
     * Sisipkan teks di posisi kursor tab aktif (mengganti seleksi bila ada). Mengembalikan pesan galat atau null.
     * Bila editor tab belum dibuat (tab Code belum pernah tampil), teks diantrekan dan disisipkan saat editor dibuat.
     */
    fun insertAtCursor(text: String): String? {
        val tab = active ?: return "Tidak ada tab editor"
        if (tab.loading) return "Editor sedang memuat"
        if (tab.readOnly) return "Tab ini hanya-baca"
        val clean = text.replace("\r\n", "\n").replace('\r', '\n')
        val ed = tab.editor
        if (ed == null) {
            tab.queuedInsert = (tab.queuedInsert ?: "") + clean
            return null
        }
        return try {
            ed.insertText(clean, clean.length)
            null
        } catch (e: Exception) {
            "Gagal menyisipkan teks"
        }
    }

    val active: EditorTab?
        get() = tabs.firstOrNull { it.id == activeId }

    init {
        if (!restoreDrafts()) newTab()
    }

    fun newTab(): EditorTab {
        val tab = EditorTab(nextId++, nextUntitledTitle())
        tab.onEdited = { scheduleDraft(); liveSync(tab) } // [CODE-LSP3]
        tabs.add(tab)
        activeId = tab.id
        scheduleDraft()
        return tab
    }

    /** [CODE-TEMPLATE] Tab scratch bernama Scratch.<ekstensi> (Scratch 2.<ekstensi>, dst. bila sudah dipakai); belum punya berkas. */
    fun newScratch(ext: String): EditorTab {
        val used = tabs.map { it.title }.toSet()
        var n = 1
        while (true) {
            val t = if (n == 1) "Scratch.$ext" else "Scratch $n.$ext"
            if (t !in used) break
            n++
        }
        val title = if (n == 1) "Scratch.$ext" else "Scratch $n.$ext"
        val tab = EditorTab(nextId++, title)
        tab.onEdited = { scheduleDraft(); liveSync(tab) } // [CODE-LSP3]
        tabs.add(tab)
        activeId = tab.id
        scheduleDraft()
        return tab
    }

    /** Nomor terkecil yang belum dipakai tab lain, jadi menutup tab lalu membuka baru tidak menaikkan angka. */
    private fun nextUntitledTitle(): String {
        val used = tabs.map { it.title }.toSet()
        var n = 1
        while (true) {
            val t = if (n == 1) UNTITLED else "$UNTITLED $n"
            if (t !in used) return t
            n++
        }
    }

    /** Buka find bar; seleksi satu baris pendek (maks 64 karakter) menjadi kata cari awal. */
    fun openFind() {
        val ed = active?.editor
        if (ed != null) {
            val c = ed.cursor
            if (c.isSelected && c.right - c.left in 1..64) {
                val sel = ed.text.subSequence(c.left, c.right).toString()
                if ('\n' !in sel) find.query = sel
            }
        }
        find.dialogOpen = true // [FIND-DIALOG] find bar muncul setelah CARI/GANTI ditekan di dialog
    }

    fun select(id: Int) {
        val t = tabs.firstOrNull { it.id == id } ?: return
        activeId = id
        refreshExternal(t) // [CODE-GUARD]
    }

    /** Menutup tab. Selalu menyisakan satu tab kosong. Konfirmasi tab dirty ada di layar. */
    fun close(id: Int) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        live.release(id) // [CODE-LSP3]
        jumps.dropTab(id) // [CODE-BACK]
        backDepth = jumps.size
        if (backDepth == 0) backHint = null
        tabs.removeAt(index).disposeEditor()
        if (tabs.isEmpty()) {
            newTab()
        } else if (activeId == id) {
            activeId = tabs[minOf(index, tabs.size - 1)].id
        }
        scheduleDraft()
    }

    // ---------------------------------------------------------------- Buka berkas (F4)

    /** Tab yang sedang memegang [file] (dibandingkan lewat path kanonik), atau null. */
    internal fun tabFor(file: File): EditorTab? {
        val key = FileStore.keyOf(file)
        return tabs.firstOrNull { t -> t.file?.let { FileStore.keyOf(it) } == key }
    }

    /** True bila ada tab yang berkasnya sama dengan atau berada di bawah [file] (folder). */
    internal fun hasTabUnder(file: File): Boolean {
        val key = FileStore.keyOf(file)
        return tabs.any { t ->
            val k = t.file?.let { FileStore.keyOf(it) } ?: return@any false
            k == key || k.startsWith("$key/")
        }
    }

    /** Folder awal pemilih Buka: folder berkas tab aktif, lalu folder utama panel. */
    internal fun defaultOpenDir(): File = active?.file?.parentFile ?: dir.root

    /** [CODE-PROJECT] Menu "Jadikan folder proyek": pilih folder, lalu buat penanda `.molina` (proyek lokal tanpa Git). */
    internal fun pickProjectFolder() {
        val start = RunReport.findRoot(defaultOpenDir())
        picker = PickerRequest(PickMode.Folder, start, "Pilih folder proyek") { createProjectAt(it) }
    }

    /** [CODE-PROJLOG] Menu "Riwayat proyek": tampilkan log proyek `.molina` yang memuat folder aktif. */
    internal fun openProjectLog() {
        val ri = RunReport.findRootInfo(defaultOpenDir())
        when (ri.how) {
            com.molina.code.guard.ProjectMarker.DIR -> runIo {
                val list = ProjectLog.read(ri.root)
                val pend = ProjectCommit.pending(ri.root) // [CODE-COMMIT] null bila belum pernah commit
                val snaps = ProjectStore.snapshotTimes(ri.root) // [CODE-STORE]
                mainHandler.post { projectLogView = ProjectLogView(ri.root, list, pend, snaps) }
            }
            ".git" -> toast("Folder ini repo Git: riwayatnya memakai Git")
            else -> toast("Belum proyek. Pakai menu Jadikan folder proyek dulu")
        }
    }

    /** [CODE-COMMIT] Commit manual: catat berkas yang berubah sejak commit terakhir di log proyek, lalu segarkan dialog. */
    internal fun commitProject(root: File, message: String) {
        runIo {
            val res = try {
                ProjectCommit.commit(root, message, System.currentTimeMillis())
            } catch (e: Exception) {
                null
            }
            val committed = res?.status == ProjectCommit.Status.COMMITTED
            val list = if (committed) ProjectLog.read(root) else null
            val snaps = if (committed) ProjectStore.snapshotTimes(root) else emptySet() // [CODE-STORE]
            mainHandler.post {
                toast(
                    when (res?.status) {
                        ProjectCommit.Status.COMMITTED -> "Commit dibuat: ${res.entry?.fileCount ?: 0} berkas" +
                            if (res.unsaved > 0) " (${res.unsaved} berkas isinya tidak tersimpan)" else ""
                        ProjectCommit.Status.NOTHING -> "Tidak ada perubahan sejak commit terakhir"
                        ProjectCommit.Status.DISABLED -> "Folder ini bukan proyek .molina"
                        ProjectCommit.Status.TOO_BIG -> "Proyek terlalu besar untuk di-commit"
                        else -> "Gagal membuat commit"
                    }
                )
                if (list != null) projectLogView = ProjectLogView(root, list, 0, snaps)
            }
        }
    }

    /**
     * [CODE-STORE] Kembalikan [path] di proyek [root] ke isinya pada commit [time] (salinan lokal). Tab berkas itu yang
     * masih berisi perubahan belum disimpan menolak pemulihan; tab bersih dimuat ulang setelahnya.
     */
    internal fun restoreProjectFile(root: File, time: Long, path: String) {
        val target = File(root, path)
        if (tabFor(target)?.dirty == true) {
            toast("Simpan atau tutup tab $path dulu")
            return
        }
        runIo {
            val st = try {
                ProjectCommit.restoreFile(root, time, path, System.currentTimeMillis())
            } catch (e: Exception) {
                ProjectStore.RestoreStatus.FAILED
            }
            val ok = st == ProjectStore.RestoreStatus.OK
            val list = if (ok) ProjectLog.read(root) else null
            val pend = if (ok) ProjectCommit.pending(root) else null
            val snaps = if (ok) ProjectStore.snapshotTimes(root) else emptySet()
            mainHandler.post {
                toast(
                    when (st) {
                        ProjectStore.RestoreStatus.OK -> "Dipulihkan: $path"
                        ProjectStore.RestoreStatus.NO_BLOB -> "Isi versi ini tidak tersimpan (berkas besar atau penyimpanan penuh)"
                        ProjectStore.RestoreStatus.NO_SNAPSHOT, ProjectStore.RestoreStatus.NOT_IN_SNAPSHOT -> "Versi ini tidak tersedia lagi"
                        ProjectStore.RestoreStatus.BAD_PATH -> "Path tidak aman, dibatalkan"
                        ProjectStore.RestoreStatus.FAILED -> "Gagal memulihkan $path"
                    }
                )
                if (ok) {
                    tabFor(target)?.let { if (!it.dirty) doReload(it) }
                    if (list != null) projectLogView = ProjectLogView(root, list, pend, snaps)
                }
            }
        }
    }

    /**
     * [CODE-RESTORE] Pulihkan seluruh proyek [root] ke commit [time] ([affected] = path dari pratinjau). Commit pengaman
     * dibuat otomatis lebih dulu. Tab berkas terdampak yang masih berisi perubahan belum disimpan menolak pemulihan;
     * tab bersih dimuat ulang setelahnya.
     */
    internal fun restoreProjectAll(root: File, time: Long, affected: List<String>) {
        affected.firstOrNull { tabFor(File(root, it))?.dirty == true }?.let {
            toast("Simpan atau tutup tab $it dulu")
            return
        }
        runIo {
            val res = try {
                ProjectRestore.run(root, time, System.currentTimeMillis())
            } catch (e: Exception) {
                ProjectRestore.Result(ProjectRestore.Status.SAFETY_FAILED)
            }
            val ok = res.status == ProjectRestore.Status.OK
            val list = ProjectLog.read(root)
            val pend = ProjectCommit.pending(root)
            val snaps = ProjectStore.snapshotTimes(root)
            mainHandler.post {
                toast(
                    when (res.status) {
                        ProjectRestore.Status.OK ->
                            "Proyek dipulihkan: ${res.overwritten} ditimpa, ${res.created} dibuat, ${res.deleted} dihapus" +
                                (if (res.skipped > 0) ", ${res.skipped} dilewati" else "") +
                                (if (res.failed > 0) ", ${res.failed} gagal" else "")
                        ProjectRestore.Status.NO_SNAPSHOT -> "Versi ini tidak tersedia lagi"
                        ProjectRestore.Status.DISABLED -> "Folder ini bukan proyek"
                        ProjectRestore.Status.TOO_BIG -> "Proyek terlalu besar untuk dipulihkan"
                        ProjectRestore.Status.SAFETY_FAILED -> "Gagal membuat commit pengaman; tidak ada berkas yang diubah"
                    }
                )
                if (ok) for (p in affected) tabFor(File(root, p))?.let { if (!it.dirty && File(root, p).isFile) doReload(it) }
                projectLogView = ProjectLogView(root, list, pend, snaps)
            }
        }
    }

    private fun createProjectAt(folder: File) {
        runIo {
            val mark = com.molina.code.guard.ProjectMarker.markerIn(folder)
            val r = if (mark != null) null else com.molina.code.guard.ProjectMarker.create(folder, System.currentTimeMillis())
            mainHandler.post {
                val name = folder.name.ifEmpty { folder.path }
                toast(
                    when {
                        mark != null -> "$name sudah proyek (ditandai $mark)"
                        r == com.molina.code.guard.ProjectMarker.Result.CREATED ->
                            "Proyek dibuat: $name. Laporan perubahan skrip kini memindai folder ini"
                        else -> "Gagal membuat penanda proyek di $name"
                    }
                )
                dir.refresh()
            }
        }
    }

    /**
     * Buka berkas di tab. Berkas yang sudah terbuka hanya dipilih (tidak ada tab ganda). Tab "Baru"
     * yang masih kosong dipakai ulang. Isi dibaca di thread IO; tab dikunci selama dibaca.
     */
    fun openFile(file: File) {
        dir.closePanel()
        val existing = tabFor(file)
        if (existing != null) {
            activeId = existing.id
            return
        }
        val reuse = active?.takeIf { it.isPristine() }
        val tab = reuse ?: newTab()
        tab.file = file
        tab.title = file.name
        if (isLibraryFile(file)) tab.readOnly = true // [CODE-DEBLIB]
        tab.loading = true
        tab.editor?.setEditable(false)
        dir.selected = file.path
        scheduleDraft()
        runIo {
            val r = FileStore.readForOpen(file)
            mainHandler.post {
                if (tabs.none { it === tab }) return@post
                val text = r.text
                if (text == null) {
                    toast(r.error ?: "Gagal membuka ${file.name}")
                    if (reuse != null) {
                        tab.file = null
                        tab.title = nextUntitledTitle()
                        tab.loading = false
                        tab.editor?.setEditable(!tab.readOnly)
                    } else {
                        close(tab.id)
                    }
                } else {
                    tab.applyDiskText(text, r.eol)
                    if (r.hadBom) toast("BOM dibuang saat berkas disimpan")
                }
                scheduleDraft()
            }
        }
    }

    // ---------------------------------------------------------------- Muat ulang (F4)

    /** Muat ulang tab aktif dari disk. Tab dirty meminta konfirmasi dulu (buang perubahan). */
    fun reloadActive() {
        val tab = active ?: return
        if (tab.file == null) {
            toast("Berkas belum disimpan")
            return
        }
        if (tab.loading) return
        if (tab.dirty) reloadConfirm = tab else doReload(tab)
    }

    internal fun cancelReload() {
        reloadConfirm = null
    }

    internal fun doReload(tab: EditorTab) {
        reloadConfirm = null
        val f = tab.file ?: return
        tab.loading = true
        tab.editor?.setEditable(false)
        runIo {
            val r = FileStore.readForOpen(f)
            mainHandler.post {
                if (tabs.none { it === tab }) return@post
                val text = r.text
                if (text == null) {
                    toast(r.error ?: "Gagal memuat ulang ${f.name}")
                    tab.loading = false
                    tab.editor?.setEditable(!tab.readOnly)
                } else {
                    tab.applyDiskText(text, r.eol)
                    toast("Dimuat ulang: ${f.name}")
                }
                scheduleDraft()
            }
        }
    }

    // ---------------------------------------------------------------- Sinkron tab dengan operasi berkas (F4)

    /** Berkas/folder [old] diganti nama jadi [new]: arahkan tab yang menunjuk ke sana. */
    internal fun onRenamed(old: File, new: File) {
        val oldKey = FileStore.keyOf(old)
        for (t in tabs) {
            val f = t.file ?: continue
            val k = FileStore.keyOf(f)
            if (k == oldKey) {
                t.file = new
                t.title = new.name
            } else if (k.startsWith("$oldKey/")) {
                t.file = File(new, k.substring(oldKey.length + 1))
            }
        }
        scheduleDraft()
    }

    /** Berkas/folder [gone] dihapus: tab di dalamnya tetap terbuka sebagai belum tersimpan. */
    internal fun onDeleted(gone: File) {
        val key = FileStore.keyOf(gone)
        var orphaned = 0
        for (t in tabs) {
            val f = t.file ?: continue
            val k = FileStore.keyOf(f)
            if (k == key || k.startsWith("$key/")) {
                t.markOrphaned()
                orphaned++
            }
        }
        if (orphaned > 0) toast("Berkas dihapus dari disk. Tab tetap terbuka (belum disimpan)")
        scheduleDraft()
    }

    // ---------------------------------------------------------------- Simpan (F3e)

    /**
     * Simpan tab. Tab yang sudah punya berkas ditulis langsung; tab baru membuka dialog Simpan
     * sebagai. [after] dipanggil setelah tersimpan dan tanpa edit baru selama penulisan.
     */
    fun save(tab: EditorTab, after: (() -> Unit)? = null) {
        if (tab.loading) return
        val target = tab.file
        if (target == null) {
            saveAs = SaveAsRequest(tab, after)
        } else if (formatOnSave && !tab.readOnly && tab.editor != null && !format.busy) {
            // [CODE-FORMAT] format dulu, lalu tulis apa pun hasilnya
            formatForSave(tab) { note ->
                saveNote = note
                writeTab(tab, target, after)
            }
        } else {
            writeTab(tab, target, after)
        }
    }

    /** Menu titik tiga "Simpan sebagai": selalu membuka dialog untuk tab aktif. */
    fun saveAsActive() {
        val tab = active ?: return
        if (!tab.loading) saveAs = SaveAsRequest(tab, null)
    }

    fun cancelSaveAs() {
        saveAs = null
    }

    /** Folder awal dialog Simpan sebagai: folder berkas tab, lalu folder simpan terakhir, lalu folder utama panel. */
    internal fun defaultSaveDir(tab: EditorTab): File =
        tab.file?.parentFile ?: lastSaveDir ?: dir.root

    internal fun commitSaveAs(req: SaveAsRequest, target: File) {
        saveAs = null
        lastSaveDir = target.parentFile
        writeTab(req.tab, target, req.after)
    }

    private fun writeTab(tab: EditorTab, target: File, after: (() -> Unit)?, force: Boolean = false) {
        if (isLibraryFile(target)) return toast("Berkas pustaka Debian hanya-baca. Gunakan Simpan sebagai ke folder kerja") // [CODE-DEBLIB]
        val text = tab.currentTextOrNull(Int.MAX_VALUE) ?: ""
        val eol = tab.eol
        val formatNote = saveNote // [CODE-FORMAT]
        saveNote = null
        val guard = !force && tab.file == target // [CODE-GUARD]
        val base = tab.baseFp
        runIo {
            if (guard && DiskGuard.blocksSave(diskVerdict(base, target, text).first)) {
                mainHandler.post {
                    tab.staleDisk = true
                    staleConflict = StaleConflict(tab, target, after)
                }
                return@runIo
            }
            val hist = history
            if (hist != null) recordHistoryBefore(hist, target) // [CODE-HISTORY] isi disk sebelum ditimpa
            val err = try {
                FileStore.writeUtf8Atomic(target, FileStore.applyEol(text, eol))
                null
            } catch (e: Exception) {
                e
            }
            if (err == null && hist != null) recordHistorySaved(hist, target, text)
            mainHandler.post {
                if (err != null) {
                    toast("Gagal menyimpan: ${err.message ?: err.javaClass.simpleName}")
                } else {
                    tab.file = target
                    tab.title = target.name
                    // Baseline = isi yang barusan ditulis. Bila pengguna mengetik selama penulisan,
                    // recomputeDirty membiarkan tab tetap dirty dan "after" tidak dijalankan.
                    tab.markSaved(text, eol)
                    dir.selected = target.path
                    dir.refreshParentOf(target)
                    scheduleDraft()
                    toast("Tersimpan: ${target.name}" + (formatNote?.let { " \u00B7 tanpa format: $it" } ?: ""))
                    if (!tab.dirty) {
                        after?.invoke()
                    } else if (after != null) {
                        toast("Ada perubahan baru, tab tidak ditutup")
                    }
                    if (checkOnSave && after == null) checkAfterSave(tab) // [CODE-CHECK-SAVE]
                }
            }
        }
    }

    /** [CODE-HISTORY] Di thread IO: catat isi disk saat ini bila beda dari versi terbaru (menangkap perubahan dari luar). */
    private fun recordHistoryBefore(h: HistoryStore, target: File) {
        try {
            if (!target.isFile || target.length() > HistoryStore.MAX_CHARS * 4L) return
            FileStore.readForOpen(target).text?.let { h.record(target, it, HistoryKind.BEFORE) }
        } catch (e: Exception) {
            // best-effort: riwayat tidak boleh menggagalkan simpan
        }
    }

    private fun recordHistorySaved(h: HistoryStore, target: File, text: String) {
        try {
            h.record(target, text, HistoryKind.SAVED)
            if (!historyTrimmed) {
                historyTrimmed = true
                h.trimGlobal(HistoryStore.MAX_TOTAL_BYTES)
            }
        } catch (e: Exception) {
            // best-effort
        }
    }

    // ---------------------------------------------------------------- [CODE-GUARD] Tab basi dan cek skrip

    // ---------------------------------------------------------------- [CODE-RUNREPORT] Laporan perubahan setelah skrip

    /** Mulai skrip setelah memindai folder proyek (thread IO), supaya perubahannya bisa dilaporkan dan diurungkan. */
    private fun startScriptRun(title: String, body: String, plan: com.molina.code.preview.RunPlan, dir: File?, banner: String) {
        if (run.running || runStarting) return
        runChanges = null
        runSnap = null
        runTitle = title // [CODE-PROJLOG]
        preview.mode = PaneMode.Output
        preview.open = true
        if (dir == null || !dir.isDirectory) {
            run.start(title, body, plan, dir?.path, banner)
            return
        }
        runStarting = true
        runIo {
            val snap = try {
                val ri = RunReport.findRootInfo(dir) // [CODE-SCANROOT]
                RunReport.capture(ri.root, System.currentTimeMillis(), ri.how)
            } catch (e: Exception) {
                null
            }
            mainHandler.post {
                runStarting = false
                runSnap = snap
                if (!run.start(title, body, plan, dir.path, banner)) runSnap = null
            }
        }
    }

    /** Dipanggil di thread utama saat skrip selesai: bandingkan folder dengan pindaian sebelum skrip. */
    private fun collectRunChanges() {
        val snap = runSnap ?: return
        val runTitleNow = runTitle // [CODE-PROJLOG]
        runIo {
            val rep = try {
                RunReport.compare(snap)
            } catch (e: Exception) {
                null
            }
            if (rep != null && !rep.isEmpty) recordScriptHistory(snap, rep)
            if (rep != null && !rep.isEmpty) recordProjectLog(snap, rep, runTitleNow) // [CODE-PROJLOG]
            mainHandler.post {
                if (runSnap === snap && rep != null && !rep.isEmpty) runChanges = RunChanges(snap, rep)
            }
        }
    }

    /** Di thread IO: isi sebelum skrip dicatat di Riwayat ("Sebelum skrip") supaya tetap bisa dipulihkan setelah aplikasi ditutup. */
    private fun recordScriptHistory(snap: RunReport.Snapshot, rep: RunReport.Report) {
        val h = history ?: return
        var n = 0
        for (c in rep.changes) {
            if (n >= MAX_SCRIPT_HISTORY) break
            if (c.kind == RunReport.Kind.ADDED) continue
            val bytes = snap.backups[c.path] ?: continue
            try {
                val text = String(bytes, Charsets.UTF_8)
                if (text.indexOf('\uFFFD') >= 0) continue // bukan UTF-8 valid
                val target = File(snap.root, c.path)
                h.record(target, FileStore.normalizeLf(text), HistoryKind.SCRIPT)
                // [CODE-HISTORY5] isi sesudah skrip jadi versi sendiri: "Perubahan versi ini" = persis apa yang diubah skrip
                if (c.kind == RunReport.Kind.MODIFIED) FileStore.readForOpen(target).text?.let { h.record(target, it, HistoryKind.AFTER_SCRIPT) }
                n++
            } catch (e: Exception) {
                // best-effort
            }
        }
    }

    /** [CODE-PROJLOG] Di thread IO: catat hasil skrip di log proyek (hanya proyek bertanda `.molina`, bukan repo Git). */
    private fun recordProjectLog(snap: RunReport.Snapshot, rep: RunReport.Report, title: String) {
        if (snap.how != com.molina.code.guard.ProjectMarker.DIR) return
        val label = if (title.isBlank()) "Skrip" else "Skrip: $title"
        ProjectLog.append(snap.root, ProjectLog.fromReport(rep, System.currentTimeMillis(), ProjectLog.SRC_SCRIPT, label))
    }

    /** [CODE-PROJLOG] Di thread IO: catat Urungkan di log proyek. */
    private fun recordUndoLog(snap: RunReport.Snapshot, res: RunReport.UndoResult) {
        if (snap.how != com.molina.code.guard.ProjectMarker.DIR) return
        val msg = "Urungkan skrip: ${res.restored} dikembalikan, ${res.removed} dihapus" +
            (if (res.skipped.isNotEmpty()) ", ${res.skipped.size} dilewati" else "")
        ProjectLog.append(snap.root, ProjectLog.note(System.currentTimeMillis(), ProjectLog.SRC_UNDO, msg))
    }

    internal fun dismissRunChanges() {
        runChanges = null
    }

    /** Ketuk berkas di kartu perubahan: buka di editor (berkas yang dihapus tidak bisa dibuka). */
    internal fun openRunChange(path: String) {
        val c = runChanges ?: return
        val f = File(c.snap.root, path)
        if (!f.isFile) {
            toast("Berkas tidak ada lagi: $path")
            return
        }
        preview.open = false
        openFile(f)
    }

    /** [CODE-HISTORY6] Ketuk berkas yang dimodifikasi di kartu perubahan: buka berkasnya, lalu Riwayat pada versi "Sesudah skrip". */
    internal fun openRunChangeDiff(path: String) {
        val c = runChanges ?: return
        val f = File(c.snap.root, path)
        if (!f.isFile) {
            toast("Berkas tidak ada lagi: $path")
            return
        }
        preview.open = false
        openFile(f)
        if (history == null) return
        historyFocus = HistoryKind.AFTER_SCRIPT
        historyOpen = true
    }

    /** Terapkan Urungkan. Berkas yang berubah lagi sejak skrip selesai dilewati, bukan ditimpa. */
    internal fun undoRunConfirm() {
        undoRunOpen = false
        val c = runChanges ?: return
        runIo {
            val res = try {
                RunReport.undo(c.snap, c.report)
            } catch (e: Exception) {
                null
            }
            if (res != null) recordUndoLog(c.snap, res) // [CODE-PROJLOG]
            mainHandler.post {
                if (res == null) {
                    toast("Gagal mengurungkan hasil skrip")
                } else {
                    if (runChanges === c) runChanges = null
                    runSnap = null
                    lastStaleCheck = 0L
                    refreshExternal()
                    val parts = ArrayList<String>()
                    if (res.restored > 0) parts.add("${res.restored} dikembalikan")
                    if (res.removed > 0) parts.add("${res.removed} dihapus")
                    if (res.skipped.isNotEmpty()) parts.add("${res.skipped.size} dilewati")
                    if (res.failed.isNotEmpty()) parts.add("${res.failed.size} gagal")
                    toast(if (parts.isEmpty()) "Tidak ada yang diubah" else "Urungkan: " + parts.joinToString(", "))
                }
            }
        }
    }

    /** [CODE-ROLES] Mode berkas: pemeriksaan skrip dilakukan molina-run di Terminal, bukan di Code. */
    private inline fun checkedByMolinaRun(go: () -> Unit) = go()

    private var lastStaleCheck = 0L

    private fun kindOf(lang: TerminalRunLang): ScriptPrecheck.Kind =
        if (lang == TerminalRunLang.PY) ScriptPrecheck.Kind.PY else ScriptPrecheck.Kind.SH

    /** Periksa [text] sebelum dijalankan. Bersih: [go] langsung. Bermasalah: dialog Tetap jalankan/Batal. */
    private fun guardedRun(title: String, text: String, kind: ScriptPrecheck.Kind, go: (ScriptPrecheck.Report) -> Unit) {
        val rep = ScriptPrecheck.analyze(text, kind)
        if (rep.clean) go(rep) else scriptCheck = ScriptCheckPrompt(title, rep) { go(rep) }
    }

    /** Di thread IO: baca disk dan bandingkan dengan baseline [base]. Teks null bila berkas tak ada atau tak terbaca. */
    private fun diskVerdict(base: Long, target: File, editorText: String?): Pair<DiskVerdict, String?> {
        if (!target.exists()) return DiskVerdict.MISSING to null
        val text = FileStore.readForOpen(target).text ?: return DiskVerdict.UNKNOWN to null
        val v = DiskGuard.verdict(true, true, base, FileStore.fingerprint(text), editorText?.let { FileStore.fingerprint(it) })
        return v to text
    }

    /**
     * Cek tab berkas terhadap disk ([only] = satu tab, null = semua dengan jeda [STALE_CHECK_MS]). Tab bersih yang
     * berkasnya berubah dimuat ulang diam-diam; tab dengan perubahan sendiri ditandai basi (chip "Basi").
     */
    internal fun refreshExternal(only: EditorTab? = null) {
        if (only == null) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastStaleCheck < STALE_CHECK_MS) return
            lastStaleCheck = now
        }
        class Probe(val tab: EditorTab, val file: File, val base: Long)
        val probes = (if (only != null) listOf(only) else tabs.toList())
            .filter { !it.loading }
            .mapNotNull { t -> t.file?.let { f -> Probe(t, f, t.baseFp) } }
        if (probes.isEmpty()) return
        runIo {
            val results = probes.map { it to diskVerdict(it.base, it.file, null).first }
            mainHandler.post {
                for ((p, v) in results) {
                    val t = p.tab
                    if (tabs.none { it === t } || t.loading || t.file != p.file || t.baseFp != p.base) continue
                    if (DiskGuard.autoReload(v, t.dirty)) {
                        toast("Diubah dari luar, dimuat ulang: ${t.title}")
                        doReload(t)
                    } else if (DiskGuard.flagStale(v, t.dirty)) {
                        if (!t.staleDisk) {
                            t.staleDisk = true
                            toast("${t.title} diubah dari luar. Saat simpan Anda diberi pilihan.")
                        }
                    } else if (v == DiskVerdict.SAME) {
                        t.staleDisk = false
                    }
                }
            }
        }
    }

    internal fun staleCancel() {
        staleConflict = null
    }

    /** Timpa: tulis isi editor apa adanya. Isi disk saat ini tetap tercatat di Riwayat ("Sebelum simpan"). */
    internal fun staleOverwrite() {
        val c = staleConflict ?: return
        staleConflict = null
        writeTab(c.tab, c.target, c.after, force = true)
    }

    /** Muat ulang dari disk. Perubahan editor yang belum disimpan dicatat dulu di Riwayat ("Sebelum muat ulang"). */
    internal fun staleReload() {
        val c = staleConflict ?: return
        staleConflict = null
        val t = c.tab
        val mine = if (t.dirty) t.currentTextOrNull(Int.MAX_VALUE) else null
        runIo {
            val h = history
            if (h != null && mine != null) {
                try {
                    h.record(c.target, mine, HistoryKind.RELOAD)
                } catch (e: Exception) {
                    // best-effort
                }
            }
            mainHandler.post { if (tabs.any { it === t }) doReload(t) }
        }
    }

    /** Bandingkan: catat isi disk di Riwayat lalu buka Riwayat (merah = isi disk, hijau = isi editor). */
    internal fun staleCompare() {
        val c = staleConflict ?: return
        staleConflict = null
        val t = c.tab
        if (history == null) { toast("Riwayat tidak tersedia"); return }
        runIo {
            try {
                val disk = FileStore.readForOpen(c.target).text
                if (disk != null) history?.record(c.target, disk, HistoryKind.BEFORE)
            } catch (e: Exception) {
                // best-effort
            }
            mainHandler.post {
                if (tabs.any { it === t }) {
                    activeId = t.id
                    historyOpen = true
                }
            }
        }
    }

    // ---------------------------------------------------------------- Draf (F3e)

    /** Jadwalkan tulis draf 1,5 detik setelah edit terakhir (debounce). */
    internal fun scheduleDraft() {
        if (store == null) return
        mainHandler.removeCallbacks(draftTask)
        mainHandler.postDelayed(draftTask, DRAFT_DELAY_MS)
    }

    private fun persistDrafts() {
        val st = store ?: return
        val snap = snapshot() ?: return
        runIo { st.write(snap) }
    }

    /** Tulis draf sekarang dan tunggu selesai (maks 2 detik). Dipanggil dari Activity.onStop. */
    fun flushDrafts() {
        val st = store ?: return
        mainHandler.removeCallbacks(draftTask)
        val snap = snapshot() ?: return
        try {
            io.submit(Runnable { st.write(snap) }).get(2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // best-effort
        }
    }

    private fun snapshot(): DraftSnapshot? {
        if (store == null) return null
        val entries = tabs.map { tab ->
            val text = if (tab.dirty && !tab.loading) tab.currentTextOrNull(MAX_DRAFT_CHARS) else null
            DraftEntry(
                tab.id, tab.title, tab.file?.path, tab.dirty, tab.wordWrap, tab.readOnly, text,
                tab.eol, tab.baseEol, tab.baseFp
            )
        }
        return DraftSnapshot(activeId, nextId, entries, dir.root.path)
    }

    /** Pulihkan daftar tab dari draf. Isi dibaca di thread IO; tab dikunci (loading) sampai selesai. */
    private fun restoreDrafts(): Boolean {
        val st = store ?: return false
        val snap = st.load() ?: return false
        if (snap.tabs.isEmpty()) return false
        snap.root?.let { dir.restoreRoot(File(it)) }
        for (t in snap.tabs) {
            val tab = EditorTab(t.id, t.title)
            tab.onEdited = { scheduleDraft(); liveSync(tab) } // [CODE-LSP3]
            tab.file = t.path?.let { File(it) }
            tab.wordWrap = t.wrap
            tab.readOnly = t.readOnly
            tab.eol = t.eol
            tab.baseEol = t.baseEol
            tab.baseFp = t.baseFp
            tab.dirty = t.dirty
            tabs.add(tab)
            if (!t.dirty && tab.file == null) continue // tab kosong: tidak ada yang dibaca
            tab.loading = true
            runIo {
                val draft: String? = if (t.dirty) st.readDraft(t.id) else null
                val disk: OpenResult? = if (draft == null) tab.file?.let { FileStore.readForOpen(it) } else null
                mainHandler.post {
                    val diskText = disk?.text
                    if (draft != null) {
                        tab.applyDraft(draft, t.eol, t.baseFp, t.baseEol)
                    } else if (disk != null && diskText != null) {
                        tab.applyDiskText(diskText, disk.eol)
                    } else {
                        toast("Gagal memuat ${tab.title}")
                        close(tab.id)
                    }
                }
            }
        }
        nextId = maxOf(snap.next, (snap.tabs.maxOfOrNull { it.id } ?: 0) + 1)
        activeId = if (tabs.any { it.id == snap.active }) snap.active else tabs.first().id
        return true
    }

    /** Jalankan [block] di thread IO tunggal sesi. Aman dipanggil setelah sesi dilepas (diabaikan). */
    internal fun runIo(block: () -> Unit) {
        try {
            io.execute(block)
        } catch (e: RejectedExecutionException) {
            // sesi sudah dilepas
        }
    }

    /**
     * Tombol Play. Skrip berjalan: hentikan. Markdown/JSON: buka/tutup pratinjau langsung.
     * .sh/.py: jalankan isi editor sekarang (tidak perlu disimpan) di Debian PRoot.
     */
    internal fun playActive() {
        if (run.running) {
            run.stop()
            return
        }
        val tab = active ?: return
        if (tab.loading) return
        when (tab.previewKind()) {
            PreviewKind.MARKDOWN, PreviewKind.JSON, PreviewKind.HTML -> {
                if (preview.open && preview.mode == PaneMode.Preview) {
                    preview.open = false
                } else {
                    preview.invalidate()
                    preview.anchorLine = tab.editor?.cursor?.leftLine ?: -1
                    preview.mode = PaneMode.Preview
                    preview.open = true
                }
            }
            PreviewKind.SCRIPT -> runScript(tab)
            PreviewKind.NONE -> toast("Play mendukung berkas .sh, .py, .md, .json, dan .html")
        }
    }

    /** Menu titik tiga: kirim isi tab aktif ke Script Execution (Settings). Berkas sama = entri diperbarui. */
    internal fun sendToScripts() {
        val sink = scriptSink ?: run { toast("Script Execution belum tersedia"); return }
        val tab = active ?: return
        if (tab.loading) return
        val text = tab.currentTextOrNull(MAX_RUN_CHARS) ?: run { toast("Skrip terlalu besar untuk dikirim"); return }
        if (text.isBlank()) { toast("Editor kosong, tidak ada yang dikirim"); return }
        val title = tab.file?.name ?: tab.title
        val key = tab.scriptKey ?: tab.file?.path ?: "tab:${tab.title}"
        val res = sink.send(ScriptShare(title, key, text))
        toast(res.message)
        // Tab hasil EDIT dari Script Execution: kiriman berhasil = tersimpan, penanda belum-disimpan padam.
        if (res.ok && tab.scriptKey != null && tab.file == null) tab.markSaved(text, tab.eol)
    }

    /**
     * Buka isi skrip dari Script Execution di tab editor (tombol EDIT di Settings). Tab dengan [key] yang sama
     * hanya dipilih; tab kosong yang belum disentuh dipakai ulang. Isi dianggap belum tersimpan sampai
     * dikirim balik lewat menu titik tiga > Kirim ke Script Execution.
     */
    fun openScript(title: String, key: String, text: String) {
        dir.closePanel()
        val existing = tabs.firstOrNull { it.scriptKey == key }
        if (existing != null) {
            activeId = existing.id
            return
        }
        val tab = active?.takeIf { it.isPristine() } ?: newTab()
        tab.title = title
        tab.scriptKey = key
        tab.applyDraft(text.replace("\r\n", "\n").replace('\r', '\n'), Eol.LF, FileStore.EMPTY_FP, Eol.LF)
        activeId = tab.id
        scheduleDraft()
    }

    private fun runScript(tab: EditorTab) {
        val text = tab.currentTextOrNull(MAX_RUN_CHARS)
        if (text == null) {
            toast("Skrip terlalu besar untuk dijalankan")
            return
        }
        val plan = RunPlans.of(tab.file?.name ?: tab.title, tab.firstLineOrNull()) // [CODE-TEMPLATE] scratch
        if (plan == null) {
            toast(if (RunPlans.isGradleScript(tab.file?.name ?: tab.title)) "Skrip Gradle tidak dijalankan di editor; build lewat CI" else "Interpreter skrip tidak dikenal") // [GRADLE-KTS]
            return
        }
        if (run.executor == null) {
            toast("Eksekutor skrip belum terpasang")
            return
        }
        val kind = when { // [CODE-KTS]
            plan.needsPython -> ScriptPrecheck.Kind.PY
            plan.label == "kotlin" -> ScriptPrecheck.Kind.KTS
            else -> ScriptPrecheck.Kind.SH
        }
        guardedRun(tab.title, text, kind) { rep -> // [CODE-GUARD]
            if (rep.body.isBlank()) { toast("Tidak ada kode untuk dijalankan"); return@guardedRun }
            startScriptRun(tab.title, rep.body, plan, tab.file?.parentFile, rep.banner()) // [CODE-RUNREPORT]
        }
    }

    // [CODE-PALETTE] buka berkas:baris dari tautan error di panel Keluaran
    internal fun openLocation(path: String, line: Int, col: Int) {
        val origin = currentJumpPoint() // [CODE-BACK]
        val f = resolveLocation(path)
        val base = path.substringAfterLast('/')
        val tab: EditorTab? = if (f != null) {
            openFile(f)
            tabFor(f)
        } else {
            tabs.firstOrNull { it.file?.name == base || it.title == base }
                ?: tabs.firstOrNull { it.title == run.title }
        }
        if (tab == null) {
            toast("Berkas tidak ditemukan: $path")
            return
        }
        preview.open = false
        activeId = tab.id
        recordJump(origin, tab.id, line - 1) // [CODE-BACK]
        jumpWhenReady(tab, line - 1, col, 40)
    }

    private fun resolveLocation(raw: String): File? {
        var p = raw.removePrefix("file://")
        if (p.startsWith("/sdcard/")) p = "/storage/emulated/0/" + p.removePrefix("/sdcard/")
        val cands = ArrayList<File>()
        if (p.startsWith("/")) {
            cands.add(File(p))
        } else {
            active?.file?.parentFile?.let { cands.add(File(it, p)) }
            cands.add(File(workDir, p))
            cands.add(File(dir.root, p))
        }
        return cands.firstOrNull { it.isFile }
    }

    /** Lompat ke [line] (basis 0) begitu editor tab siap dan teksnya selesai dimuat; mencoba ulang tiap 100 ms. */
    private fun jumpWhenReady(tab: EditorTab, line: Int, col: Int, tries: Int) {
        val ed = tab.editor
        val total = ed?.text?.lineCount ?: 0
        if (ed != null && !tab.loading && total > 0 && (line < total || tries <= 0)) {
            ed.jumpToLineHighlighted(line.coerceIn(0, total - 1), col)
            return
        }
        if (tries <= 0) return
        mainHandler.postDelayed({ jumpWhenReady(tab, line, col, tries - 1) }, 100L)
    }

    fun releaseAll() {
        run.release()
        format.release() // [CODE-FORMAT]
        checker.release() // [CODE-CHECK]
        live.shutdown() // [CODE-LSP3]
        mainHandler.removeCallbacks(draftTask)
        tabs.forEach { it.disposeEditor() }
        io.shutdown()
    }

    companion object {
        const val UNTITLED = "Baru"
        private const val DRAFT_DELAY_MS = 1500L
        private const val MAX_DRAFT_CHARS = 2_000_000
        private const val MAX_RUN_CHARS = 500_000
        private const val STALE_CHECK_MS = 1_500L // [CODE-GUARD]
        private const val MAX_SCRIPT_HISTORY = 50 // [CODE-RUNREPORT]
        private const val MAX_LIVE_CHARS = 300_000
        internal const val LIVE_LABEL = "pylsp"
        private const val DEFINITION_TIMEOUT_MS = 5_000L // [CODE-LSP6] Jedi pertama kali bisa lambat
    }
}

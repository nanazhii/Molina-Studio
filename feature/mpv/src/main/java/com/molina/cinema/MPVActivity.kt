package com.molina.cinema

import com.molina.core.common.PlaylistExtras
import com.molina.core.common.PlaylistPlan
import com.molina.core.common.RollingQueue
import com.molina.core.common.StreamHeaders
import com.molina.core.common.StreamOutcome
import com.molina.core.common.StreamResolvers
import com.molina.core.common.StreamSearchItem
import com.molina.core.common.ShieldProxy
import com.molina.core.common.ShieldStats
import com.molina.core.common.ExternalIntentGate
import com.molina.core.common.UrlPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.molina.cinema.databinding.PlayerBinding
import com.molina.cinema.MPVLib.MpvEvent
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import androidx.appcompat.app.AlertDialog
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.DisplayMetrics
import android.util.Rational
import androidx.core.content.ContextCompat
import android.view.*
import android.view.ViewGroup.MarginLayoutParams
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import java.io.File
import java.lang.IllegalArgumentException
import kotlin.math.roundToInt

typealias StateRestoreCallback = () -> Unit

class MPVActivity : AppCompatActivity(), MPVLib.EventObserver, TouchGesturesObserver {
    // Resolusi stream (NewPipe) jalan di sini; dibatalkan di onDestroy.
    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Path entri yang sedang diputar, disimpan agar lencana Shield tidak memanggil mpv dari thread UI. */
    @Volatile private var shieldPath: String = ""

    /**
     * Muat masukan web: halaman yang dikenali resolver di-resolve dulu (toast saat memuat, toast
     * galat bila gagal); selain itu langsung ke mpv secara sinkron (urutan antrean terjaga).
     */
    private fun loadWeb(raw: String, flag: String, headers: Map<String, String> = emptyMap(),
                        onLoaded: () -> Unit = {}) {
        if (MpvLoad.needsResolveCheck(raw)) { // [DNS-CHECK]
            streamScope.launch {
                val v = withContext(Dispatchers.IO) { MpvLoad.resolveVerdict(raw) }
                if (v is UrlPolicy.Verdict.Blocked) {
                    showToast("Diblokir: ${v.reason.message}", true)
                    return@launch
                }
                MPVLib.command(MpvLoad.loadfileArgs(raw, flag, headers))
                onLoaded()
            }
            return
        }
        if (!StreamLoader.isWebUrl(raw)) {
            MPVLib.command(MpvLoad.loadfileArgs(raw, flag, headers))
            onLoaded()
            return
        }
        streamScope.launch {
            val p = StreamLoader.prepare(raw) { showToast(getString(R.string.stream_resolving), true) }
            when (p) {
                is Prepared.Ready -> {
                    p.title?.let { streamTitles[p.url] = it }
                    streamSources[p.url] = raw
                    MPVLib.command(MpvLoad.loadfileArgs(p.url, flag, headers + p.headers, p.title))
                    appendResolved(p, headers)
                    onLoaded()
                }
                is Prepared.Failed -> showToast(getString(StreamLoader.messageRes(p.error)), true)
            }
        }
    }

    /** Sisa playlist hasil resolver (mis. daftar channel yt-dlp) ditambahkan setelah item pertama. */
    private fun appendResolved(p: Prepared.Ready, headers: Map<String, String>) {
        if (p.entries.size < 2) return
        for (e in p.entries.drop(1)) {
            e.title?.let { streamTitles[e.url] = it }
            MPVLib.command(MpvLoad.loadfileArgs(e.url, MpvLoad.APPEND, headers + p.headers, e.title))
        }
        MpvLog.i(TAG, "Playlist resolver: ${p.entries.size} entri")
    }

    // Playlist hasil pencarian (jendela bergulir): hanya diakses dari main thread.
    private var rolling: RollingQueue? = null

    private var searchJob: kotlinx.coroutines.Job? = null

    /** Antrean dari extra Intent Cinema Home; null bila tidak ada atau tidak valid. */
    private fun playlistFrom(intent: Intent?, current: String): RollingQueue? {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return null
        // runCatching: extra dari aplikasi lain bisa berisi elemen null.
        val plan = runCatching {
            PlaylistPlan.parse(
                intent.getStringArrayListExtra(PlaylistExtras.URLS),
                intent.getStringArrayListExtra(PlaylistExtras.TITLES),
                intent.getIntExtra(PlaylistExtras.INDEX, -1),
                current
            )
        }.getOrNull() ?: return null
        PlaybackLog.onPlaylist(plan.urls.size, plan.index + 1)
        return RollingQueue(plan)
    }

    // URL stream -> judul, untuk dialog playlist (nama berkas stream tidak informatif).
    private val streamTitles = HashMap<String, String>()

    /** URL stream hasil resolve -> tautan asal yang stabil; kunci Penanda posisi. */
    private val streamSources = HashMap<String, String>()

    /** Baris "belum siap" di dialog playlist: sisa hasil pencarian yang belum di-resolve. */
    private fun pendingRows(): List<PendingRow> {
        val q = rolling ?: return emptyList()
        return q.pendingIndices().map { i ->
            PendingRow(i, q.title(i).ifEmpty { "#${i + 1}" })
        }
    }

    /**
     * Pengguna mengetuk item yang belum siap: semua item dari kursor sampai item itu di-resolve
     * berurutan dan ditambahkan (urutan terjaga), lalu pemutaran melompat ke yang terakhir berhasil.
     */
    private fun jumpToPending(planIndex: Int) {
        val q = rolling ?: return
        if (q.busy) {
            showToast(getString(R.string.stream_resolving), true)
            return
        }
        streamScope.launch {
            var target = -1
            var first = true
            while (true) {
                val i = q.beginForced() ?: break
                val p = StreamLoader.prepare(q.url(i)) {
                    if (first) showToast(getString(R.string.stream_resolving), true)
                }
                first = false
                if (rolling !== q) return@launch
                when (p) {
                    is Prepared.Ready -> {
                        val title = p.title ?: q.title(i).ifEmpty { null }
                        title?.let { streamTitles[p.url] = it }
                        streamSources[p.url] = q.url(i)
                        MPVLib.command(MpvLoad.loadfileArgs(p.url, MpvLoad.APPEND, p.headers, title))
                        q.succeeded()
                        PlaybackLog.onPrefetched(i + 1, title.orEmpty())
                        target = (MPVLib.getPropertyInt("playlist-count") ?: 0) - 1
                    }
                    is Prepared.Failed -> {
                        PlaybackLog.onPrefetchSkipped(i + 1, p.error.name)
                        q.failed()
                    }
                }
                if (i >= planIndex) break
            }
            if (target >= 0) MPVLib.setPropertyInt("playlist-pos", target)
        }
    }

    /**
     * Dipanggil saat sebuah item selesai dimuat. Bila antrean mpv tak punya item sesudah posisi
     * kini, item berikutnya di-resolve di latar lalu ditambahkan. Yang gagal dilewati.
     */
    private fun prefetchNext() {
        val q = rolling ?: return
        streamScope.launch {
            val count = MPVLib.getPropertyInt("playlist-count") ?: return@launch
            val pos = MPVLib.getPropertyInt("playlist-pos") ?: return@launch
            var i = q.begin(count, pos) ?: return@launch
            while (true) {
                val p = StreamLoader.prepare(q.url(i))
                if (rolling !== q) return@launch // playlist diganti saat menunggu
                when (p) {
                    is Prepared.Ready -> {
                        val title = p.title ?: q.title(i).ifEmpty { null }
                        title?.let { streamTitles[p.url] = it }
                        streamSources[p.url] = q.url(i)
                        MPVLib.command(MpvLoad.loadfileArgs(p.url, MpvLoad.APPEND, p.headers, title))
                        q.succeeded()
                        PlaybackLog.onPrefetched(i + 1, title.orEmpty())
                        return@launch
                    }
                    is Prepared.Failed -> {
                        PlaybackLog.onPrefetchSkipped(i + 1, p.error.name)
                        if (!q.failed()) return@launch
                        i = q.begin(count, pos) ?: return@launch
                    }
                }
            }
        }
    }

    // for calls to eventUi() and eventPropertyUi()
    private val eventUiHandler = Handler(Looper.getMainLooper())
    // for use with fadeRunnable1..3
    private val fadeHandler = Handler(Looper.getMainLooper())
    // for use with stopServiceRunnable
    private val stopServiceHandler = Handler(Looper.getMainLooper())

    /**
     * DO NOT USE THIS
     */
    private var activityIsStopped = false

    private var activityIsForeground = true
    private var didResumeBackgroundPlayback = false
    private var userIsOperatingSeekbar = false

    private var toast: Toast? = null

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequestCompat? = null
    private var audioFocusRestore: () -> Unit = {}

    private val psc = Utils.PlaybackStateCache()
    private var mediaSession: MediaSessionCompat? = null

    private lateinit var binding: PlayerBinding
    private lateinit var gestures: TouchGestures

    // convenience alias
    private val player get() = binding.player

    private val seekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser)
                return
            kickSpectrum() // [SPECTRUM]
            player.timePos = progress.toDouble() / SEEK_BAR_PRECISION
            updateSeekPreview(progress.toDouble() / SEEK_BAR_PRECISION)
            // Note: don't call updatePlaybackPos() here either
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            userIsOperatingSeekbar = true
            thumbnailer.prepare(localVideoPath())
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            userIsOperatingSeekbar = false
            hideMarkLabel.run()
            hideSeekPreview()
            showControls() // re-trigger display timeout
        }
    }

    private var becomingNoisyReceiverRegistered = false
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS, "noisy")
            }
        }
    }

    // Fade out controls
    private val fadeRunnable = object : Runnable {
        var hasStarted = false
        private val listener = object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) { hasStarted = true }

            override fun onAnimationCancel(animation: Animator) { hasStarted = false }

            override fun onAnimationEnd(animation: Animator) {
                if (hasStarted)
                    hideControls()
                hasStarted = false
            }
        }

        override fun run() {
            binding.topControls.animate().alpha(0f).setDuration(CONTROLS_FADE_DURATION)
            binding.controls.animate().alpha(0f).setDuration(CONTROLS_FADE_DURATION).setListener(listener)
        }
    }

    // Fade out unlock button
    private val fadeRunnable2 = object : Runnable {
        private val listener = object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                binding.unlockBtn.visibility = View.GONE
            }
        }

        override fun run() {
            binding.unlockBtn.animate().alpha(0f).setDuration(CONTROLS_FADE_DURATION).setListener(listener)
        }
    }

    // Fade out gesture text
    private val fadeRunnable3 = object : Runnable {
        // okay this doesn't actually fade...
        override fun run() {
            binding.gestureTextView.visibility = View.GONE
        }
    }

    private val stopServiceRunnable = Runnable {
        val intent = Intent(this, BackgroundPlaybackService::class.java)
        applicationContext.stopService(intent)
    }

    /* Settings */
    private var statsFPS = false
    private var statsLuaMode = 0 // ==0 disabled, >0 page number

    private var backgroundPlayMode = ""
    private var noUIPauseMode = ""

    private var shouldSavePosition = false

    private var autoRotationMode = ""

    private var controlsAtBottom = true
    private var showMediaTitle = false
    private var useTimeRemaining = false

    private var rememberBrightness = false
    private var lastScreenBrightness = -1

    private var ignoreAudioFocus = false
    private var playlistExitWarning = true
    private var newIntentReplace = false

    private var smoothSeekGesture = false
    /* * */

    @SuppressLint("ClickableViewAccessibility")
    private fun initListeners() {
        with (binding) {
            prevBtn.setOnClickListener { playlistPrev() }
            nextBtn.setOnClickListener { playlistNext() }
            cycleAudioBtn.setOnClickListener { cycleAudio() }
            cycleSubsBtn.setOnClickListener { cycleSub() }
            playBtn.setOnClickListener { player.cyclePause() }
            cycleDecoderBtn.setOnClickListener { player.cycleHwdec() }
            cycleSpeedBtn.setOnClickListener { cycleSpeed() }
            abLoopBtn.setOnClickListener { cycleAbLoop() }
            topLockBtn.setOnClickListener { lockUI() }
            shieldBadge.setOnClickListener { showShieldInfo() }
            topPiPBtn.setOnClickListener { goIntoPiP() }
            topMenuBtn.setOnClickListener { openTopMenu() }
            topSearchBtn.setOnClickListener { openSearchUrl() }
            unlockBtn.setOnClickListener { unlockUI() }
            playbackDurationTxt.setOnClickListener {
                useTimeRemaining = !useTimeRemaining
                updatePlaybackPos(psc.positionSec)
                updatePlaybackDuration(psc.durationSec)
            }

            cycleAudioBtn.setOnLongClickListener { pickAudio(); true }
            cycleSpeedBtn.setOnLongClickListener { pickSpeed(); true }
            cycleSubsBtn.setOnLongClickListener { pickSub(); true }
            prevBtn.setOnLongClickListener { openPlaylistMenu(pauseForDialog()); true }
            nextBtn.setOnLongClickListener { openPlaylistMenu(pauseForDialog()); true }
            cycleDecoderBtn.setOnLongClickListener { pickDecoder(); true }

            playbackSeekbar.setOnSeekBarChangeListener(seekBarChangeListener)
            playbackSeekbar.onMarkTap = { sec -> jumpToMark(sec) }
        }

        player.setOnTouchListener { _, e ->
            if (lockedUI) false else gestures.onTouchEvent(e)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.outside) { v, windowInsets ->
            // guidance: https://medium.com/androiddevelopers/gesture-navigation-handling-visual-overlaps-4aed565c134c
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updateLayoutParams<MarginLayoutParams> {
                // avoid system bars and cutout
                leftMargin = insets.left
                topMargin = insets.top
                bottomMargin = insets.bottom
                rightMargin = insets.right
            }
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this) {
            onBackPressedImpl()
        }

        addOnPictureInPictureModeChangedListener { info ->
            onPiPModeChangedImpl(info.isInPictureInPictureMode)
        }
    }

    private var playbackHasStarted = false
    private var onloadCommands = mutableListOf<Array<String>>()

    // Activity lifetime

    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)

        // Do these here and not in MainActivity because mpv can be launched from a file browser
        Utils.copyAssets(this)
        BackgroundPlaybackService.createNotificationChannel(this)

        binding = PlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Init controls to be hidden and view fullscreen
        hideControls()

        // Initialize listeners for the player view
        initListeners()
        streamScope.launch { ShieldStats.blockedCount.collect { updateShieldBadge() } }

        gestures = TouchGestures(this)

        // set up initial UI state
        readSettings()
        onConfigurationChanged(resources.configuration)
        run {
            // edge-to-edge & immersive mode
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
            binding.topPiPBtn.visibility = View.GONE
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN))
            binding.topLockBtn.visibility = View.GONE

        if (showMediaTitle)
            binding.controlsTitleGroup.visibility = View.VISIBLE

        updateOrientation(true)

        // Parse the intent
        val filepath = runCatching { parsePathFromIntent(intent) }.getOrNull()
        if (intent.action == Intent.ACTION_VIEW) {
            // Extra dari aplikasi lain bisa memuat Parcelable rusak: jangan crash
            runCatching {
                parseIntentExtras(intent.extras)
                intentHeaders = parseHeadersExtra(intent.extras)
            }
        }

        if (filepath == null) {
            MpvLog.e(TAG, "No file given, exiting")
            showToast(getString(R.string.error_no_file))
            finishWithResult(RESULT_CANCELED)
            return
        }

        PlaybackLog.attach()
        WatchStats.attach(applicationContext)
        player.addObserver(this)
        rolling = playlistFrom(intent, filepath)
        player.initialize(filesDir.path, cacheDir.path)
        MpvVersionNote.record(applicationContext)
        // Folder jadi playlist: dari pemilih lama (extra) atau dari Cinema Home (ACTION_VIEW)
        val beginPlayback: () -> Unit = {
            val folder = resolveFolderPlaylist(intent, filepath)
            if (folder != null) {
                MpvLog.i(TAG, "Playlist folder: ${folder.first.size} berkas")
                showToast("Playlist folder: ${folder.first.size} berkas", true)
                player.playFolder(folder.first, folder.second)
            } else if (StreamLoader.isWebUrl(filepath)) {
                val startHeaders = intentHeaders
                streamScope.launch {
                    val p = StreamLoader.prepare(filepath) { showToast(getString(R.string.stream_resolving), true) }
                    when (p) {
                        is Prepared.Ready -> {
                            p.title?.let { streamTitles[p.url] = it }
                            streamSources[p.url] = filepath
                            player.playWhenReady(p.url, startHeaders + p.headers, p.title) { appendResolved(p, startHeaders) }
                        }
                        is Prepared.Failed -> {
                            showToast(getString(StreamLoader.messageRes(p.error)), true)
                            finishWithResult(RESULT_CANCELED)
                        }
                    }
                }
            } else {
                player.playFile(filepath, intentHeaders)
            }
        }
        if (needsExternalConfirm(intent, filepath)) {
            confirmExternalPlay(intent, filepath, beginPlayback) { finishWithResult(RESULT_CANCELED) }
        } else beginPlayback()

        mediaSession = initMediaSession()
        updateMediaSession()
        with (BackgroundPlaybackService) {
            mediaToken = mediaSession?.sessionToken
            thumbnailChanged = {
                updateMediaSession()
            }
        }

        updateScreenBrightness()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioSessionId = audioManager!!.generateAudioSessionId()
        if (audioSessionId != AudioManager.ERROR)
            player.setAudioSessionId(audioSessionId)
        else
            MpvLog.w(TAG, "AudioManager.generateAudioSessionId() returned error")

        volumeControlStream = STREAM_TYPE
    }

    private fun finishWithResult(code: Int, includeTimePos: Boolean = false) {
        // Refer to http://mpv-android.github.io/mpv-android/intent.html
        // FIXME: should track end-file events to accurately report OK vs CANCELED
        if (isFinishing) // only count first call
            return
        val result = Intent(RESULT_INTENT)
        result.data = if (intent.data?.scheme == "file") null else intent.data
        if (includeTimePos) {
            result.putExtra("position", psc.position.toInt())
            result.putExtra("duration", psc.duration.toInt())
        }
        setResult(code, result)
        finish()
    }

    override fun onDestroy() {
        thumbnailer.release()
        MpvLog.v(TAG, "Exiting.")

        // Suppress any further callbacks
        activityIsForeground = false
        // Stop events from the mpv thread first (MPVLib.event holds the observer lock, so no
        // callback is in flight once this returns). Otherwise handleAudioFocus() could
        // register the noisy receiver again right after it is unregistered below.
        player.removeObserver(this)
        PlaybackLog.detach()
        WatchStats.flush()
        streamScope.cancel()
        // [SPECTRUM-LEAK] hentikan analisis spektrum (MediaCodec/MediaExtractor) agar tidak jalan setelah Activity hancur
        spectrumAnalyzer?.cancel()
        spectrumAnalyzer = null
        eventUiHandler.removeCallbacksAndMessages(null)
        fadeHandler.removeCallbacksAndMessages(null)
        sleepTimer.cancel()
        stopServiceHandler.removeCallbacksAndMessages(null)

        if (becomingNoisyReceiverRegistered) {
            unregisterReceiver(becomingNoisyReceiver)
            becomingNoisyReceiverRegistered = false
        }

        with (BackgroundPlaybackService) {
            mediaToken = null
            thumbnailChanged = null
        }
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        mediaSession = null

        audioFocusRequest?.let {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager!!, it)
        }
        audioFocusRequest = null

        // take the background service with us
        stopServiceRunnable.run()

        externalDialog?.dismiss()
        player.destroy()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        MpvLog.v(TAG, "onNewIntent($intent)")
        super.onNewIntent(intent)

        // Happens when mpv is still running (not necessarily playing) and the user selects a new
        // file to be played from another app
        val filepath = intent?.let { runCatching { parsePathFromIntent(it) }.getOrNull() }
        if (filepath == null) {
            return
        }
        PlaybackLog.onRequest(filepath)
        val headers = if (intent?.action == Intent.ACTION_VIEW)
            runCatching { parseHeadersExtra(intent?.extras) }.getOrDefault(emptyMap()) else emptyMap()

        val applyIntent: () -> Unit = {
            // Folder dari Cinema selalu mengganti playlist; jalur "tambah di akhir" hanya untuk berkas tunggal
            val folderRequest = intent?.getBooleanExtra("folder_playlist", false) == true
            if (!activityIsForeground && didResumeBackgroundPlayback && !folderRequest) {
                if (this.newIntentReplace) {
                    loadReplacing(intent, filepath, headers)
                    showToast(getString(R.string.notice_file_play))
                } else {
                    loadWeb(filepath, MpvLoad.APPEND, headers) {
                        showToast(getString(R.string.notice_file_appended))
                    }
                }
                moveTaskToBack(true)
            } else {
                loadReplacing(intent, filepath, headers)
            }
        }
        if (intent != null && needsExternalConfirm(intent, filepath)) {
            confirmExternalPlay(intent, filepath, applyIntent) { }
        } else applyIntent()
    }

    // [EXT-CONFIRM] MPVActivity exported + BROWSABLE: intent tanpa token Molina yang meminta jaringan (URL utama,
    // playlist extra, atau subtitle) harus dikonfirmasi dulu. Berkas lokal dari aplikasi lain tidak terpengaruh.
    private var externalDialog: AlertDialog? = null

    private fun externalEntries(i: Intent, filepath: String): List<String?> = try {
        val subs = i.extras?.let { x -> Utils.getParcelableArray<Uri>(x, "subs").map { it.toString() } }.orEmpty()
        listOf(filepath) + i.getStringArrayListExtra(PlaylistExtras.URLS).orEmpty() + subs
    } catch (e: Exception) {
        listOf("tidak-terbaca://") // extra rusak dari luar: anggap jaringan, minta konfirmasi
    }

    private fun needsExternalConfirm(i: Intent, filepath: String): Boolean {
        val trusted = try { ExternalIntentGate.isTrusted(i.getStringExtra(ExternalIntentGate.EXTRA)) } catch (e: Exception) { false }
        return !trusted && ExternalIntentGate.requestsNetwork(externalEntries(i, filepath))
    }

    private fun confirmExternalPlay(i: Intent, filepath: String, onAllow: () -> Unit, onDeny: () -> Unit) {
        val first = ExternalIntentGate.firstRemote(externalEntries(i, filepath))
        val host = ExternalIntentGate.displayHost(first)
        val warn = if (first != null && UrlPolicy.isCleartext(first)) "\n\nPeringatan: sambungan ini TIDAK terenkripsi." else ""
        MpvLog.i(TAG, "Intent luar meminta jaringan${if (host.isEmpty()) "" else ": $host"}, menunggu konfirmasi")
        externalDialog?.dismiss()
        externalDialog = AlertDialog.Builder(this, R.style.AppTheme_PlayerDialog)
            .setTitle("Putar dari aplikasi lain?")
            .setMessage("Aplikasi lain meminta Molina memuat media dari jaringan" +
                (if (host.isEmpty()) "" else ": $host") + "." + warn)
            .setPositiveButton("Putar") { _, _ -> onAllow() }
            .setNegativeButton("Batal") { _, _ -> onDeny() }
            .setOnCancelListener { onDeny() }
            .show()
    }

    /** Folder jadi playlist untuk [filepath] bila diminta (extra folder_playlist, atau ACTION_VIEW + sakelar). */
    private fun resolveFolderPlaylist(intent: Intent?, filepath: String): Pair<List<String>, Int>? {
        val wantFolder = intent?.getBooleanExtra("folder_playlist", false) == true ||
            (intent?.action == Intent.ACTION_VIEW && MpvPrefs.current(this).playFolder)
        if (!wantFolder) return null
        val folder = if (isAllowedCustomPath(filepath)) Utils.folderPlaylist(filepath) else null
        if (folder == null)
            MpvLog.i(TAG, "Playlist folder tidak dipakai (bukan berkas lokal atau folder hanya 1 media)")
        return folder
    }

    /** Ganti isi playlist: seluruh folder bila berlaku, kalau tidak berkas/URL tunggal. */
    private fun loadReplacing(intent: Intent?, filepath: String, headers: Map<String, String>) {
        rolling = playlistFrom(intent, filepath)
        streamTitles.clear()
        val folder = resolveFolderPlaylist(intent, filepath)
        if (folder != null) {
            MpvLog.i(TAG, "Playlist folder: ${folder.first.size} berkas")
            showToast("Playlist folder: ${folder.first.size} berkas", true)
            player.loadFolder(folder.first, folder.second)
        } else {
            loadWeb(filepath, MpvLoad.REPLACE, headers)
        }
    }

    private fun updateAudioPresence() {
        val haveAudio = MPVLib.getPropertyBoolean("current-tracks/audio/selected")
        if (haveAudio == null) {
            // If we *don't know* if there's an active audio track then don't update to avoid
            // spurious UI changes. The property will become available again later.
            return
        }
        isPlayingAudio = (haveAudio && MPVLib.getPropertyBoolean("mute") != true)
    }

    private fun isPlayingAudioOnly(): Boolean {
        if (!isPlayingAudio)
            return false
        val image = MPVLib.getPropertyString("current-tracks/video/image")
        return image.isNullOrEmpty() || image == "yes"
    }

    private fun shouldBackground(): Boolean {
        if (isFinishing) // about to exit?
            return false
        return when (backgroundPlayMode) {
            "always" -> true
            "audio-only" -> isPlayingAudioOnly()
            else -> false // "never"
        }
    }

    override fun onPause() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (isInMultiWindowMode || isInPictureInPictureMode) {
                MpvLog.v(TAG, "Going into multi-window mode")
                super.onPause()
                return
            }
        }

        onPauseImpl()
    }

    private fun tryStartForegroundService(intent: Intent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                ContextCompat.startForegroundService(this, intent)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                MpvLog.w(TAG, e)
                return false
            }
        } else {
            ContextCompat.startForegroundService(this, intent)
        }
        return true
    }

    private fun onPauseImpl() {
        val shouldBackground = shouldBackground()

        activityIsForeground = false
        eventUiHandler.removeCallbacksAndMessages(null)
        if (isFinishing) {
            savePosition()
            recordWatchProgress(shieldPath, psc.position, psc.duration) // [WATCH-PROGRESS]
            // tell mpv to shut down so that any other property changes or such are ignored,
            // preventing useless busywork
            MPVLib.command(arrayOf("stop"))
        } else if (!shouldBackground) {
            player.paused = true
            // clear any old thumbnails
            BackgroundPlaybackService.thumbnail = null
            updateMediaSession()
        }
        writeSettings()
        super.onPause()

        didResumeBackgroundPlayback = shouldBackground
        if (shouldBackground) {
            MpvLog.i(TAG, "Pemutaran dilanjutkan di latar belakang (mode=$backgroundPlayMode)")
            stopServiceHandler.removeCallbacks(stopServiceRunnable)
            val serviceIntent = Intent(this, BackgroundPlaybackService::class.java)
            if (!tryStartForegroundService(serviceIntent)) {
                MpvLog.w(TAG, "Layanan pemutaran latar tidak dapat dimulai; pemutaran dijeda")
                didResumeBackgroundPlayback = false
                player.paused = true
            }
        }
    }

    private fun readSettings() {
        val cfg = MpvPrefs.current(applicationContext)

        gestures.syncSettings(cfg)

        val statsMode = cfg.statsMode
        this.statsFPS = statsMode == "native_fps"
        this.statsLuaMode = if (statsMode.startsWith("lua"))
            statsMode.removePrefix("lua").toIntOrNull() ?: 0
        else
            0
        this.backgroundPlayMode = cfg.backgroundPlay
        this.noUIPauseMode = cfg.noUiPause
        this.shouldSavePosition = cfg.savePosition
        if (this.autoRotationMode != "manual") // don't reset
            this.autoRotationMode = cfg.autoRotation
        this.controlsAtBottom = cfg.bottomControls
        this.showMediaTitle = cfg.displayMediaTitle
        this.useTimeRemaining = cfg.useTimeRemaining
        this.rememberBrightness = cfg.rememberBrightness
        this.lastScreenBrightness = if (this.rememberBrightness)
            cfg.lastScreenBrightness
        else
            -1
        this.ignoreAudioFocus = cfg.ignoreAudioFocus
        this.playlistExitWarning = cfg.playlistExitWarning
        this.newIntentReplace = cfg.newIntentReplace
        this.smoothSeekGesture = cfg.seekGestureSmooth
    }

    private fun writeSettings() {
        MpvPrefs.saveUiState(
            applicationContext, useTimeRemaining, rememberBrightness, lastScreenBrightness
        )
    }

    override fun onStart() {
        super.onStart()
        activityIsStopped = false
    }

    override fun onStop() {
        super.onStop()
        activityIsStopped = true
    }

    override fun onResume() {
        com.molina.core.common.CrashContext.host = com.molina.core.common.CrashContext.HOST_PLAYER
        // If we weren't actually in the background (e.g. multi window mode), don't reinitialize stuff
        if (activityIsForeground) {
            super.onResume()
            return
        }

        if (lockedUI) { // precaution
            MpvLog.w(TAG, "resumed with locked UI, unlocking")
            unlockUI()
        }

        // Init controls to be hidden and view fullscreen
        hideControls()
        readSettings()

        activityIsForeground = true
        // stop background service with a delay
        stopServiceHandler.removeCallbacks(stopServiceRunnable)
        stopServiceHandler.postDelayed(stopServiceRunnable, 1000L)

        refreshUi()

        super.onResume()
    }

    /** [WATCH-PROGRESS] Catat persentase tontonan video YouTube (kunci = tautan asal) untuk garis progres di hasil pencarian. */
    private fun recordWatchProgress(path: String, positionMs: Long, durationMs: Long) {
        val ctx = applicationContext
        runOnUiThread { WatchProgressStore.record(ctx, streamSources[path] ?: path, positionMs, durationMs) }
    }

    private fun savePosition() {
        if (!shouldSavePosition)
            return
        if (MPVLib.getPropertyBoolean("eof-reached") ?: true) {
            MpvLog.d(TAG, "player indicates EOF, not saving watch-later config")
            return
        }
        MPVLib.command(arrayOf("write-watch-later-config"))
    }

    /**
     * Requests or abandons audio focus and noisy receiver depending on the playback state.
     * @warning Call from event thread, not UI thread
     */
    private fun handleAudioFocus() {
        if ((psc.pause && !psc.cachePause) || !isPlayingAudio) {
            if (becomingNoisyReceiverRegistered)
                unregisterReceiver(becomingNoisyReceiver)
            becomingNoisyReceiverRegistered = false
            // TODO: could abandon audio focus after a timeout
        } else {
            if (!becomingNoisyReceiverRegistered)
                registerReceiver(
                    becomingNoisyReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                )
            becomingNoisyReceiverRegistered = true
            // (re-)request audio focus
            // Note that this will actually request focus every time the user unpauses, refer to discussion in #1066
            if (requestAudioFocus()) {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN, "request")
            } else {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS, "request")
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        val req = audioFocusRequest ?:
            with(AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)) {
            setAudioAttributes(with(AudioAttributesCompat.Builder()) {
                // N.B.: libmpv may use different values in ao_audiotrack, but here we always pretend to be music.
                setUsage(AudioAttributesCompat.USAGE_MEDIA)
                setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                build()
            })
            setOnAudioFocusChangeListener {
                onAudioFocusChange(it, "callback")
            }
            build()
        }
        val res = AudioManagerCompat.requestAudioFocus(manager, req)
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = req
            return true
        }
        return false
    }

    // This handles both "real" audio focus changes by the callbacks, which aren't
    // really used anymore after Android 12 (except for AUDIOFOCUS_LOSS),
    // as well as actions equivalent to a focus change that we make up ourselves.
    private fun onAudioFocusChange(type: Int, source: String) {
        AudioLog.focus(type, source, ignoreAudioFocus || isFinishing)
        if (ignoreAudioFocus || isFinishing)
            return
        when (type) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // loss can occur in addition to ducking, so remember the old callback
                val oldRestore = audioFocusRestore
                val wasPlayerPaused = player.paused ?: false
                player.paused = true
                audioFocusRestore = {
                    oldRestore()
                    if (!wasPlayerPaused) player.paused = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                MPVLib.command(arrayOf("multiply", "volume", AUDIO_FOCUS_DUCKING.toString()))
                audioFocusRestore = {
                    val inv = 1f / AUDIO_FOCUS_DUCKING
                    MPVLib.command(arrayOf("multiply", "volume", inv.toString()))
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusRestore()
                audioFocusRestore = {}
            }
        }
    }

    // UI

    /** dpad navigation */
    private var btnSelected = -1

    private var mightWantToToggleControls = false

    /** true if we're actually outputting any audio (includes the mute state, but not pausing) */
    private var isPlayingAudio = false

    private var useAudioUI = false

    private var lockedUI = false

    private fun pauseForDialog(): StateRestoreCallback {
        val useKeepOpen = when (noUIPauseMode) {
            "always" -> true
            "audio-only" -> isPlayingAudioOnly()
            else -> false // "never"
        }
        if (useKeepOpen) {
            // don't pause but set keep-open so mpv doesn't exit while the user is doing stuff
            val oldValue = MPVLib.getPropertyString("keep-open")
            MPVLib.setPropertyBoolean("keep-open", true)
            return {
                oldValue?.also { MPVLib.setPropertyString("keep-open", it) }
            }
        }

        // Pause playback during UI dialogs
        val wasPlayerPaused = player.paused ?: true
        player.paused = true
        return {
            if (!wasPlayerPaused)
                player.paused = false
        }
    }

    private fun updateStats() {
        if (!statsFPS)
            return
        binding.statsTextView.text = getString(R.string.ui_fps, player.estimatedVfFps)
    }

    private fun controlsShouldBeVisible(): Boolean {
        if (lockedUI)
            return false
        // [SPECTRUM] UI audio: potret = kontrol selalu tampil; landscape = auto-hide seperti video
        return (useAudioUI && !isLandscapeNow()) || btnSelected != -1 || userIsOperatingSeekbar
    }

    private fun isLandscapeNow() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private var layoutControlsShown: Boolean? = null

    /** [SPECTRUM] Selaraskan sampul dan spektrum dengan status tampil/sembunyi kontrol (spektrum turun saat kontrol hilang). */
    private fun syncAudioLayout() {
        if (!useAudioUI) { layoutControlsShown = null; return }
        val shown = binding.controls.visibility == View.VISIBLE
        if (layoutControlsShown == shown) return
        layoutControlsShown = shown
        showNowPlayingArt(lastCover)
    }

    /** Make controls visible, also controls the timeout until they fade. */
    private fun showControls() {
        updateAbLoopButton()
        if (lockedUI) {
            MpvLog.w(TAG, "cannot show UI in locked mode")
            return
        }

        // remove all callbacks that were to be run for fading
        fadeHandler.removeCallbacks(fadeRunnable)
        binding.controls.animate().cancel()
        binding.topControls.animate().cancel()

        // reset controls alpha to be visible
        binding.controls.alpha = 1f
        binding.topControls.alpha = 1f

        if (binding.controls.visibility != View.VISIBLE) {
            binding.controls.visibility = View.VISIBLE
            binding.topControls.visibility = View.VISIBLE
            binding.controls.doOnNextLayout { syncAudioLayout() } // [SPECTRUM] tinggi kontrol baru valid setelah layout

            if (this.statsFPS) {
                updateStats()
                binding.statsTextView.visibility = View.VISIBLE
            }

            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.show(WindowInsetsCompat.Type.navigationBars())
        }

        // add a new callback to hide the controls once again
        if (!controlsShouldBeVisible())
            fadeHandler.postDelayed(fadeRunnable, CONTROLS_DISPLAY_TIMEOUT)
    }

    /** Hide controls instantly */
    fun hideControls() {
        if (controlsShouldBeVisible())
            return
        // use GONE here instead of INVISIBLE (which makes more sense) because of Android bug with surface views
        // see http://stackoverflow.com/a/12655713/2606891
        binding.controls.visibility = View.GONE
        binding.topControls.visibility = View.GONE
        binding.statsTextView.visibility = View.GONE

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        syncAudioLayout() // [SPECTRUM]
    }

    /** Start fading out the controls */
    private fun hideControlsFade() {
        fadeHandler.removeCallbacks(fadeRunnable)
        fadeHandler.post(fadeRunnable)
    }

    /**
     * Toggle visibility of controls (if allowed)
     * @return future visibility state
     */
    private fun toggleControls(): Boolean {
        if (lockedUI)
            return false
        if (controlsShouldBeVisible())
            return true
        return if (binding.controls.visibility == View.VISIBLE && !fadeRunnable.hasStarted) {
            hideControlsFade()
            false
        } else {
            showControls()
            true
        }
    }

    private fun showUnlockControls() {
        fadeHandler.removeCallbacks(fadeRunnable2)
        binding.unlockBtn.animate().setListener(null).cancel()

        binding.unlockBtn.alpha = 1f
        binding.unlockBtn.visibility = View.VISIBLE

        fadeHandler.postDelayed(fadeRunnable2, CONTROLS_DISPLAY_TIMEOUT)
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (lockedUI) {
            showUnlockControls()
            return super.dispatchKeyEvent(ev)
        }

        // try built-in event handler first, forward all other events to libmpv
        val handled = interceptDpad(ev) ||
                (ev.action == KeyEvent.ACTION_DOWN && interceptKeyDown(ev)) ||
                player.onKey(ev)
        if (handled) {
            return true
        }
        return super.dispatchKeyEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        if (lockedUI)
            return super.dispatchGenericMotionEvent(ev)

        if (ev != null && ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (player.onPointerEvent(ev))
                return true
            // keep controls visible when mouse moves
            if (ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE)
                showControls()
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (lockedUI) {
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_DOWN)
                showUnlockControls()
            return super.dispatchTouchEvent(ev)
        }

        if (super.dispatchTouchEvent(ev)) {
            // reset delay if the event has been handled
            // ideally we'd want to know if the event was delivered to controls, but we can't
            if (binding.controls.visibility == View.VISIBLE && !fadeRunnable.hasStarted)
                showControls()
            if (ev.action == MotionEvent.ACTION_UP) {
                // [SPECTRUM] mode audio landscape: ketukan non-gestur tetap memunculkan kontrol
                if (useAudioUI && mightWantToToggleControls && binding.controls.visibility != View.VISIBLE)
                    showControls()
                return true
            }
        }
        if (ev.action == MotionEvent.ACTION_DOWN)
            mightWantToToggleControls = true
        if (ev.action == MotionEvent.ACTION_UP && mightWantToToggleControls) {
            toggleControls()
        }
        return true
    }

    /**
     * Returns views eligible for dpad button navigation
     */
    private fun dpadButtons(): Sequence<View> {
        val groups = arrayOf(binding.controlsButtonGroup, binding.topControls)
        return sequence {
            for (g in groups) {
                for (i in 0 until g.childCount) {
                    val view = g.getChildAt(i)
                    if (view.isEnabled && view.isVisible && view.isFocusable)
                        yield(view)
                }
            }
        }
    }

    private fun interceptDpad(ev: KeyEvent): Boolean {
        if (btnSelected == -1) { // UP and DOWN are always grabbed and overridden
            when (ev.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (ev.action == KeyEvent.ACTION_DOWN) { // activate dpad navigation
                        btnSelected = 0
                        updateSelectedDpadButton()
                        showControls()
                    }
                    return true
                }
            }
            return false
        }

        // this runs when dpad navigation is active:
        when (ev.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (ev.action == KeyEvent.ACTION_DOWN) { // deactivate dpad navigation
                    btnSelected = -1
                    updateSelectedDpadButton()
                    hideControlsFade()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    btnSelected = (btnSelected + 1) % dpadButtons().count()
                    updateSelectedDpadButton()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    val count = dpadButtons().count()
                    btnSelected = (count + btnSelected - 1) % count
                    updateSelectedDpadButton()
                }
                return true
            }
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (ev.action == KeyEvent.ACTION_UP) {
                    val view = dpadButtons().elementAtOrNull(btnSelected)
                    // 500ms appears to be the standard
                    if (ev.eventTime - ev.downTime > 500L)
                        view?.performLongClick()
                    else
                        view?.performClick()
                }
                return true
            }
        }
        return false
    }

    private fun updateSelectedDpadButton() {
        val colorFocused = ContextCompat.getColor(this, R.color.tint_btn_bg_focused)
        val colorNoFocus = ContextCompat.getColor(this, R.color.tint_btn_bg_nofocus)

        dpadButtons().forEachIndexed { i, child ->
            if (i == btnSelected)
                child.setBackgroundColor(colorFocused)
            else
                child.setBackgroundColor(colorNoFocus)
        }
    }

    private fun interceptKeyDown(event: KeyEvent): Boolean {
        // intercept some keys to provide functionality "native" to
        // mpv-android even if libmpv already implements these
        var unhandled = 0

        when (event.unicodeChar.toChar()) {
            // (overrides a default binding)
            'j' -> cycleSub()
            '#' -> cycleAudio()

            else -> unhandled++
        }
        // Note: dpad center is bound according to how Android TV apps should generally behave,
        // see <https://developer.android.com/docs/quality-guidelines/tv-app-quality>.
        // Due to implementation inconsistencies enter and numpad enter need to perform the same
        // function (issue #963).
        when (event.keyCode) {
            // (no default binding)
            KeyEvent.KEYCODE_CAPTIONS -> cycleSub()
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> cycleAudio()
            KeyEvent.KEYCODE_INFO -> toggleControls()
            KeyEvent.KEYCODE_MENU -> openTopMenu()
            KeyEvent.KEYCODE_GUIDE -> openTopMenu()
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> player.cyclePause()

            // (overrides a default binding)
            KeyEvent.KEYCODE_ENTER -> player.cyclePause()

            else -> unhandled++
        }

        return unhandled < 2
    }

    private fun onBackPressedImpl() {
        if (lockedUI)
            return showUnlockControls()

        val notYetPlayed = psc.playlistCount - psc.playlistPos - 1
        if (notYetPlayed <= 0 || !playlistExitWarning) {
            finishWithResult(RESULT_OK, true)
            return
        }

        val restore = pauseForDialog()
        with (AlertDialog.Builder(this, R.style.AppTheme_PlayerDialog)) {
            setMessage(getString(R.string.exit_warning_playlist, notYetPlayed))
            setPositiveButton(R.string.dialog_yes) { dialog, _ ->
                dialog.dismiss()
                finishWithResult(RESULT_OK, true)
            }
            setNegativeButton(R.string.dialog_no) { dialog, _ ->
                dialog.dismiss()
                restore()
            }
            create().show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = windowManager.currentWindowMetrics
            gestures.setMetrics(wm.bounds.width().toFloat(), wm.bounds.height().toFloat())
        } else @Suppress("DEPRECATION") {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            gestures.setMetrics(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
        }

        // Adjust control margins
        binding.controls.updateLayoutParams<MarginLayoutParams> {
            bottomMargin = if (!controlsAtBottom) {
                Utils.convertDp(this@MPVActivity, 60f)
            } else {
                0
            }
            leftMargin = if (!controlsAtBottom) {
                Utils.convertDp(this@MPVActivity, if (isLandscape) 60f else 24f)
            } else {
                0
            }
            rightMargin = leftMargin
        }

        if (useAudioUI) { // [SPECTRUM] rotasi: tampilkan kontrol sebentar (landscape lalu auto-hide) dan hitung ulang tata letak
            showControls()
            binding.controls.doOnNextLayout { layoutControlsShown = null; syncAudioLayout() }
        }
    }

    private fun onPiPModeChangedImpl(state: Boolean) {
        MpvLog.v(TAG, "onPiPModeChanged($state)")
        if (state) {
            lockedUI = true
            hideControls()
            return
        }

        unlockUI()
        // For whatever stupid reason Android provides no good detection for when PiP is exited
        // so we have to do this shit <https://stackoverflow.com/questions/43174507/#answer-56127742>
        // If we don't exit the activity here it will stick around and not be retrievable from the
        // recents screen, or react to onNewIntent().
        if (activityIsStopped) {
            // Note: On Android 12 or older there's another bug with this: the result will not
            // be delivered to the calling activity and is instead instantly returned the next
            // time, which makes it looks like the file picker is broken.
            finishWithResult(RESULT_OK, true)
        }
    }

    private fun playlistPrev() = PlaylistNav.prev() // [PLAYLIST-WRAP]
    private fun playlistNext() = PlaylistNav.next()

    /** Lencana Molina Shield: gembok (https), peringatan (http/rtmp/rtsp), perisai (lokal) + jumlah blokir. */
    private fun updateShieldBadge() {
        val p = shieldPath
        val icon = when {
            UrlPolicy.scheme(p) == "https" -> "\uD83D\uDD12"
            UrlPolicy.isCleartext(p) -> "\u26A0"
            else -> "\uD83D\uDEE1"
        }
        val n = ShieldStats.blockedCount.value
        binding.shieldBadge.text = if (n > 0) "$icon $n" else icon
    }

    private fun showShieldInfo() {
        val p = shieldPath
        val line = when {
            UrlPolicy.scheme(p) == "https" -> "Terenkripsi (https)."
            UrlPolicy.isCleartext(p) -> "TIDAK terenkripsi: isinya bisa dibaca atau diubah di jaringan."
            else -> "Sumber lokal."
        }
        val shield = if (ShieldProxy.proxyUrl() != null) "Molina Shield aktif" else "Molina Shield mati"
        showToast("$line $shield. Diblokir: ${ShieldStats.blockedCount.value}", true)
    }

    private fun showToast(msg: String, cancel: Boolean = false) {
        if (cancel)
            toast?.cancel()
        toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).apply {
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 0)
            show()
        }
    }

    // Intent/Uri parsing

    private fun parsePathFromIntent(intent: Intent): String? {
        fun safeResolveUri(u: Uri?): String? {
            return if (u != null && u.isHierarchical && !u.isRelative)
                resolveUri(u)
            else null
        }

        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                // Normal file open or URL view
                intent.data?.let { resolveUri(it) }
            }

            Intent.ACTION_SEND -> {
                // Handle single shared file or text link
                var parsed = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (parsed == null) {
                    parsed = intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                        Uri.parse(it.trim())
                    }
                }

                safeResolveUri(parsed)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                // Multiple shared files
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (!uris.isNullOrEmpty()) {
                    val paths = uris.mapNotNull { uri ->
                        safeResolveUri(uri)
                    }
                    if (paths.size == 1) {
                        return paths[0]
                    } else if (!paths.isEmpty()) {
                        // Use a memory playlist
                        val memoryUri = Utils.makeMemoryPlaylist(paths)
                        MpvLog.v(TAG, "Created memory playlist URI (${paths.size})")
                        return memoryUri
                    }
                }
                return null
            }

            else -> {
                // Intent kustom dari Molina Cinema (extra filepath)
                intent.getStringExtra("filepath")?.takeIf { isAllowedCustomPath(it) }
            }
        }
    }

    // MPVActivity bersifat exported, jadi aplikasi lain bisa mengirim extra "filepath".
    // Tolak skema tak dikenal dan path di dalam data privat aplikasi.
    private fun isAllowedCustomPath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("memory://", ignoreCase = true))
            return isAllowedMemoryPlaylist(path)
        return isAllowedEntry(path)
    }

    // Playlist memory:// hanya diterima bila setiap barisnya lolos validasi yang sama
    // dengan entri tunggal. Skema memory sendiri ditolak di dalam isi (cegah rekursi).
    private fun isAllowedMemoryPlaylist(uri: String): Boolean {
        val header = "memory://#EXTM3U"
        if (!uri.startsWith(header, ignoreCase = true)) return false
        val entries = uri.substring(header.length)
            .split('\n')
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (entries.isEmpty() || entries.size > MAX_MEMORY_PLAYLIST_ENTRIES) return false
        return entries.all { isAllowedEntry(it) }
    }

    private fun isAllowedEntry(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.contains("://")) {
            val scheme = path.substringBefore("://").lowercase()
            if (scheme !in ALLOWED_CUSTOM_SCHEMES) return false
            if (scheme == "file") return isOutsidePrivateData(Uri.parse(path).path)
            return UrlPolicy.check(path) is UrlPolicy.Verdict.Allowed
        }
        return isOutsidePrivateData(path)
    }

    // Path di dalam data privat aplikasi (termasuk lewat symlink) tidak boleh dibuka dari luar.
    private fun isOutsidePrivateData(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return try {
            val real = File(path).canonicalPath
            val privateRoot = File(applicationInfo.dataDir).canonicalPath
            !(real == privateRoot || real.startsWith("$privateRoot/"))
        } catch (e: Exception) {
            false
        }
    }

    private fun resolveUri(data: Uri): String? {
        val filepath = when (data.scheme) {
            "file" -> data.path?.takeIf { isOutsidePrivateData(it) }
            "content" -> translateContentUri(data)
            // Skema lain (data, tcp, udp, ftp, mms, rtp) ditolak: tidak lolos UrlPolicy dan tidak dipakai.
            "http", "https", "rtmp", "rtmps", "rtsp" -> data.toString()
            else -> null
        }

        if (filepath == null)
            MpvLog.e(TAG, "unknown scheme: ${data.scheme}")
        return filepath
    }

    private fun translateContentUri(uri: Uri): String {
        val resolver = applicationContext.contentResolver
        MpvLog.v(TAG, "Resolving content URI: $uri")
        try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                // See if we can skip the indirection and read the real file directly
                val path = Utils.findRealPath(pfd.fd)
                // [EXT-CONFIRM] path nyata di data privat Molina tidak boleh dibuka langsung (sama seperti file://)
                if (path != null && isOutsidePrivateData(path)) {
                    MpvLog.v(TAG, "Found real file path: $path")
                    return path
                }
            }
        } catch(e: Exception) {
            MpvLog.e(TAG, "Failed to open content fd: $e")
        }

        // Otherwise, just let mpv open the content URI directly via ffmpeg
        return uri.toString()
    }

    private fun parseIntentExtras(extras: Bundle?) {
        onloadCommands.clear()
        if (extras == null)
            return

        fun pushOption(key: String, value: String) {
            onloadCommands.add(arrayOf("set", "file-local-options/${key}", value))
        }

        // Refer to http://mpv-android.github.io/mpv-android/intent.html
        // Note: these only apply to the first file, it's not clear what the semantics for a
        // playlist should be.

        if (extras.getByte("decode_mode") == 2.toByte())
            pushOption("hwdec", "no")
        if (extras.containsKey("subs")) {
            val subList = Utils.getParcelableArray<Uri>(extras, "subs")
            val subsToEnable = Utils.getParcelableArray<Uri>(extras, "subs.enable")

            for (suburi in subList) {
                val subfile = resolveUri(suburi) ?: continue
                // MPVActivity exported: URL subtitle dari aplikasi lain tidak boleh melewati kebijakan URL.
                if (UrlPolicy.check(subfile) is UrlPolicy.Verdict.Blocked) {
                    MpvLog.i(TAG, "Subtitle dari intent ditolak kebijakan URL")
                    continue
                }
                val flag = if (subsToEnable.any { it == suburi }) "select" else "auto"

                MpvLog.v(TAG, "Adding subtitles from intent extras: $subfile")
                onloadCommands.add(arrayOf("sub-add", subfile, flag))
            }
        }
        extras.getInt("position", 0).let {
            if (it > 0)
                pushOption("start", "${it / 1000f}")
        }
        extras.getString("title", "").let {
            if (!it.isNullOrEmpty())
                pushOption("force-media-title", it.filter { c -> c >= ' ' && c != '\u007f' }.take(200))
        }
        // `headers` ditangani parseHeadersExtra (opsi loadfile per-entri). `tls_verify` sengaja
        // tidak didukung: extra dari aplikasi lain tidak boleh melemahkan verifikasi TLS.
    }

    // Header HTTP dari extra intent: String[] berpasangan [nama1, nilai1, nama2, nilai2, ...]
    // (konvensi pemutar Android lain). Disaring StreamHeaders; hanya jumlah yang dicatat, bukan isinya.
    private fun parseHeadersExtra(extras: Bundle?): Map<String, String> {
        val arr = extras?.getStringArray("headers") ?: return emptyMap()
        val raw = LinkedHashMap<String, String>()
        var i = 0
        while (i + 1 < arr.size && i < 64) {
            raw[arr[i] ?: ""] = arr[i + 1] ?: ""
            i += 2
        }
        val clean = StreamHeaders.sanitize(raw)
        MpvLog.i(TAG, "Header dari intent: ${clean.size} dipakai, ${raw.size - clean.size} ditolak")
        return clean
    }

    // UI (Part 2)

    data class TrackData(val trackId: Int, val trackType: String)
    private fun trackSwitchNotification(f: () -> TrackData) {
        val (track_id, track_type) = f()
        val trackPrefix = when (track_type) {
            "audio" -> getString(R.string.track_audio)
            "sub"   -> getString(R.string.track_subs)
            "video" -> "Video"
            else    -> "???"
        }

        val msg = if (track_id == -1) {
            "$trackPrefix ${getString(R.string.track_off)}"
        } else {
            val trackName = player.tracks[track_type]?.firstOrNull{ it.mpvId == track_id }?.name ?: "???"
            "$trackPrefix $trackName"
        }
        showToast(msg, true)
    }

    private fun cycleAudio() = trackSwitchNotification {
        player.cycleAudio(); TrackData(player.aid, "audio")
    }
    private fun cycleSub() = trackSwitchNotification {
        player.cycleSub(); TrackData(player.sid, "sub")
    }

    private fun selectTrack(type: String, get: () -> Int, set: (Int) -> Unit) {
        val tracks = player.tracks.getValue(type)
        val selectedMpvId = get()
        val selectedIndex = tracks.indexOfFirst { it.mpvId == selectedMpvId }
        val restore = pauseForDialog()

        with (AlertDialog.Builder(this, R.style.AppTheme_PlayerDialog)) {
            setSingleChoiceItems(tracks.map { it.name }.toTypedArray(), selectedIndex) { dialog, item ->
                val trackId = tracks[item].mpvId

                set(trackId)
                dialog.dismiss()
                trackSwitchNotification { TrackData(trackId, type) }
            }
            setOnDismissListener { restore() }
            create().show()
        }
    }

    private fun pickAudio() = selectTrack("audio", { player.aid }, { player.aid = it })

    private fun pickSub() {
        val restore = pauseForDialog()
        val impl = SubTrackDialog(player)
        lateinit var dialog: AlertDialog
        impl.listener = { it, secondary ->
            if (secondary)
                player.secondarySid = it.mpvId
            else
                player.sid = it.mpvId
            dialog.dismiss()
            trackSwitchNotification { TrackData(it.mpvId, SubTrackDialog.TRACK_TYPE) }
        }

        dialog = with (AlertDialog.Builder(this, R.style.AppTheme_PlayerDialog)) {
            val inflater = LayoutInflater.from(context)
            setView(impl.buildView(inflater))
            setOnDismissListener { restore() }
            create()
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        dialog.dockTo(android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL, 380, 8)
    }

    /** Ikon cari/URL di player: putar sekarang (replace) atau tambah ke antrean (append), tanpa keluar dari player. */
    private fun openSearchUrl() {
        val restoreState = pauseForDialog()
        var playNow = false
        val helper = Utils.OpenUrlDialog(this, R.style.AppTheme_PlayerDialog, allowQuery = true)
        with (helper) {
            builder.setPositiveButton(R.string.search_url_play) { _, _ ->
                if (StreamLoader.isQuery(helper.text)) {
                    searchAndShow(helper.text, append = false)
                } else {
                    playNow = true
                    PlaybackLog.onRequest(helper.text)
                    loadWeb(helper.text, MpvLoad.REPLACE)
                }
            }
            builder.setNeutralButton(R.string.search_url_queue) { _, _ ->
                if (StreamLoader.isQuery(helper.text)) {
                    searchAndShow(helper.text, append = true)
                } else {
                    PlaybackLog.onRequest(helper.text)
                    loadWeb(helper.text, MpvLoad.APPEND) {
                        showToast(getString(R.string.notice_file_appended), true)
                    }
                }
            }
            builder.setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
            builder.setOnDismissListener {
                restoreState()
                if (playNow)
                    player.paused = false
            }
            create().show()
        }
    }

    /** Cari judul lewat resolver (sampai [PlaylistPlan.MAX_ITEMS] hasil) lalu tampilkan daftar pilihan. */
    private fun searchAndShow(query: String, append: Boolean) {
        searchJob?.cancel()
        showToast(getString(R.string.search_searching), true)
        searchJob = streamScope.launch {
            val outcome = kotlinx.coroutines.withContext(Dispatchers.IO) {
                StreamResolvers.get()?.search(query, PlaylistPlan.MAX_ITEMS)
            }
            when (outcome) {
                null -> showToast(getString(R.string.search_unavailable), true)
                is StreamOutcome.Failure -> showToast(getString(StreamLoader.messageRes(outcome.error)), true)
                is StreamOutcome.Success ->
                    if (outcome.value.isEmpty()) showToast(getString(R.string.search_no_results, query), true)
                    else showSearchResults(outcome.value, append)
            }
        }
    }

    private fun searchMeta(item: StreamSearchItem): String {
        val parts = ArrayList<String>(2)
        item.uploader?.let { parts.add(it) }
        item.durationSec?.let { s ->
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            parts.add(
                if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                else String.format(java.util.Locale.ROOT, "%d:%02d", m, sec)
            )
        }
        com.molina.core.common.RelativeTime.label(item)?.let { parts.add(it) }
        return parts.joinToString(" \u00B7 ")
    }

    /**
     * Daftar hasil (maks. 50). Mode putar: ketuk = putar sekarang dan hasil lain jadi antrean bergulir
     * (sama seperti Cinema Home). Mode antrean: ketuk = tambahkan satu item, daftar tetap terbuka.
     */
    private fun showSearchResults(items: List<StreamSearchItem>, append: Boolean) {
        val restore = pauseForDialog()
        var chosen = false
        lateinit var dialog: AlertDialog
        val impl = SearchResultsDialog(items, { searchMeta(it) }, onPick = { pos ->
            val picked = items[pos]
            if (append) {
                PlaybackLog.onRequest(picked.pageUrl)
                loadWeb(picked.pageUrl, MpvLoad.APPEND) {
                    showToast(getString(R.string.search_added, picked.title), true)
                }
            } else {
                chosen = true
                dialog.dismiss()
                playSearchResult(items, pos)
            }
        }, onClose = { dialog.dismiss() })
        val builder = AlertDialog.Builder(this, R.style.AppTheme_PlayerDialog)
        builder.setView(impl.buildView(LayoutInflater.from(builder.context)))
        builder.setOnDismissListener {
            restore()
            if (chosen) player.paused = false
        }
        dialog = builder.create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }

    /** Putar hasil yang diketuk; hasil tonton YouTube lainnya (maks. 50) menjadi antrean bergulir. */
    private fun playSearchResult(items: List<StreamSearchItem>, pos: Int) {
        val picked = items[pos]
        val valid = items.filter { PlaylistPlan.isWatchUrl(it.pageUrl) }
            .distinctBy { it.pageUrl }
            .take(PlaylistPlan.MAX_ITEMS)
        val idx = valid.indexOfFirst { it.pageUrl == picked.pageUrl }
        val plan = if (idx >= 0) {
            runCatching {
                PlaylistPlan.parse(valid.map { it.pageUrl }, valid.map { it.title }, idx, picked.pageUrl)
            }.getOrNull()
        } else null
        rolling = plan?.let { RollingQueue(it) }
        plan?.let { PlaybackLog.onPlaylist(it.urls.size, it.index + 1) }
        PlaybackLog.onRequest(picked.pageUrl)
        loadWeb(picked.pageUrl, MpvLoad.REPLACE)
    }

    private fun openPlaylistMenu(restore: StateRestoreCallback) {
        val impl = PlaylistDialog(player, { streamTitles }, { pendingRows() })
        lateinit var dialog: AlertDialog

        impl.listeners = object : PlaylistDialog.Listeners {
            override fun openUrl() {
                val helper = Utils.OpenUrlDialog(this@MPVActivity)
                with (helper) {
                    builder.setPositiveButton(R.string.dialog_ok) { _, _ ->
                        loadWeb(helper.text, MpvLoad.APPEND) { impl.refresh() }
                    }
                    builder.setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
                    create().show()
                }
            }

            override fun onItemPicked(item: MPVView.PlaylistItem) {
                MPVLib.setPropertyInt("playlist-pos", item.index)
                dialog.dismiss()
            }

            override fun onPendingPicked(planIndex: Int) {
                dialog.dismiss()
                jumpToPending(planIndex)
            }

            override fun onExit() = dialog.dismiss()

            override fun onMenu() {
                dialog.dismiss()
                openTopMenu()
            }
        }

        dialog = with (AlertDialog.Builder(this, R.style.AppTheme_PlayerDialog)) {
            val inflater = LayoutInflater.from(context)
            setView(impl.buildView(inflater))
            setOnDismissListener { restore() }
            create()
        }
        dialog.show()
    }

    private fun pickDecoder() {
        val restore = pauseForDialog()

        val items = mutableListOf(
            Pair("HW (mediacodec-copy)", "mediacodec-copy"),
            Pair("SW", "no")
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            items.add(0, Pair("HW+ (mediacodec)", "mediacodec"))
        val hwdecActive = player.hwdecActive
        val selectedIndex = items.indexOfFirst { it.second == hwdecActive }
        with (AlertDialog.Builder(this, R.style.AppTheme_PlayerDialog)) {
            setSingleChoiceItems(items.map { it.first }.toTypedArray(), selectedIndex ) { dialog, idx ->
                MPVLib.setPropertyString("hwdec", items[idx].second)
                dialog.dismiss()
            }
            setOnDismissListener { restore() }
            create().show()
        }
    }

    private fun cycleSpeed() {
        player.cycleSpeed()
    }

    /** Loop A-B bawaan mpv: perintah ab-loop berputar A, B, mati. */
    private fun cycleAbLoop() {
        MPVLib.command(arrayOf("ab-loop"))
        updateAbLoopButton(announce = true)
    }

    /** Label: tanpa titik = A-B putih, hanya A = "A-", keduanya = A-B emas. */
    private fun updateAbLoopButton(announce: Boolean = false) {
        val a = MPVLib.getPropertyString("ab-loop-a")
        val b = MPVLib.getPropertyString("ab-loop-b")
        val hasA = a != null && a != "no"
        val hasB = b != null && b != "no"
        binding.abLoopBtn.text = if (hasA && !hasB) "A-" else "A-B"
        binding.abLoopBtn.setTextColor(if (hasA && hasB) 0xFF2ECC71.toInt() else 0xFFFFFFFF.toInt())
        if (announce) {
            showToast(when {
                hasA && hasB -> "Loop A-B aktif"
                hasA -> "Titik A ditandai"
                else -> "Loop A-B dimatikan"
            }, cancel = true)
        }
    }

    private fun pickSpeed() {
        // TODO: replace this with SliderPickerDialog
        val picker = SpeedPickerDialog()

        val restore = pauseForDialog()
        genericPickerDialog(picker, R.string.title_speed_dialog, "speed") {
            restore()
        }
    }

    private fun goIntoPiP() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
            return
        updatePiPParams(true)
        enterPictureInPictureMode()
    }

    private fun lockUI() {
        lockedUI = true
        hideControlsFade()
    }

    private fun unlockUI() {
        binding.unlockBtn.visibility = View.GONE
        lockedUI = false
        showControls()
    }

    data class MenuItem(@IdRes val idRes: Int, val handler: () -> Boolean)
    /**
     * Tempelkan dialog ke sisi layar dengan lebar tetap (dp), tanpa lebar minimum tema.
     * Gravity.END = tepi kanan, Gravity.BOTTOM = bawah; margin kecil dari tepi.
     */
    private fun AlertDialog.dockTo(gravity: Int, widthDp: Int, marginDp: Int = 8) {
        val w = window ?: return
        val dm = resources.displayMetrics
        val px = (widthDp * dm.density).toInt().coerceAtMost((dm.widthPixels * 0.92f).toInt())
        val margin = (marginDp * dm.density).toInt()
        w.setGravity(gravity)
        w.setLayout(px, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        val lp = w.attributes
        lp.x = margin
        lp.y = margin
        w.attributes = lp
    }

    private fun genericMenu(
            @LayoutRes layoutRes: Int, buttons: List<MenuItem>, hiddenButtons: Set<Int>,
            restoreState: StateRestoreCallback, docked: Boolean = false,
            onViewReady: (View) -> Unit = {}) {
        lateinit var dialog: AlertDialog

        val builder = AlertDialog.Builder(this, R.style.AppTheme_PlayerDialog)
        val dialogView = LayoutInflater.from(builder.context).inflate(layoutRes, null)
        onViewReady(dialogView)

        for (button in buttons) {
            val buttonView = dialogView.findViewById<View>(button.idRes)
            buttonView.setOnClickListener {
                val ret = button.handler()
                if (ret) // restore state immediately
                    restoreState()
                dialog.dismiss()
            }
        }

        hiddenButtons.forEach { dialogView.findViewById<View>(it).isVisible = false }

        if (Utils.visibleChildren(dialogView) == 0) {
            MpvLog.w(TAG, "Not showing menu because it would be empty")
            restoreState()
            return
        }

        Utils.handleInsetsAsPadding(dialogView)

        with (builder) {
            setView(dialogView)
            setOnCancelListener { restoreState() }
            dialog = create()
        }
        dialog.show()
        if (docked) dialog.dockTo(android.view.Gravity.BOTTOM or android.view.Gravity.END, 72, 12)
    }

    /** Kunci penanda: berkas lokal atau tautan asal; null untuk stream hasil resolve tanpa tautan asal yang stabil. */
    private fun bookmarkKey(): String? {
        val path = MPVLib.getPropertyString("path") ?: return null
        streamSources[path]?.let { return it }
        if (path.startsWith("http", ignoreCase = true) && path.contains("googlevideo.com")) return null
        return path
    }

    /** Dialog Penanda posisi: ketuk = lompat, tekan lama = hapus, tombol Tandai = simpan posisi sekarang. */
    private fun openBookmarks(restoreStateOrig: StateRestoreCallback) {
        val restoreState: StateRestoreCallback = { refreshSeekMarks(); restoreStateOrig() }
        val key = bookmarkKey()
        if (key == null) {
            showToast(getString(R.string.bookmark_unavailable), true)
            restoreState()
            return
        }
        val now = MPVLib.getPropertyDouble("time-pos") ?: 0.0
        val marks = VideoBookmarks.list(this, key)
        val labels = marks.map { m ->
            val t = Utils.prettyTime(m.sec.roundToInt())
            if (m.name.isBlank()) t else "$t  \u00B7  ${m.name}"
        }.toTypedArray()
        val builder = AlertDialog.Builder(this, R.style.AppTheme_PlayerDialog)
        builder.setTitle(R.string.bookmark_title)
        if (labels.isEmpty()) {
            builder.setMessage(R.string.bookmark_empty)
        } else {
            builder.setItems(labels) { d, i ->
                MPVLib.command(arrayOf("seek", marks[i].sec.toString(), "absolute"))
                d.dismiss()
            }
            builder.setNeutralButton(R.string.bookmark_clear) { _, _ ->
                VideoBookmarks.clear(this, key)
                showToast(getString(R.string.bookmark_cleared), true)
            }
        }
        builder.setPositiveButton(getString(R.string.bookmark_add, Utils.prettyTime(now.roundToInt()))) { _, _ ->
            val added = VideoBookmarks.add(this, key, now)
            showToast(
                if (added) getString(R.string.bookmark_added, Utils.prettyTime(now.roundToInt()))
                else getString(R.string.bookmark_exists), true
            )
        }
        var handoff = false
        builder.setNegativeButton(R.string.bookmark_backup) { _, _ ->
            handoff = true
            AlertDialog.Builder(this, R.style.AppTheme_PlayerDialog)
                .setTitle(R.string.bookmark_backup_title)
                .setItems(arrayOf(getString(R.string.bookmark_export), getString(R.string.bookmark_import))) { _, which ->
                    val msg = if (which == 0) VideoBookmarks.exportNow(this) else VideoBookmarks.importFromFile(this)
                    showToast(msg, true)
                }
                .setOnDismissListener { refreshSeekMarks(); restoreState() }
                .show()
        }
        builder.setOnDismissListener { if (!handoff) restoreState() }
        val dialog = builder.create()
        dialog.show()
        dialog.listView?.setOnItemLongClickListener { _, _, i, _ ->
            handoff = true
            dialog.dismiss()
            val acts = arrayOf(getString(R.string.bookmark_rename), getString(R.string.bookmark_delete))
            AlertDialog.Builder(this, R.style.AppTheme_PlayerDialog)
                .setItems(acts) { _, which ->
                    if (which == 0) {
                        val input = android.widget.EditText(this)
                        input.setText(marks[i].name)
                        input.setSelection(input.text.length)
                        input.hint = getString(R.string.bookmark_name_hint)
                        input.maxLines = 1
                        input.inputType = android.text.InputType.TYPE_CLASS_TEXT
                        AlertDialog.Builder(this, R.style.AppTheme_PlayerDialog)
                            .setTitle(R.string.bookmark_rename_title)
                            .setView(input)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                VideoBookmarks.rename(this, key, i, input.text.toString())
                                showToast(getString(R.string.bookmark_renamed), true)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .setOnDismissListener { restoreState() }
                            .show()
                    } else {
                        VideoBookmarks.removeAt(this, key, i)
                        showToast(getString(R.string.bookmark_removed), true)
                        restoreState()
                    }
                }
                .setOnCancelListener { restoreState() }
                .show()
            true
        }
    }

    private fun openTopMenu() {
        val restoreState = pauseForDialog()

        /******/
        val hiddenButtons = mutableSetOf<Int>()
        val buttons: MutableList<MenuItem> = mutableListOf(
                MenuItem(R.id.playlistBtn) {
                    openPlaylistMenu(restoreState); false
                },
                MenuItem(R.id.bookmarkBtn) {
                    openBookmarks(restoreState); false
                },
                MenuItem(R.id.backgroundBtn) {
                    // restoring state may (un)pause so do that first
                    restoreState()
                    backgroundPlayMode = "always"
                    player.paused = false
                    moveTaskToBack(true)
                    false
                },
                MenuItem(R.id.audioOnlyBtn) { toggleAudioOnly(); true },
                MenuItem(R.id.chapterBtn) {
                    val chapters = player.loadChapters()
                    if (chapters.isEmpty())
                        return@MenuItem true
                    val chapterArray = chapters.map {
                        val timecode = Utils.prettyTime(it.time.roundToInt())
                        if (!it.title.isNullOrEmpty())
                            getString(R.string.ui_chapter, it.title, timecode)
                        else
                            getString(R.string.ui_chapter_fallback, it.index+1, timecode)
                    }.toTypedArray()
                    val selectedIndex = MPVLib.getPropertyInt("chapter") ?: 0
                    with (AlertDialog.Builder(this, R.style.AppTheme_PlayerDialog)) {
                        setSingleChoiceItems(chapterArray, selectedIndex) { dialog, item ->
                            MPVLib.setPropertyInt("chapter", chapters[item].index)
                            dialog.dismiss()
                        }
                        setOnDismissListener { restoreState() }
                        create().show()
                    }; false
                },
                MenuItem(R.id.chapterPrev) {
                    MPVLib.command(arrayOf("add", "chapter", "-1")); true
                },
                MenuItem(R.id.chapterNext) {
                    MPVLib.command(arrayOf("add", "chapter", "1")); true
                },
                MenuItem(R.id.advancedBtn) { openAdvancedMenu(restoreState); false },
                MenuItem(R.id.orientationBtn) {
                    autoRotationMode = "manual"
                    cycleOrientation()
                    true
                }
        )

        if (!isPlayingAudio)
            hiddenButtons.add(R.id.backgroundBtn)
        // tombol Audio only hanya berguna bila ada video + audio, atau saat mode ini aktif (untuk kembali)
        if (!forcedAudioOnly && (player.vid == -1 || !isPlayingAudio))
            hiddenButtons.add(R.id.audioOnlyBtn)
        if ((MPVLib.getPropertyInt("chapter-list/count") ?: 0) == 0)
            hiddenButtons.add(R.id.rowChapter)
        /******/

        genericMenu(R.layout.dialog_top_menu, buttons, hiddenButtons, restoreState, docked = true) { v ->
            v.findViewById<android.widget.ImageButton>(R.id.audioOnlyBtn).apply {
                val label = getString(if (forcedAudioOnly) R.string.audio_only_off else R.string.audio_only_on)
                contentDescription = label
                tooltipText = label
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context,
                    if (forcedAudioOnly) R.color.player_playlist_active else R.color.player_dialog_text))
            }
        }
    }

    private fun genericPickerDialog(
        picker: PickerDialog, @StringRes titleRes: Int, property: String,
        restoreState: StateRestoreCallback
    ) {
        val dialog = with(AlertDialog.Builder(this, R.style.AppTheme_PlayerDialog)) {
            setTitle(titleRes)
            val inflater = LayoutInflater.from(context)
            setView(picker.buildView(inflater))
            setPositiveButton(R.string.dialog_ok) { _, _ ->
                picker.number?.let {
                    if (picker.isInteger())
                        MPVLib.setPropertyInt(property, it.toInt())
                    else
                        MPVLib.setPropertyDouble(property, it)
                }
            }
            setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
            setOnDismissListener { restoreState() }
            create()
        }

        picker.number = MPVLib.getPropertyDouble(property)
        dialog.show()
    }

    private fun openAdvancedMenu(restoreState: StateRestoreCallback) {
        /******/
        val hiddenButtons = mutableSetOf<Int>()
        val buttons: MutableList<MenuItem> = mutableListOf(
                MenuItem(R.id.subSeekPrev) {
                    MPVLib.command(arrayOf("sub-seek", "-1")); true
                },
                MenuItem(R.id.subSeekNext) {
                    MPVLib.command(arrayOf("sub-seek", "1")); true
                },
                MenuItem(R.id.statsBtn) {
                    MPVLib.command(arrayOf("script-binding", "stats/display-stats-toggle")); true
                },
                MenuItem(R.id.aspectBtn) {
                    val ratios = resources.getStringArray(R.array.aspect_ratios)
                    with (AlertDialog.Builder(this, R.style.AppTheme_PlayerDialog)) {
                        setItems(R.array.aspect_ratio_names) { dialog, item ->
                            if (ratios[item] == "panscan") {
                                MPVLib.setPropertyString("video-aspect-override", "-1")
                                MPVLib.setPropertyDouble("panscan", 1.0)
                            } else {
                                MPVLib.setPropertyString("video-aspect-override", ratios[item])
                                MPVLib.setPropertyDouble("panscan", 0.0)
                            }
                            dialog.dismiss()
                        }
                        setOnDismissListener { restoreState() }
                        create().show()
                    }; false
                },
        )

        val statsButtons = arrayOf(R.id.statsBtn1, R.id.statsBtn2, R.id.statsBtn3)
        for (i in 1..3) {
            buttons.add(MenuItem(statsButtons[i-1]) {
                MPVLib.command(arrayOf("script-binding", "stats/display-page-$i")); true
            })
        }

        // contrast, brightness and others get a -100 to 100 slider
        val basicIds = arrayOf(R.id.contrastBtn, R.id.brightnessBtn, R.id.gammaBtn, R.id.saturationBtn)
        val basicProps = arrayOf("contrast", "brightness", "gamma", "saturation")
        val basicTitles = arrayOf(R.string.contrast, R.string.video_brightness, R.string.gamma, R.string.saturation)
        basicIds.forEachIndexed { index, id ->
            buttons.add(MenuItem(id) {
                val slider = SliderPickerDialog(-100.0, 100.0, 1, R.string.format_fixed_number)
                genericPickerDialog(slider, basicTitles[index], basicProps[index], restoreState)
                false
            })
        }

        // audio / sub delay get a decimal picker
        buttons.add(MenuItem(R.id.audioDelayBtn) {
            val picker = DecimalPickerDialog(-600.0, 600.0)
            genericPickerDialog(picker, R.string.audio_delay, "audio-delay", restoreState)
            false
        })
        buttons.add(MenuItem(R.id.subDelayBtn) {
            val picker = SubDelayDialog(-600.0, 600.0)
            val dialog = with(AlertDialog.Builder(this, R.style.AppTheme_PlayerDialog)) {
                setTitle(R.string.sub_delay)
                val inflater = LayoutInflater.from(context)
                setView(picker.buildView(inflater))
                setPositiveButton(R.string.dialog_ok) { _, _ ->
                    picker.delay1?.let { player.subDelay = it }
                    picker.delay2?.let { player.secondarySubDelay = it }
                }
                setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
                setOnDismissListener { restoreState() }
                create()
            }

            picker.delay1 = player.subDelay ?: 0.0
            picker.delay2 = if (player.secondarySid != -1) player.secondarySubDelay else null
            dialog.show()
            false
        })

        // [SUBTITLE-STYLE] gaya & kecepatan subtitle (properti mpv; lihat SubStyleUi)
        buttons.add(MenuItem(R.id.subStyleBtn) {
            SubStyleUi.showStyleDialog(this, restoreState)
            false
        })
        buttons.add(MenuItem(R.id.subSpeedBtn) {
            SubStyleUi.showSpeedDialog(this, restoreState)
            false
        })
        buttons.add(MenuItem(R.id.subConvertBtn) {
            SubConvertUi.show(this, streamScope, restoreState)
            false
        })

        buttons.add(MenuItem(R.id.spectrumToggleBtn) { // [SPECTRUM]
            toggleSpectrum()
            true
        })
        buttons.add(MenuItem(R.id.spectrumColorBtn) { // [SPECTRUM]
            toggleSpectrumColors()
            true
        })

        buttons.add(MenuItem(R.id.sleepTimerBtn) {
            pickSleepTimer(restoreState)
            false
        })

        if (player.vid == -1)
            hiddenButtons.addAll(arrayOf(R.id.rowVideo1, R.id.rowVideo2, R.id.aspectBtn))
        if (player.aid == -1 || player.vid == -1)
            hiddenButtons.add(R.id.audioDelayBtn)
        if (player.sid == -1)
            hiddenButtons.addAll(arrayOf(R.id.subDelayBtn, R.id.rowSubSeek, R.id.rowSubStyle))
        if (!useAudioUI)
            hiddenButtons.addAll(arrayOf(R.id.spectrumToggleBtn, R.id.spectrumColorBtn)) // [SPECTRUM]
        /******/

        genericMenu(R.layout.dialog_advanced_menu, buttons, hiddenButtons, restoreState) { v ->
            if (sleepTimer.active) {
                val left = ((sleepTimer.remainingMs() + 59_999L) / 60_000L).toInt()
                v.findViewById<Button>(R.id.sleepTimerBtn).text =
                    getString(R.string.sleep_timer_active, left)
            }
        }
    }

    private val sleepHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sleepTimer = SleepTimer(
        clock = { android.os.SystemClock.elapsedRealtime() },
        postDelayed = { ms, r -> sleepHandler.postDelayed(r, ms) },
        remove = { r -> sleepHandler.removeCallbacks(r) },
        getVolume = { MPVLib.getPropertyDouble("volume") },
        setVolume = { MPVLib.setPropertyDouble("volume", it) },
        isPaused = { player.paused == true },
        pause = { player.paused = true },
        onExpired = {
            MpvLog.i(TAG, "Sleep timer habis, pemutaran dijeda")
            showToast(getString(R.string.sleep_timer_done))
        },
    )

    /** Pilih durasi sleep timer (0 = matikan). Fade-out dan jeda diurus [SleepTimer]. */
    private fun pickSleepTimer(restoreState: StateRestoreCallback) {
        val minutes = intArrayOf(0, 15, 30, 45, 60, 90)
        val labels = minutes.map {
            if (it == 0) getString(R.string.sleep_timer_off) else getString(R.string.sleep_timer_min, it)
        }.toTypedArray()
        with (AlertDialog.Builder(this, R.style.AppTheme_PlayerDialog)) {
            setTitle(R.string.sleep_timer)
            setItems(labels) { dialog, idx ->
                val m = minutes[idx]
                sleepTimer.start(m)
                MpvLog.i(TAG, "Sleep timer: $m menit")
                showToast(
                    if (m == 0) getString(R.string.sleep_timer_cancelled)
                    else getString(R.string.sleep_timer_set, m)
                )
                dialog.dismiss()
            }
            setOnDismissListener { restoreState() }
            create().show()
        }
    }

    private var forcedAudioOnly = false

    /** Matikan/nyalakan track video lewat property `vid`; UI audio dipicu oleh observer current-tracks/video/image. */
    private fun toggleAudioOnly() {
        forcedAudioOnly = !forcedAudioOnly
        if (forcedAudioOnly)
            player.vid = -1
        else
            MPVLib.setPropertyString("vid", "auto")
        MpvLog.i(TAG, "Audio only: $forcedAudioOnly")
        showToast(getString(if (forcedAudioOnly) R.string.audio_only_on else R.string.audio_only_off), true)
    }

    private fun cycleOrientation() {
        requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun refreshUi() {
        // forces update of entire UI, used when resuming the activity
        updatePlaybackStatus(psc.pause)
        updatePlaybackPos(psc.positionSec)
        updatePlaybackDuration(psc.durationSec)
        updateAudioUI()
        updateNowPlayingArt()
        updateOrientation()
        updateMetadataDisplay()
        updateDecoderButton()
        updateSpeedButton()
        updatePlaylistButtons()
        player.loadTracks()
    }

    private fun updateAudioUI() {
        val audioButtons = arrayOf(R.id.prevBtn, R.id.cycleAudioBtn, R.id.playBtn,
                R.id.cycleSpeedBtn, R.id.abLoopBtn, R.id.nextBtn)
        val videoButtons = arrayOf(R.id.cycleAudioBtn, R.id.cycleSubsBtn, R.id.playBtn,
                R.id.cycleDecoderBtn, R.id.cycleSpeedBtn, R.id.abLoopBtn)

        val shouldUseAudioUI = isPlayingAudioOnly()
        if (shouldUseAudioUI == useAudioUI)
            return
        useAudioUI = shouldUseAudioUI
        MpvLog.v(TAG, "Audio UI: $useAudioUI")

        val seekbarGroup = binding.controlsSeekbarGroup
        val buttonGroup = binding.controlsButtonGroup

        if (useAudioUI) {
            // Move prev/next file from seekbar group to buttons group
            Utils.viewGroupMove(seekbarGroup, R.id.prevBtn, buttonGroup, 0)
            Utils.viewGroupMove(seekbarGroup, R.id.nextBtn, buttonGroup, -1)

            // Change button layout of buttons group
            Utils.viewGroupReorder(buttonGroup, audioButtons)

            // Show song title and more metadata
            binding.controlsTitleGroup.visibility = View.VISIBLE
            Utils.viewGroupReorder(binding.controlsTitleGroup, arrayOf(R.id.titleTextView, R.id.minorTitleTextView))
            updateMetadataDisplay()

            showControls()
        } else {
            Utils.viewGroupMove(buttonGroup, R.id.prevBtn, seekbarGroup, 0)
            Utils.viewGroupMove(buttonGroup, R.id.nextBtn, seekbarGroup, -1)

            Utils.viewGroupReorder(buttonGroup, videoButtons)

            // Show title only depending on settings
            if (showMediaTitle) {
                binding.controlsTitleGroup.visibility = View.VISIBLE
                Utils.viewGroupReorder(binding.controlsTitleGroup, arrayOf(R.id.fullTitleTextView))
                updateMetadataDisplay()
            } else {
                binding.controlsTitleGroup.visibility = View.GONE
            }

            hideControls() // do NOT use fade runnable
        }

        // Visibility might have changed, so update
        updatePlaylistButtons()
        updateNowPlayingArt()
    }

    private fun updateScreenBrightness() {
        if (lastScreenBrightness == -1)
            return
        val lp = window.attributes
        lp.screenBrightness = lastScreenBrightness / 100f
        window.attributes = lp
    }

    private fun updateMetadataDisplay() {
        if (!useAudioUI) {
            if (showMediaTitle)
                binding.fullTitleTextView.text = psc.meta.formatTitle()
        } else {
            binding.titleTextView.text = psc.meta.formatTitle()
            binding.minorTitleTextView.text = psc.meta.formatArtistAlbum()
        }
    }

    // [COVER-ART] Layar "Now Playing": sampul dimuat di thread IO, hanya untuk berkas lokal pada mode UI audio.
    private var coverPath: String? = null
    private var coverJob: Job? = null

    // [SPECTRUM] Visualizer spektrum di layar Now Playing: PCM berkas lokal dianalisis MediaCodec (SpectrumDecoder),
    // digambar SpectrumView menurut posisi putar mpv. Tidak menyentuh JNI/native mpv.
    @Volatile private var spectrumPath: String? = null
    @Volatile private var spectrumAnalyzer: SpectrumAnalyzer? = null // [SPECTRUM-JUMP]
    private var spectrumReserve = false
    @Volatile private var spectrumKickUntil = 0L

    /** [SPECTRUM] Jaga spektrum tetap bergerak sesaat setelah seek, apa pun status pause/cache mpv. */
    private fun kickSpectrum(ms: Long = 1200L) { spectrumKickUntil = SystemClock.uptimeMillis() + ms }

    /** [SPECTRUM-JUMP] Event seek mpv: minta dekoder lompat (didebounce di analyzer) dan catat ke log mandiri (maks 1x/500 ms). */
    @Volatile private var lastSeekLog = 0L
    private fun logSpectrumSeek(eventId: Int) {
        spectrumAnalyzer?.onSeek()
        val now = SystemClock.uptimeMillis()
        if (now - lastSeekLog < 500L) return
        lastSeekLog = now
        val t = binding.spectrumView.track ?: return
        val name = if (eventId == MpvEvent.MPV_EVENT_SEEK) "SEEK" else "RESTART"
        val pos = MPVLib.getPropertyDouble("time-pos/full")
        val frame = if (pos == null) -1 else t.frameFor((pos * 1000).toLong())
        MpvLog.d("spectrum", "event=$name pos=${pos ?: "null"}s frame=$frame terisi=${t.has(frame)} kepala=${t.firstGap()} akhir=${t.endFrame}")
    }
    private var lastCover: CoverArt.Cover? = null
    private var specPosMs = 0L
    private var specPosTick = 0L

    /** Posisi putar dalam ms: dibaca dari mpv tiap ~33 ms, di antaranya diekstrapolasi (hemat panggilan JNI). */
    private fun spectrumPositionMs(): Long {
        val now = SystemClock.uptimeMillis()
        if (now - specPosTick >= 33) {
            MPVLib.getPropertyDouble("time-pos/full")?.let { specPosMs = (it * 1000).toLong(); specPosTick = now }
        }
        val p = specPosMs + ((now - specPosTick) * psc.speed).toLong()
        spectrumPlayhead = p // [SPECTRUM] dibaca dekoder (thread latar) untuk melompat ke posisi seek
        return p
    }

    @Volatile private var spectrumPlayhead = 0L // playhead terakhir (ms) untuk SpectrumDecoder; nilai biasa, tanpa JNI di thread dekoder

    private fun controlsReserved(): Int {
        val d = resources.displayMetrics.density
        val c = binding.controls
        if (c.visibility != View.VISIBLE) return (24 * d).toInt() // [SPECTRUM] kontrol tersembunyi: spektrum turun ke bawah
        val bottomMargin = (c.layoutParams as? MarginLayoutParams)?.bottomMargin ?: 0
        return if (c.height > 0) c.height + bottomMargin else (180 * d).toInt()
    }

    /**
     * [SPECTRUM] Ruang untuk bilah tool (shield/cari/kunci/PiP/pengaturan) yang menempel tepat di atas controls,
     * supaya spektrum berada di atasnya dan tidak tertutup. Memakai tinggi terukur bila ada, jika belum 48dp.
     */
    private fun topBarReserve(): Int {
        val d = resources.displayMetrics.density
        val tb = binding.topControls
        if (tb.visibility != View.VISIBLE) return 0
        val mb = (tb.layoutParams as? MarginLayoutParams)?.bottomMargin ?: 0
        return (if (tb.height > 0) tb.height else (48 * d).toInt()) + mb + (8 * d).toInt()
    }

    private fun layoutSpectrum(reserved: Int) {
        val lp = binding.spectrumView.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        val total = reserved + topBarReserve()
        if (lp.bottomMargin == total) return
        lp.bottomMargin = total
        binding.spectrumView.layoutParams = lp
    }

    private fun setSpectrumReserve(on: Boolean) {
        if (spectrumReserve == on) return
        spectrumReserve = on
        showNowPlayingArt(if (useAudioUI) lastCover else null)
    }

    private fun stopSpectrum() {
        stopLiveSpectrum() // [SPECTRUM-LIVE]
        spectrumAnalyzer?.cancel() // [SPECTRUM-JUMP]
        spectrumAnalyzer = null
        spectrumPath = null
        binding.spectrumView.track = null
        binding.spectrumView.visibility = View.GONE
        setSpectrumReserve(false)
    }

    private var spectrumLive: SpectrumLive? = null // [SPECTRUM-LIVE]

    private fun stopLiveSpectrum() { // [SPECTRUM-LIVE]
        spectrumLive?.cancel()
        spectrumLive = null
    }

    private fun updateAnySpectrum(path: String?) { // [SPECTRUM-LIVE]
        if (!updateLiveSpectrum(path)) updateSpectrum(path)
    }

    /** [SPECTRUM-LIVE] true bila stream http(s) ditangani visualizer live (jangan lanjut ke updateSpectrum). */
    private fun updateLiveSpectrum(path: String?): Boolean {
        if (path != null || !useAudioUI || !SpectrumPrefs.enabled(this)) { stopLiveSpectrum(); return false }
        val url = try { MPVLib.getPropertyString("path") } catch (e: Exception) { null }
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) { stopLiveSpectrum(); return false }
        if (spectrumLive != null && spectrumPath == url) return true
        stopSpectrum()
        spectrumPath = url
        setSpectrumReserve(true)
        val view = binding.spectrumView
        view.accent = ContextCompat.getColor(this, R.color.player_playlist_active)
        view.colorMode = SpectrumPrefs.colorMode(this)
        view.isPlaying = { !psc.pause && activityIsForeground }
        view.dimWhen = { false }
        val live = SpectrumLive(
            active = { !psc.pause && activityIsForeground },
            onTrack = { t -> view.post { if (spectrumPath == url) { view.track = t; view.visibility = View.VISIBLE } } },
            onUnsupported = { view.post { if (spectrumPath == url && view.track == null) stopSpectrum() } },
        )
        view.positionMs = live::positionMs
        spectrumLive = live
        live.start()
        return true
    }

    private fun updateSpectrum(path: String?) {
        if (path == null || !SpectrumPrefs.enabled(this)) { stopSpectrum(); return }
        if (path == spectrumPath) return
        stopSpectrum()
        spectrumPath = path
        setSpectrumReserve(true)
        val view = binding.spectrumView
        view.accent = ContextCompat.getColor(this, R.color.player_playlist_active)
        view.colorMode = SpectrumPrefs.colorMode(this)
        view.positionMs = ::spectrumPositionMs
        view.isPlaying = { (!psc.pause || userIsOperatingSeekbar || pausedForSeek != 0 || SystemClock.uptimeMillis() < spectrumKickUntil) && activityIsForeground }
        view.dimWhen = { false } // zona spektrum sudah dipisahkan dari konten lain, tidak perlu diredupkan
        spectrumAnalyzer = SpectrumAnalyzer( // [SPECTRUM-JUMP] kepala berurutan + dekoder lompat setelah seek
            path = path,
            positionMs = { MPVLib.getPropertyDouble("time-pos/full")?.let { (it * 1000).toLong() } ?: -1L },
            onTrack = { t -> view.post { if (spectrumPath == path) { view.track = t; view.visibility = View.VISIBLE } } },
            onUnsupported = { view.post { if (spectrumPath == path && view.track == null) stopSpectrum() } }, // format tidak didukung dekoder perangkat
        ).also { it.start() }
    }

    private fun toggleSpectrumColors() { // [SPECTRUM] putar mode: aksen tunggal -> per frekuensi -> Cinema
        val mode = SpectrumPrefs.colorMode(this).next()
        SpectrumPrefs.setColorMode(this, mode)
        binding.spectrumView.colorMode = mode
        showToast(getString(when (mode) {
            SpectrumColorMode.ACCENT -> R.string.spectrum_colors_accent
            SpectrumColorMode.FREQUENCY -> R.string.spectrum_colors_freq
            SpectrumColorMode.CINEMA -> R.string.spectrum_colors_cinema
        }), true)
    }

    private fun toggleSpectrum() {
        val on = !SpectrumPrefs.enabled(this)
        SpectrumPrefs.set(this, on)
        updateAnySpectrum(if (useAudioUI) localVideoPath() else null) // [SPECTRUM-LIVE]
        showToast(getString(if (on) R.string.spectrum_on else R.string.spectrum_off), true)
    }

    private fun updateNowPlayingArt() {
        val path = if (useAudioUI) localVideoPath() else null
        updateAnySpectrum(path) // [SPECTRUM] [SPECTRUM-LIVE]
        if (path == null) {
            coverJob?.cancel()
            coverPath = null
            showNowPlayingArt(null)
            return
        }
        if (path == coverPath) return
        coverPath = path
        coverJob?.cancel()
        coverJob = streamScope.launch {
            val cover = withContext(Dispatchers.IO) { CoverArt.load(path) }
            if (coverPath != path || !useAudioUI) return@launch
            showNowPlayingArt(cover)
            if (cover != null) {
                // Sampul yang sama untuk notifikasi & sesi media (menggantikan bingkai yang di-grab mpv).
                BackgroundPlaybackService.thumbnail = cover.thumb
                updateMediaSession()
            }
        }
    }

    private fun showNowPlayingArt(cover: CoverArt.Cover?) {
        lastCover = cover // [SPECTRUM]
        if (cover == null) {
            // [SPECTRUM] tanpa sampul tetap tampil (latar gelap) bila visualizer aktif
            binding.nowPlayingArt.visibility = if (spectrumReserve) View.VISIBLE else View.GONE
            binding.nowPlayingCover.setImageDrawable(null)
            binding.nowPlayingBackdrop.setImageDrawable(null)
            layoutSpectrum(controlsReserved())
            return
        }
        // Sisakan ruang untuk bilah kontrol agar kartu sampul tidak tertimpa.
        val d = resources.displayMetrics.density
        val reserved = controlsReserved() // [SPECTRUM] ikut status tampil/sembunyi kontrol
        val side = (32 * d).toInt()
        val spectrumSpace = if (spectrumReserve) (SPECTRUM_HEIGHT_DP * d).toInt() else 0 // [SPECTRUM]
        binding.nowPlayingCover.setPadding(side, (24 * d).toInt(), side, reserved + spectrumSpace + (if (spectrumReserve) topBarReserve() else 0) + (12 * d).toInt())
        layoutSpectrum(reserved)
        binding.nowPlayingCover.setImageBitmap(cover.art)
        binding.nowPlayingBackdrop.setImageBitmap(cover.backdrop)
        binding.nowPlayingArt.visibility = View.VISIBLE
    }

    private fun updatePlaybackPos(position: Int) {
        binding.playbackPositionTxt.text = Utils.prettyTime(position)
        if (useTimeRemaining) {
            val diff = psc.durationSec - position
            binding.playbackDurationTxt.text = if (diff <= 0)
                "-00:00"
            else
                Utils.prettyTime(-diff, true)
        }
        if (!userIsOperatingSeekbar)
            binding.playbackSeekbar.progress = position * SEEK_BAR_PRECISION

        // Note: do NOT add other update functions here just because this is called every second.
        // Use property observation instead.
        updateStats()
    }

    private fun updatePlaybackDuration(duration: Int) {
        if (!useTimeRemaining)
            binding.playbackDurationTxt.text = Utils.prettyTime(duration)
        if (!userIsOperatingSeekbar)
            binding.playbackSeekbar.max = duration * SEEK_BAR_PRECISION
        refreshSeekMarks()
    }

    /** Titik emas penanda di bilah seek; kosong untuk stream tanpa kunci penanda. */
    private fun refreshSeekMarks() {
        val key = try { bookmarkKey() } catch (e: Exception) { null }
        seekMarks = if (key == null) emptyList() else VideoBookmarks.list(this, key)
        binding.playbackSeekbar.setMarks(seekMarks.map { it.sec }, SEEK_BAR_PRECISION)
    }

    private var seekMarks: List<Mark> = emptyList()
    private var markLabelShown = false
    private val hideMarkLabel = Runnable {
        if (markLabelShown) {
            markLabelShown = false
            binding.gestureTextView.visibility = View.GONE
        }
    }

    private fun nearestMark(sec: Double, within: Double): Mark? =
        seekMarks.minByOrNull { Math.abs(it.sec - sec) }?.takeIf { Math.abs(it.sec - sec) <= within }

    private fun markLabel(sec: Double, m: Mark?): String {
        val t = Utils.prettyTime(sec.roundToInt())
        if (m == null) return t
        return if (m.name.isBlank()) "\u25CF  $t" else "\u25CF  $t  \u00B7  ${m.name}"
    }

    /** Saat bilah seek diseret melewati penanda bernama (+-2 detik): tampilkan "mm:ss . nama" di label gestur. */
    private fun showMarkLabelWhileSeeking(sec: Double) {
        val m = nearestMark(sec, 2.0)
        if (m == null) {
            hideMarkLabel.run()
            return
        }
        val tv = binding.gestureTextView
        markLabelShown = true
        tv.visibility = View.VISIBLE
        tv.text = markLabel(sec, m)
    }

    /** Ketuk titik penanda di bilah seek: lompat tepat ke posisinya dan tampilkan labelnya sebentar. */
    private fun jumpToMark(sec: Double) {
        player.timePos = sec
        val tv = binding.gestureTextView
        markLabelShown = true
        tv.visibility = View.VISIBLE
        tv.text = markLabel(sec, nearestMark(sec, 0.5))
        tv.removeCallbacks(hideMarkLabel)
        tv.postDelayed(hideMarkLabel, 1500)
    }

    private val thumbnailer = SeekThumbnailer()

    /** Path berkas lokal yang sedang diputar, atau null (stream / fd:// tidak mendukung pratinjau gambar). */
    private fun localVideoPath(): String? {
        val p = try { MPVLib.getPropertyString("path") } catch (e: Exception) { null } ?: return null
        val f = if (p.startsWith("file://")) p.removePrefix("file://") else p
        return if (f.startsWith("/") && java.io.File(f).isFile) f else null
    }

    private var probedUrl: String? = null // [SPECTRUM-LIVE-PROBE]

    /** Diagnosis sementara: apakah af lavfi/astats + af-metadata tersedia untuk stream live. Hanya log, tanpa efek UI. */
    private fun probeLiveSpectrum() { // [SPECTRUM-LIVE-PROBE]
        val url = try { MPVLib.getPropertyString("path") } catch (e: Exception) { null } ?: return
        if (url == probedUrl || !url.startsWith("http")) return
        probedUrl = url
        streamScope.launch {
            val tag = "SpectrumProbe"
            try {
                MPVLib.command(arrayOf("af", "add", "@mprobe:lavfi=[astats=metadata=1:reset=1]"))
                MpvLog.i(tag, "af add dikirim untuk $url")
                kotlinx.coroutines.delay(3000)
                val af = MPVLib.getPropertyString("af")
                val rms = MPVLib.getPropertyString("af-metadata/mprobe/lavfi.astats.Overall.RMS_level")
                MpvLog.i(tag, "af=$af | RMS_level=$rms")
            } catch (e: Exception) {
                MpvLog.e(tag, "probe gagal", e)
            } finally {
                try { MPVLib.command(arrayOf("af", "remove", "@mprobe")) } catch (_: Exception) {}
            }
        }
    }

    /** Kartu pratinjau di atas bilah kontrol saat bilah seek diseret: bingkai (berkas lokal) + waktu/penanda. */
    private fun updateSeekPreview(sec: Double) {
        val box = binding.seekPreview
        val img = binding.seekPreviewImage
        binding.seekPreviewText.text = markLabel(sec, nearestMark(sec, 2.0))
        val hasThumb = thumbnailer.available
        img.visibility = if (hasThumb) View.VISIBLE else View.GONE
        if (box.visibility != View.VISIBLE) box.visibility = View.VISIBLE
        positionSeekPreview(box)
        if (hasThumb) thumbnailer.request(sec) { bmp ->
            if (bmp != null && box.visibility == View.VISIBLE) img.setImageBitmap(bmp)
        }
    }

    /** Pusatkan kartu pada posisi jempol bilah seek, dibatasi dalam lebar layar. */
    private fun positionSeekPreview(box: View) {
        val bar = binding.playbackSeekbar
        val outside = binding.outside
        val total = bar.max
        if (total <= 0) return
        val a = IntArray(2)
        val b = IntArray(2)
        bar.getLocationInWindow(a)
        outside.getLocationInWindow(b)
        val span = bar.width - bar.paddingLeft - bar.paddingRight
        val x = a[0] - b[0] + bar.paddingLeft + span * bar.progress.toFloat() / total
        val w = if (box.width > 0) box.width else (164 * resources.displayMetrics.density).toInt()
        val maxX = (outside.width - w).coerceAtLeast(0).toFloat()
        box.translationX = (x - w / 2f).coerceIn(0f, maxX)
    }

    private fun hideSeekPreview() {
        thumbnailer.cancel()
        binding.seekPreview.visibility = View.GONE
    }

    private fun updatePlaybackStatus(paused: Boolean) {
        val r = if (paused) R.drawable.ic_play_arrow_black_24dp else R.drawable.ic_pause_black_24dp
        binding.playBtn.setImageResource(r)

        updatePiPParams()
        if (paused) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateDecoderButton() {
        binding.cycleDecoderBtn.text = when (player.hwdecActive) {
            "mediacodec" -> "HW+"
            "no" -> "SW"
            else -> "HW"
        }
    }

    private fun updateSpeedButton() {
        binding.cycleSpeedBtn.text = getString(R.string.ui_speed, psc.speed)
    }

    private fun updatePlaylistButtons() {
        val plCount = psc.playlistCount

        if (!useAudioUI && plCount == 1) {
            // use View.GONE so the buttons won't take up any space
            binding.prevBtn.visibility = View.GONE
            binding.nextBtn.visibility = View.GONE
            return
        }
        binding.prevBtn.visibility = View.VISIBLE
        binding.nextBtn.visibility = View.VISIBLE

        val g = ContextCompat.getColor(this, R.color.tint_disabled)
        val w = ContextCompat.getColor(this, R.color.tint_normal)
        // [PLAYLIST-WRAP] next/prev berputar, jadi aktif selama ada lebih dari satu trek
        val navTint = ColorStateList.valueOf(if (plCount > 1) w else g)
        binding.prevBtn.imageTintList = navTint
        binding.nextBtn.imageTintList = navTint
    }

    private fun updateOrientation(initial: Boolean = false) {
        // screen orientation is fixed (Android TV)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT))
            return

        if (autoRotationMode != "auto") {
            if (!initial)
                return // don't reset at runtime
            requestedOrientation = when (autoRotationMode) {
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        if (initial || player.vid == -1)
            return

        val ratio = player.getVideoAspect()?.toFloat() ?: 0f
        if (ratio == 0f || ratio in (1f / ASPECT_RATIO_MIN) .. ASPECT_RATIO_MIN) {
            // video is square, let Android do what it wants
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            return
        }
        requestedOrientation = if (ratio > 1f)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    @RequiresApi(26)
    private fun makeRemoteAction(@DrawableRes icon: Int, @StringRes title: Int, intentAction: String): RemoteAction {
        val intent = NotificationButtonReceiver.createIntent(this, intentAction)
        return RemoteAction(Icon.createWithResource(this, icon), getString(title), "", intent)
    }

    /**
     * Update Picture-in-picture parameters. Will only run if in PiP mode unless
     * `force` is set.
     */
    private fun updatePiPParams(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return
        if (!isInPictureInPictureMode && !force)
            return

        val playPauseAction = if (psc.pause)
            makeRemoteAction(R.drawable.ic_play_arrow_black_24dp, R.string.btn_play, "PLAY_PAUSE")
        else
            makeRemoteAction(R.drawable.ic_pause_black_24dp, R.string.btn_pause, "PLAY_PAUSE")
        val actions = mutableListOf<RemoteAction>()
        if (psc.playlistCount > 1) {
            actions.add(makeRemoteAction(
                R.drawable.ic_skip_previous_black_24dp, R.string.dialog_prev, "ACTION_PREV"
            ))
            actions.add(playPauseAction)
            actions.add(makeRemoteAction(
                R.drawable.ic_skip_next_black_24dp, R.string.dialog_next, "ACTION_NEXT"
            ))
        } else {
            actions.add(playPauseAction)
        }

        val params = with(PictureInPictureParams.Builder()) {
            val aspect = player.getVideoAspect() ?: 0.0
            setAspectRatio(Rational(aspect.times(10000).toInt(), 10000))
            setActions(actions)
        }
        try {
            setPictureInPictureParams(params.build())
        } catch (e: IllegalArgumentException) {
            // Android has some limits of what the aspect ratio can be
            params.setAspectRatio(Rational(1, 1))
            setPictureInPictureParams(params.build())
        }
    }

    // Media Session handling

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPause() {
            player.paused = true
        }
        override fun onPlay() {
            player.paused = false
        }
        override fun onSeekTo(pos: Long) {
            player.timePos = (pos / 1000.0)
        }
        override fun onSkipToNext() = playlistNext()
        override fun onSkipToPrevious() = playlistPrev()
        override fun onSetRepeatMode(repeatMode: Int) {
            MPVLib.setPropertyString("loop-playlist",
                if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ALL) "inf" else "no")
            MPVLib.setPropertyString("loop-file",
                if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE) "inf" else "no")
        }
        override fun onSetShuffleMode(shuffleMode: Int) {
            player.changeShuffle(false, shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL)
        }
    }

    private fun initMediaSession(): MediaSessionCompat {
        /*
            developer.android.com/guide/topics/media-apps/working-with-a-media-session
            developer.android.com/guide/topics/media-apps/audio-app/mediasession-callbacks
            developer.android.com/reference/android/support/v4/media/session/MediaSessionCompat
         */
        val session = MediaSessionCompat(this, TAG)
        session.setFlags(0)
        session.setCallback(mediaSessionCallback)
        return session
    }

    private fun updateMediaSession() {
        synchronized (psc) {
            mediaSession?.let { psc.write(it) }
        }
    }

    // mpv events

    private fun eventPropertyUi(property: String, dummy: Any?, metaUpdated: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "track-list" -> player.loadTracks()
            "current-tracks/audio/selected", "current-tracks/video/image" -> updateAudioUI()
            "hwdec-current" -> updateDecoderButton()
        }
        if (metaUpdated)
            updateMetadataDisplay()
    }

    private fun eventPropertyUi(property: String, value: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "pause" -> updatePlaybackStatus(value)
            "mute" -> { // indirectly from updateAudioPresence()
                updateAudioUI()
            }
        }
    }

    private fun eventPropertyUi(property: String, value: Long) {
        if (!activityIsForeground) return
        when (property) {
            "time-pos" -> updatePlaybackPos(psc.positionSec)
            "playlist-pos", "playlist-count" -> {
                updatePlaylistButtons()
                if (property == "playlist-count") {
                    MpvLog.i(TAG, "playlist-count=$value")
                    updatePiPParams()
                }
            }
        }
    }

    private fun eventPropertyUi(property: String, value: Double) {
        if (!activityIsForeground) return
        when (property) {
            "duration/full" -> updatePlaybackDuration(psc.durationSec)
            "video-params/aspect", "video-params/rotate" -> {
                updateOrientation()
                updatePiPParams()
            }
        }
    }

    private fun eventPropertyUi(property: String, value: String, metaUpdated: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "speed" -> updateSpeedButton()
        }
        if (metaUpdated)
            updateMetadataDisplay()
    }

    private fun eventUi(eventId: Int) {
        if (!activityIsForeground) return
        if (eventId == MpvEvent.MPV_EVENT_FILE_LOADED) updateNowPlayingArt() // [COVER-ART]
    }

    override fun eventProperty(property: String) {
        val metaUpdated = psc.update(property)
        if (metaUpdated)
            updateMediaSession()
        if (property == "loop-file" || property == "loop-playlist") {
            mediaSession?.setRepeatMode(when (player.getRepeat()) {
                2 -> PlaybackStateCompat.REPEAT_MODE_ONE
                1 -> PlaybackStateCompat.REPEAT_MODE_ALL
                else -> PlaybackStateCompat.REPEAT_MODE_NONE
            })
        } else if (property == "current-tracks/audio/selected") {
            updateAudioPresence()
        }

        if (property == "pause" || property == "current-tracks/audio/selected")
            handleAudioFocus()

        // [INFO-DECODER] catat dekoder aktif untuk Sistem > Info (tetap tercatat saat di latar)
        if (property == "hwdec-current") MpvVersionNote.recordDecoder(applicationContext, player.hwdecActive)

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, null, metaUpdated) }
    }

    override fun eventProperty(property: String, value: Boolean) {
        if (property == "pause") WatchStats.onPause(value)
        val metaUpdated = psc.update(property, value)
        if (metaUpdated)
            updateMediaSession()
        if (property == "shuffle") {
            mediaSession?.setShuffleMode(if (value)
                PlaybackStateCompat.SHUFFLE_MODE_ALL
            else
                PlaybackStateCompat.SHUFFLE_MODE_NONE)
        } else if (property == "mute") {
            updateAudioPresence()
        }

        if (metaUpdated || property == "mute")
            handleAudioFocus()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: Long) {
        if (psc.update(property, value))
            updateMediaSession()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: Double) {
        if (psc.update(property, value))
            updateMediaSession()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: String) {
        val metaUpdated = psc.update(property, value)
        if (metaUpdated)
            updateMediaSession()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value, metaUpdated) }
    }

    override fun event(eventId: Int) {
        if (eventId == MpvEvent.MPV_EVENT_SEEK || eventId == MpvEvent.MPV_EVENT_PLAYBACK_RESTART) logSpectrumSeek(eventId) // [SPECTRUM-DIAG]
        if (eventId == MpvEvent.MPV_EVENT_END_FILE) {
            WatchStats.onEnd()
            PlaybackLog.onEnd(psc.position, psc.duration)
            recordWatchProgress(shieldPath, psc.position, psc.duration) // [WATCH-PROGRESS]
            psc.eof()
            updateMediaSession()
        }

        if (eventId == MpvEvent.MPV_EVENT_SHUTDOWN)
            finishWithResult(if (playbackHasStarted) RESULT_OK else RESULT_CANCELED)

        if (eventId == MpvEvent.MPV_EVENT_START_FILE || eventId == MpvEvent.MPV_EVENT_FILE_LOADED) {
            shieldPath = MPVLib.getPropertyString("path") ?: ""
            runOnUiThread { updateShieldBadge() }
        }

        if (eventId == MpvEvent.MPV_EVENT_FILE_LOADED) {
            WatchStats.onLoaded()
            PlaybackLog.onLoaded()
            prefetchNext()
        }

        if (eventId == MpvEvent.MPV_EVENT_START_FILE) {
            WatchStats.onStart()
            PlaybackLog.onStart()
            val cmds = onloadCommands.toTypedArray()
            onloadCommands.clear()
            for (c in cmds)
                MPVLib.command(c)
            if (this.statsLuaMode > 0 && !playbackHasStarted) {
                MPVLib.command(arrayOf("script-binding", "stats/display-page-${this.statsLuaMode}-toggle"))
            }

            playbackHasStarted = true
        }

        if (!activityIsForeground) return
        eventUiHandler.post { eventUi(eventId) }
    }

    // Gesture handler

    private var initialSeek = 0f
    private var initialBright = 0f
    private var initialVolume = 0
    private var maxVolume = 0
    /** 0 = initial, 1 = paused, 2 = was already paused */
    private var pausedForSeek = 0

    private fun fadeGestureText() {
        fadeHandler.removeCallbacks(fadeRunnable3)
        binding.gestureTextView.visibility = View.VISIBLE

        fadeHandler.postDelayed(fadeRunnable3, 500L)
    }

    override fun onPropertyChange(p: PropertyChange, diff: Float) {
        val gestureTextView = binding.gestureTextView
        when (p) {
            /* Drag gestures */
            PropertyChange.Init -> {
                mightWantToToggleControls = false

                initialSeek = (psc.position / 1000f)
                initialBright = Utils.getScreenBrightness(this) ?: 0.5f
                with (audioManager!!) {
                    initialVolume = getStreamVolume(STREAM_TYPE)
                    maxVolume = if (isVolumeFixed)
                        0
                    else
                        getStreamMaxVolume(STREAM_TYPE)
                }
                if (!isPlayingAudio)
                    maxVolume = 0 // disallow volume gesture if no audio
                pausedForSeek = 0

                fadeHandler.removeCallbacks(fadeRunnable3)
                gestureTextView.visibility = View.VISIBLE
                gestureTextView.text = ""
            }
            PropertyChange.Seek -> {
                // disable seeking when duration is unknown
                val duration = (psc.duration / 1000f)
                if (duration == 0f || initialSeek < 0)
                    return
                if (smoothSeekGesture && pausedForSeek == 0) {
                    pausedForSeek = if (psc.pause) 2 else 1
                    if (pausedForSeek == 1)
                        player.paused = true
                }

                kickSpectrum() // [SPECTRUM]
                val newPosExact = (initialSeek + diff).coerceIn(0f, duration)
                val newPos = newPosExact.roundToInt()
                val newDiff = (newPosExact - initialSeek).roundToInt()
                if (smoothSeekGesture) {
                    player.timePos = newPosExact.toDouble() // (exact seek)
                } else {
                    // seek faster than assigning to timePos but less precise
                    MPVLib.command(arrayOf("seek", "$newPosExact", "absolute+keyframes"))
                }
                // Note: don't call updatePlaybackPos() here because mpv will seek a timestamp
                // actually present in the file, and not the exact one we specified.

                val posText = Utils.prettyTime(newPos)
                val diffText = Utils.prettyTime(newDiff, true)
                gestureTextView.text = getString(R.string.ui_seek_distance, posText, diffText)
            }
            PropertyChange.Volume -> {
                if (maxVolume == 0)
                    return
                val newVolume = (initialVolume + (diff * maxVolume).toInt()).coerceIn(0, maxVolume)
                val newVolumePercent = 100 * newVolume / maxVolume
                audioManager!!.setStreamVolume(STREAM_TYPE, newVolume, 0)

                gestureTextView.text = getString(R.string.ui_volume, newVolumePercent)
            }
            PropertyChange.Bright -> {
                val newBrightPercent = ((initialBright + diff).coerceIn(0f, 1f) * 100).roundToInt()
                lastScreenBrightness = newBrightPercent
                updateScreenBrightness()

                gestureTextView.text = getString(R.string.ui_brightness, newBrightPercent)
            }
            PropertyChange.Finalize -> {
                if (pausedForSeek == 1)
                    player.paused = false
                gestureTextView.visibility = View.GONE
            }

            /* Tap gestures */
            PropertyChange.SeekFixed -> {
                kickSpectrum() // [SPECTRUM]
                val seekTime = diff * 10f
                val newPos = psc.positionSec + seekTime.toInt() // only for display
                MPVLib.command(arrayOf("seek", seekTime.toString(), "relative"))

                val diffText = Utils.prettyTime(seekTime.toInt(), true)
                gestureTextView.text = getString(R.string.ui_seek_distance, Utils.prettyTime(newPos), diffText)
                fadeGestureText()
            }
            PropertyChange.PlayPause -> player.cyclePause()
            PropertyChange.Custom -> {
                val keycode = 0x10002 + diff.toInt()
                MPVLib.command(arrayOf("keypress", "0x%x".format(keycode)))
            }
        }
    }

    // Header HTTP dari intent pembuka (hanya untuk berkas pertama), lihat parseHeadersExtra
    private var intentHeaders: Map<String, String> = emptyMap()

    companion object {
        private const val TAG = "mpv"
        private const val MAX_MEMORY_PLAYLIST_ENTRIES = 1000
        // Hanya skema yang juga diizinkan UrlPolicy.
        private val ALLOWED_CUSTOM_SCHEMES = setOf(
            "http", "https", "rtmp", "rtmps", "rtsp", "content", "file"
        )
        // how long should controls be displayed on screen (ms)
        private const val CONTROLS_DISPLAY_TIMEOUT = 1500L
        // how long controls fade to disappear (ms)
        private const val CONTROLS_FADE_DURATION = 500L
        // smallest aspect ratio that is considered non-square
        private const val ASPECT_RATIO_MIN = 1.2f // covers 5:4 and up
        private const val SPECTRUM_HEIGHT_DP = 96 // [SPECTRUM] samakan dengan tinggi spectrumView di player.xml
        // fraction to which audio volume is ducked on loss of audio focus
        private const val AUDIO_FOCUS_DUCKING = 0.5f
        // action of result intent
        private const val RESULT_INTENT = "com.molina.cinema.MPVActivity.result"
        // stream type used with AudioManager
        private const val STREAM_TYPE = AudioManager.STREAM_MUSIC
        // precision used by seekbar (1/s)
        private const val SEEK_BAR_PRECISION = 2
    }
}

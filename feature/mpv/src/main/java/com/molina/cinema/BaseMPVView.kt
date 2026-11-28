package com.molina.cinema

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.File

// Contains only the essential code needed to get a picture on the screen

abstract class BaseMPVView(context: Context, attrs: AttributeSet) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    /**
     * Initialize libmpv.
     *
     * Call this once before the view is shown.
     */
    fun initialize(configDir: String, cacheDir: String) {
        // Karantina mpv.conf/input.conf/scripts berisiko sebelum mpv membacanya (lihat ConfigGuard)
        ConfigGuard.sanitizeConfigDir(File(configDir)).forEach { MpvLog.w("ConfigGuard", it) }
        MPVLib.create(context.applicationContext)
        MpvLifecycle.markCreated()

        /* set normal options (user-supplied config can override) */
        MPVLib.setOptionString("config", "yes")
        MPVLib.setOptionString("config-dir", configDir)
        for (opt in arrayOf("gpu-shader-cache-dir", "icc-cache-dir"))
            MPVLib.setOptionString(opt, cacheDir)
        initOptions()

        MPVLib.init()

        /* set hardcoded options */
        postInitOptions()
        // could mess up VO init before surfaceCreated() is called
        MPVLib.setOptionString("force-window", "no")
        // need to idle at least once for playFile() logic to work
        MPVLib.setOptionString("idle", "once")

        holder.addCallback(this)
        observeProperties()
    }

    /**
     * Deinitialize libmpv.
     *
     * Call this once before the view is destroyed.
     */
    fun destroy() {
        // Disable surface callbacks to avoid using uninitialized mpv state
        holder.removeCallback(this)

        MpvLifecycle.destroyIfAlive()
    }

    protected abstract fun initOptions()
    protected abstract fun postInitOptions()

    protected abstract fun observeProperties()

    private var filePath: String? = null
    private var fileHeaders: Map<String, String> = emptyMap()
    private var fileTitle: String? = null
    @Volatile private var surfaceAttached = false

    /**
     * Set the first file to be played once the player is ready.
     * [headers]: header HTTP untuk entri ini (disaring di MpvLoad); kosong = perilaku lama.
     */
    fun playFile(filePath: String, headers: Map<String, String> = emptyMap(), title: String? = null) {
        this.filePath = filePath
        this.fileHeaders = headers
        this.fileTitle = title
    }

    /**
     * Untuk hasil resolusi asinkron (thread utama): muat sekarang bila surface sudah terpasang,
     * jika belum antre seperti [playFile] agar surfaceCreated() yang memuatnya.
     */
    fun playWhenReady(filePath: String, headers: Map<String, String> = emptyMap(), title: String? = null,
                      after: (() -> Unit)? = null) {
        if (surfaceAttached) {
            MPVLib.command(MpvLoad.loadfileArgs(filePath, MpvLoad.REPLACE, headers, title))
            after?.invoke()
        } else {
            playFile(filePath, headers, title)
            pendingAfter = after
        }
    }

    // Dijalankan sesaat setelah item pertama dimuat (mis. menambah sisa playlist hasil resolver).
    private var pendingAfter: (() -> Unit)? = null

    private var folderFiles: List<String>? = null
    private var folderIndex = 0

    /**
     * Mainkan satu folder sebagai playlist, mulai dari berkas ke-[index].
     */
    fun playFolder(files: List<String>, index: Int) {
        folderFiles = files
        folderIndex = index
    }

    fun loadFolder(files: List<String>, index: Int) {
        MPVLib.command(arrayOf("loadfile", files[index], "replace"))
        files.forEachIndexed { i, f ->
            if (i != index) MPVLib.command(arrayOf("loadfile", f, "append"))
        }
        // berkas terpilih dipindah ke urutan aslinya (setelah entri sebelumnya)
        if (index > 0) MPVLib.command(arrayOf("playlist-move", "0", (index + 1).toString()))
    }

    private var voInUse: String = "gpu"

    /**
     * Sets the VO to use.
     * It is automatically disabled/enabled when the surface dis-/appears.
     */
    fun setVo(vo: String) {
        voInUse = vo
        MPVLib.setOptionString("vo", vo)
    }

    // Surface callbacks

    // [ANR-FIX6] Re-inisialisasi VO (vo=gpu) saat surface kembali bisa memakan ratusan ms di perangkat lemah. Dulu dijalankan
    // sinkron di main thread, sehingga panel kontrol telat muncul saat kembali dari latar. Sekarang di thread khusus.
    // attach/detach tetap sinkron dan saling berurutan lewat surfaceLock; surfaceGen membatalkan tugas yang sudah usang.
    private val surfaceLock = Any()
    private val surfaceExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "molina-mpv-surface").apply { isDaemon = true }
    }
    @Volatile private var surfaceGen = 0

    /** Catat bila bagian ini memblokir main thread >= 100 ms (bukti di logcat). */
    private inline fun timedMain(name: String, block: () -> Unit) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            block()
        } finally {
            val dt = android.os.SystemClock.elapsedRealtime() - t0
            if (dt >= 100L) MpvLog.w(TAG, "[ui-lambat] $name memblokir main thread $dt ms")
        }
    }

    /** [SURFACE-TIME] Waktu per langkah JNI di callback surface (>= 50 ms): menunjuk panggilan mana yang menahan main thread. */
    private inline fun step(name: String, block: () -> Unit) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            block()
        } finally {
            val dt = android.os.SystemClock.elapsedRealtime() - t0
            if (dt >= 50L) MpvLog.w(TAG, "[ui-lambat] langkah $name $dt ms")
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val size = "${width}x$height"
        val gen = surfaceGen
        surfaceExec.execute { // urutan di belakang vo=gpu dari surfaceCreated (antrean yang sama)
            synchronized(surfaceLock) {
                if (surfaceAttached && gen == surfaceGen) MPVLib.setPropertyString("android-surface-size", size)
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        MpvLog.w(TAG, "attaching surface")
        val gen = ++surfaceGen
        timedMain("surfaceCreated") {
            synchronized(surfaceLock) {
                surfaceAttached = true
                step("attachSurface") { MPVLib.attachSurface(holder.surface) } // [SURFACE-TIME]
                // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not
                step("force-window=yes") { MPVLib.setOptionString("force-window", "yes") } // [SURFACE-TIME]
            }

            val folder = folderFiles
            if (folder != null) {
                loadFolder(folder, folderIndex)
                folderFiles = null
            } else if (filePath != null) {
                MPVLib.command(MpvLoad.loadfileArgs(filePath as String, MpvLoad.REPLACE, fileHeaders, fileTitle))
                filePath = null
                fileHeaders = emptyMap()
                fileTitle = null
                pendingAfter?.invoke()
                pendingAfter = null
            } else {
                // We disable video output when the context disappears, enable it back
                surfaceExec.execute {
                    synchronized(surfaceLock) {
                        if (surfaceAttached && gen == surfaceGen) MPVLib.setPropertyString("vo", voInUse)
                    }
                }
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        MpvLog.w(TAG, "detaching surface")
        surfaceGen++ // batalkan vo=gpu / ukuran surface yang masih antre
        timedMain("surfaceDestroyed") {
            val tLock = android.os.SystemClock.elapsedRealtime() // [SURFACE-TIME]
            synchronized(surfaceLock) { // menunggu tugas yang sedang berjalan selesai, lalu detach (harus sinkron: surface segera tidak valid)
                val waited = android.os.SystemClock.elapsedRealtime() - tLock
                if (waited >= 50L) MpvLog.w(TAG, "[ui-lambat] langkah menunggu-kunci $waited ms")
                surfaceAttached = false
                step("vo=null") { MPVLib.setPropertyString("vo", "null") } // [SURFACE-TIME]
                step("force-window=no") { MPVLib.setPropertyString("force-window", "no") }
                // Note that before calling detachSurface() we need to be sure that libmpv
                // is done using the surface.
                // FIXME: There could be a race condition here, because I don't think
                // setting a property will wait for VO deinit.
                step("detachSurface") { MPVLib.detachSurface() }
            }
        }
    }

    companion object {
        private const val TAG = "mpv"
    }
}

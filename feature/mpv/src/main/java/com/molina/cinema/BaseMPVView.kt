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

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        MpvLog.w(TAG, "attaching surface")
        surfaceAttached = true
        MPVLib.attachSurface(holder.surface)
        // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not
        MPVLib.setOptionString("force-window", "yes")

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
            MPVLib.setPropertyString("vo", voInUse)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        MpvLog.w(TAG, "detaching surface")
        surfaceAttached = false
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        // Note that before calling detachSurface() we need to be sure that libmpv
        // is done using the surface.
        // FIXME: There could be a race condition here, because I don't think
        // setting a property will wait for VO deinit.
        MPVLib.detachSurface()
    }

    companion object {
        private const val TAG = "mpv"
    }
}

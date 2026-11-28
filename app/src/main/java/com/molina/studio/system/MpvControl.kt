package com.molina.studio.system

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.molina.cinema.BackgroundPlaybackService
import com.molina.cinema.BackgroundPlaybackState
import com.molina.cinema.MPVActivity
import com.molina.cinema.MpvLifecycle
import java.lang.ref.WeakReference

/**
 * Pelacak dan penghenti pemutar mpv dari luar modul `:feature:mpv`, tanpa mengubah MPVActivity.
 * Pemilik libmpv adalah MPVActivity (onDestroy memanggil player.destroy()), jadi penghentian
 * yang aman adalah finish() lalu menunggu onDestroy, bukan memanggil destroy dari thread lain.
 */
internal object MpvControl : Application.ActivityLifecycleCallbacks {
    @Volatile private var ref: WeakReference<Activity>? = null

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    /** true bila MPVActivity masih ada atau layanan pemutaran latar berjalan. */
    fun isActive(): Boolean = ref?.get() != null || BackgroundPlaybackState.active.value

    /**
     * Hentikan pemutar. Blocking (maks ±3 dtk): panggil dari thread latar.
     * Mengembalikan true bila ada yang dihentikan.
     */
    fun shutdown(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (!isActive()) return false
        val act = ref?.get()
        if (act != null) {
            Handler(Looper.getMainLooper()).post { if (!act.isFinishing) act.finish() }
        }
        try { app.stopService(Intent(app, BackgroundPlaybackService::class.java)) } catch (e: Exception) { }

        val deadline = SystemClock.elapsedRealtime() + WAIT_MS
        while (ref?.get() != null && SystemClock.elapsedRealtime() < deadline) {
            try { Thread.sleep(50) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        // Hanya bila Activity sudah hancur: destroyIfAlive aman dipanggil ulang (no-op bila sudah).
        if (ref?.get() == null) {
            try { MpvLifecycle.destroyIfAlive() } catch (e: Throwable) { }
        }
        return true
    }

    private const val WAIT_MS = 3000L

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is MPVActivity) ref = WeakReference(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity === ref?.get()) ref = null
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}

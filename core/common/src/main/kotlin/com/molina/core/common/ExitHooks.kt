package com.molina.core.common

/** Pembersihan satu modul saat aplikasi keluar; dijalankan di thread latar sebelum proses dibunuh. */
fun interface ExitHook {
    fun onExit()
}

/**
 * Registri hook keluar. Modul mendaftar di sini; :app (AppExit) memanggil [runAll] saat keluar.
 * Contoh: ExitHooks.register("mpv") { MPVLib.destroy() }
 */
object ExitHooks {
    private val hooks = LinkedHashMap<String, ExitHook>()

    @Synchronized
    fun register(name: String, hook: ExitHook) {
        hooks[name] = hook
    }

    @Synchronized
    fun unregister(name: String) {
        hooks.remove(name)
    }

    @Synchronized
    private fun snapshot(): List<ExitHook> = hooks.values.toList()

    /** Jalankan semua hook berurutan; kegagalan satu hook tidak menghentikan yang lain. */
    fun runAll() {
        for (h in snapshot()) {
            try { h.onExit() } catch (e: Throwable) {}
        }
    }
}

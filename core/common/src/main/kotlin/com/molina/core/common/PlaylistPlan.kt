package com.molina.core.common

/**
 * Playlist hasil pencarian yang diserahkan Cinema Home ke pemutar lewat extra Intent.
 * Murni Kotlin (tanpa Android) agar bisa diuji di JVM. Pemutar juga dapat dipanggil aplikasi lain,
 * jadi semua masukan divalidasi: hanya URL tonton YouTube https, 2..[MAX_ITEMS] item, indeks
 * harus valid, dan item pada indeks harus sama dengan URL yang sedang dibuka.
 */
class PlaylistPlan private constructor(
    val urls: List<String>,
    val titles: List<String>,
    val index: Int,
) {
    fun titleAt(i: Int): String = titles.getOrElse(i) { "" }

    companion object {
        const val MAX_ITEMS = 50
        const val MAX_TITLE_LENGTH = 200
        private val WATCH = Regex("^https://(www\\.|m\\.)?youtube\\.com/watch\\?v=[A-Za-z0-9_-]{11}$")

        fun isWatchUrl(url: String): Boolean = WATCH.matches(url)

        /** Null bila ada satu saja aturan yang dilanggar. Judul yang tidak sepadan jadi kosong. */
        fun parse(urls: List<String>?, titles: List<String>?, index: Int, currentUrl: String?): PlaylistPlan? {
            if (urls == null || currentUrl == null) return null
            if (urls.size < 2 || urls.size > MAX_ITEMS) return null
            if (index < 0 || index >= urls.size) return null
            if (urls[index] != currentUrl) return null
            if (!urls.all { isWatchUrl(it) }) return null
            if (urls.toSet().size != urls.size) return null
            val t = if (titles != null && titles.size == urls.size)
                titles.map { it.take(MAX_TITLE_LENGTH) } else List(urls.size) { "" }
            return PlaylistPlan(urls.toList(), t, index)
        }
    }
}

/**
 * Jendela bergulir: hanya satu item di depan yang disiapkan pada satu waktu. Item terpilih diputar
 * lebih dulu; saat ia mulai, pemutar meresolve urutan berikutnya di latar lalu menambahkannya.
 * Item yang gagal dilewati. Kelas ini tidak thread-safe: panggil dari thread utama.
 */
class RollingQueue(private val plan: PlaylistPlan) {
    private var cursor = plan.index + 1
    private var inFlight = false
    var appended = 0; private set
    var skipped = 0; private set

    /** Indeks item yang menunggu giliran disiapkan, atau null bila daftar habis. */
    fun pending(): Int? = if (cursor < plan.urls.size) cursor else null

    /** True bila tidak ada pekerjaan berjalan, masih ada item, dan playlist mpv tak punya item sesudah posisi kini. */
    fun needsPrefetch(mpvCount: Int, mpvPos: Int): Boolean =
        !inFlight && pending() != null && mpvPos >= 0 && mpvCount - mpvPos - 1 < 1

    /** Tandai mulai menyiapkan [pending]; mengembalikan indeksnya, null bila tak perlu. */
    fun begin(mpvCount: Int, mpvPos: Int): Int? {
        if (!needsPrefetch(mpvCount, mpvPos)) return null
        inFlight = true
        return cursor
    }

    fun succeeded() { if (inFlight) { appended++; cursor++; inFlight = false } }

    /** Gagal: lewati item ini. Mengembalikan true bila masih ada item untuk dicoba segera. */
    fun failed(): Boolean {
        if (!inFlight) return false
        skipped++; cursor++; inFlight = false
        return pending() != null
    }

    /** True bila sedang menyiapkan item (prefetch otomatis atau lompat dari dialog). */
    val busy: Boolean get() = inFlight

    /** Indeks item yang belum disiapkan, berurutan seperti daftar hasil. */
    fun pendingIndices(): List<Int> = (cursor until plan.urls.size).toList()

    /** Seperti [begin] tetapi tanpa syarat posisi mpv: dipakai saat pengguna melompat ke item belum siap. */
    fun beginForced(): Int? {
        if (inFlight || pending() == null) return null
        inFlight = true
        return cursor
    }

    fun url(i: Int): String = plan.urls[i]
    fun title(i: Int): String = plan.titleAt(i)
}

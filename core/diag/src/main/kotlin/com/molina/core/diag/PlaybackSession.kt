package com.molina.core.diag

import java.util.concurrent.atomic.AtomicInteger

/**
 * ID satu sesi pemutaran (satu START_FILE sampai END_FILE). Dipakai sebagai awalan log supaya
 * baris dari thread event mpv, UI, dan layanan latar bisa ditelusuri ke pemutaran yang sama.
 * JVM murni: tidak bergantung pada Android maupun libmpv.
 */
@JvmInline
value class PlaybackSessionId(val value: Int) {
    /** Bentuk pendek untuk log, mis. "s007". */
    val label: String get() = "s" + value.toString().padStart(3, '0')
}

/**
 * Pencatat sesi pemutaran aktif. Paling banyak satu sesi aktif; [begin] mengganti sesi yang
 * belum diakhiri. [end] hanya berhasil untuk sesi yang masih aktif, sehingga END_FILE ganda
 * atau terlambat tidak menutup sesi yang lebih baru. Aman dipanggil dari thread mana pun.
 */
object PlaybackSessions {
    /** Pencacah melingkar 1..[MAX_ID] agar label tetap pendek dan 0 tetap berarti "tidak ada". */
    const val MAX_ID = 999_999

    private val counter = AtomicInteger(0)
    private val active = AtomicInteger(0)

    /** Mulai sesi baru dan jadikan aktif. */
    fun begin(): PlaybackSessionId {
        val id = counter.updateAndGet { if (it >= MAX_ID) 1 else it + 1 }
        active.set(id)
        return PlaybackSessionId(id)
    }

    /** Sesi aktif, atau null bila tidak ada. */
    fun current(): PlaybackSessionId? = active.get().takeIf { it != 0 }?.let { PlaybackSessionId(it) }

    /** Akhiri [id] bila masih aktif. Mengembalikan false untuk sesi yang sudah diganti atau diakhiri. */
    fun end(id: PlaybackSessionId): Boolean = active.compareAndSet(id.value, 0)

    /** Awalan log "[s007] " untuk sesi aktif, atau "" bila tidak ada sesi. */
    fun prefix(): String = current()?.let { "[${it.label}] " } ?: ""

    /** Hanya untuk tes: kembalikan ke keadaan awal. */
    internal fun resetForTest() {
        counter.set(0)
        active.set(0)
    }
}

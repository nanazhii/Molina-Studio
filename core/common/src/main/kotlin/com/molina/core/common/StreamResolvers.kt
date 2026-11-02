package com.molina.core.common

/**
 * Pintu tunggal ke resolver stream aktif. :app memasang pembuat malas sekali (MolinaApp); pemutar
 * hanya mengenal kontrak [StreamResolver], jadi engine bisa diganti tanpa menyentuh pemutar.
 */
object StreamResolvers {
    @Volatile private var factory: (() -> StreamResolver)? = null
    @Volatile private var instance: StreamResolver? = null

    /** Lepas instance resolver; dibuat ulang malas saat [get] dipanggil lagi. Agar objek engine bisa di-GC. */
    fun release() {
        synchronized(this) { instance = null }
    }

    fun install(factory: () -> StreamResolver) {
        synchronized(this) {
            this.factory = factory
            instance = null
        }
    }

    /**
     * Resolver aktif, dibuat saat pertama dipakai. Panggil dari thread latar: inisialisasi engine
     * memuat banyak kelas. Null bila belum ada yang dipasang.
     */
    fun get(): StreamResolver? {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val f = factory ?: return null
            return f().also { instance = it }
        }
    }
}

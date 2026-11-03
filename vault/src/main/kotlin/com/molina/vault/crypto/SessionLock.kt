package com.molina.vault.crypto

/** Kunci vault hanya hidup di memori selama sesi terbuka; di-wipe saat terkunci. */
object SessionLock {
    @Volatile private var key: ByteArray? = null

    val isUnlocked: Boolean get() = key != null

    @Synchronized fun begin(vaultKey: ByteArray) {
        lock()
        key = vaultKey
    }

    @Synchronized fun lock() {
        key?.fill(0)
        key = null
    }

    // MOLINA-H8-ROTATE
    /**
     * Rotasi kunci: [block] menerima kunci lama dan mengembalikan kunci baru. Seluruh proses memegang kunci
     * monitor, jadi auto-lock tidak bisa menyela dan sesi tidak pernah terbuka kembali sendiri.
     * Jika [block] melempar, kunci lama tetap dipakai; [block] wajib me-wipe kunci baru yang gagal dikomit.
     */
    @Synchronized fun rotate(block: (ByteArray) -> ByteArray) {
        val old = key ?: throw IllegalStateException("Vault terkunci")
        val fresh = block(old)
        old.fill(0)
        key = fresh
    }

    @Synchronized fun <T> use(block: (ByteArray) -> T): T {
        val k = key ?: throw IllegalStateException("Vault terkunci")
        return block(k)
    }
}

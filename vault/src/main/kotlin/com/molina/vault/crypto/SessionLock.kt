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

    @Synchronized fun <T> use(block: (ByteArray) -> T): T {
        val k = key ?: throw IllegalStateException("Vault terkunci")
        return block(k)
    }
}

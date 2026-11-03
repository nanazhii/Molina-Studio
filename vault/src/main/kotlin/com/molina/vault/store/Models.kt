package com.molina.vault.store

import com.molina.vault.crypto.InvalidVaultException
import java.nio.ByteBuffer

enum class RecordType { PASSWORD, NOTE, KEYSTORE, SECRET, FILE }

data class RecordMeta(val id: String, val type: RecordType, val title: String, val modifiedAt: Long)

data class VaultListing(val items: List<RecordMeta>, val corrupt: Int)

/** Format biner [jumlah][len|bytes]...; aman untuk file keystore dan data apa pun. */
object PayloadCodec {
    fun pack(fields: List<ByteArray>): ByteArray {
        var size = 4
        for (f in fields) size += 4 + f.size
        val bb = ByteBuffer.allocate(size).putInt(fields.size)
        for (f in fields) bb.putInt(f.size).put(f)
        return bb.array()
    }

    /** Pemanggil wajib me-wipe (fill(0)) setiap ByteArray hasil setelah dipakai. */
    fun unpack(data: ByteArray): List<ByteArray> {
        val bb = ByteBuffer.wrap(data)
        if (bb.remaining() < 4) throw InvalidVaultException("Payload rusak")
        val n = bb.int
        if (n < 0 || n > 64) throw InvalidVaultException("Payload rusak")
        return List(n) {
            if (bb.remaining() < 4) throw InvalidVaultException("Payload rusak")
            val len = bb.int
            if (len < 0 || len > bb.remaining()) throw InvalidVaultException("Payload rusak")
            ByteArray(len).also { bb.get(it) }
        }
    }
}

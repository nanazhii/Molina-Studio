package com.molina.vault.store

// MOLINA-H15-MANIFEST
// Format dan penghitung tag manifest tersegel. Murni JVM (tanpa android.*), diuji di scripts/vault-tests.

import com.molina.vault.crypto.InvalidVaultException
import com.molina.vault.crypto.StreamCrypto
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

const val MANIFEST_NAME = "manifest.mvm"

class ManifestEntry(val id: String, val tag: ByteArray, val title: String)

class ManifestData(var counter: Long, val entries: LinkedHashMap<String, ManifestEntry>)

/**
 * Berkas: "MVMF"(4) | versi=1(1) | tersegel AES-256-GCM (kunci turunan vaultKey, AAD "manifest|vault").
 * Isi: penghitung(8) | jumlah(4) | { id(36 ASCII) | tag(32) | panjangJudul(2) | judul UTF-8 }*
 * tag = SHA-256(label | panjangBerkas | versi | meta tersegel | [small tersegel v3 | IV data v2] | 16 byte terakhir berkas).
 * IV dan tag GCM acak per penyegelan, jadi setiap penulisan ulang record mengubah tag; menukar atau menyambung
 * potongan dari versi lama (yang masing-masing lolos GCM) tetap terdeteksi.
 */
object ManifestCodec {
    val MAGIC = byteArrayOf(0x4D, 0x56, 0x4D, 0x46) // "MVMF"
    const val VERSION: Byte = 1
    private const val MAX_ENTRIES = 100_000
    private const val ID_LEN = 36
    private const val TAG_LEN = 32
    private const val MAX_TITLE_CHARS = 200
    private const val MIN_SEALED = 29

    fun encode(d: ManifestData): ByteArray {
        require(d.entries.size <= MAX_ENTRIES) { "Terlalu banyak entri" }
        val titles = d.entries.values.map { it.title.take(MAX_TITLE_CHARS).toByteArray(Charsets.UTF_8) }
        var size = 8 + 4
        for (t in titles) size += ID_LEN + TAG_LEN + 2 + t.size
        val bb = ByteBuffer.allocate(size).putLong(d.counter).putInt(d.entries.size)
        for ((e, t) in d.entries.values.zip(titles)) {
            require(e.id.length == ID_LEN && e.tag.size == TAG_LEN) { "Entri manifest tidak valid" }
            bb.put(e.id.toByteArray(Charsets.US_ASCII)).put(e.tag).putShort(t.size.toShort()).put(t)
        }
        return bb.array()
    }

    fun decode(b: ByteArray): ManifestData {
        val bb = ByteBuffer.wrap(b)
        if (bb.remaining() < 12) throw InvalidVaultException("Manifest rusak")
        val counter = bb.long
        val n = bb.int
        if (counter < 0 || n < 0 || n > MAX_ENTRIES) throw InvalidVaultException("Manifest rusak")
        val map = LinkedHashMap<String, ManifestEntry>()
        repeat(n) {
            if (bb.remaining() < ID_LEN + TAG_LEN + 2) throw InvalidVaultException("Manifest rusak")
            val idb = ByteArray(ID_LEN).also { bb.get(it) }
            val tag = ByteArray(TAG_LEN).also { bb.get(it) }
            val tl = bb.short.toInt() and 0xFFFF
            if (tl > MAX_TITLE_CHARS * 4 || tl > bb.remaining()) throw InvalidVaultException("Manifest rusak")
            val tb = ByteArray(tl).also { bb.get(it) }
            val id = String(idb, Charsets.US_ASCII)
            if (map.put(id, ManifestEntry(id, tag, String(tb, Charsets.UTF_8))) != null) throw InvalidVaultException("Manifest rusak: id ganda")
        }
        if (bb.hasRemaining()) throw InvalidVaultException("Manifest rusak: data berlebih")
        return ManifestData(counter, map)
    }

    /** Hanya membaca kepala dan ekor berkas; murah walau record berukuran ratusan MB. Melempar InvalidVaultException bila bentuknya tidak wajar. */
    fun fileTag(f: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("molina-vault/manifest-tag/v1".toByteArray(Charsets.UTF_8))
        RandomAccessFile(f, "r").use { r ->
            val len = r.length()
            if (len < 9L + MIN_SEALED * 2) throw InvalidVaultException("Record terlalu pendek")
            md.update(ByteBuffer.allocate(8).putLong(len).array())
            r.readFully(ByteArray(4))
            val ver = r.readByte()
            val n = r.readInt()
            if (ver != 2.toByte() && ver != 3.toByte()) throw InvalidVaultException("Versi record tidak dikenal")
            if (n < MIN_SEALED || n > 65_536 || 9L + n + MIN_SEALED > len) throw InvalidVaultException("Record rusak")
            val meta = ByteArray(n).also { r.readFully(it) }
            md.update(byteArrayOf(ver))
            md.update(meta)
            if (ver == 3.toByte()) {
                val m = r.readInt()
                if (m < MIN_SEALED || m > 1024 * 1024 + 64 || r.filePointer + m + StreamCrypto.HEADER_SIZE + StreamCrypto.TAG_SIZE > len) {
                    throw InvalidVaultException("Record rusak")
                }
                md.update(ByteArray(m).also { r.readFully(it) })
            } else {
                md.update(ByteArray(12).also { r.readFully(it) })
            }
            val tail = ByteArray(16)
            r.seek(len - 16)
            r.readFully(tail)
            md.update(tail)
        }
        return md.digest()
    }
}

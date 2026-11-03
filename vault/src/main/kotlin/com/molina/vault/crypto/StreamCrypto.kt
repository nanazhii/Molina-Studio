package com.molina.vault.crypto

// MOLINA-H10-STREAM
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Enkripsi streaming per chunk untuk berkas besar (record v3). Murni JVM: tidak memakai Keystore/android.*
 * sehingga bisa diuji unit. Memori tetap O(chunk) berapa pun ukuran berkas.
 *
 * Format aliran:
 *   header(32) = "MVST"(4) | versi(1) | ukuranChunk(4) | salt(16) | prefiksNonce(7)
 *   chunk*     = AES-256-GCM(isi chunk) + tag(16); semua chunk berukuran penuh kecuali yang terakhir
 *
 * Konstruksi STREAM (seperti Tink/libsodium secretstream):
 *   - kunci aliran = HKDF-SHA256(salt = salt acak, ikm = vaultKey, info = domain | aad) -> unik per aliran
 *   - nonce chunk  = prefiks(7) | indeks(4, big-endian) | penanda terakhir(1)
 *   - AAD chunk    = header | aad pemanggil (mis. id record)
 * Akibatnya chunk yang ditukar, digandakan, atau dipotong di ujung ketahuan: indeks dan penanda
 * terakhir ikut mengautentikasi. Aliran selalu punya tepat satu chunk terakhir (boleh kosong).
 */
object StreamCrypto {
    const val CHUNK_SIZE = 64 * 1024
    const val MAX_STREAM_BYTES = 256L * 1024 * 1024
    const val HEADER_SIZE = 4 + 1 + 4 + 16 + 7
    const val TAG_SIZE = 16
    private const val MIN_CHUNK = 1024
    private const val MAX_CHUNK = 1024 * 1024
    private const val VERSION: Byte = 1
    private val MAGIC = byteArrayOf(0x4D, 0x56, 0x53, 0x54) // "MVST"
    private val DOMAIN = "molina-vault/stream/v1|".toByteArray(Charsets.UTF_8)
    private val rng = SecureRandom()

    /** Hasil proses: jumlah byte isi (tanpa overhead) dan SHA-256 isi, untuk fingerprint di metadata. */
    class Result(val plainBytes: Long, val sha256: ByteArray)

    /** Ukuran aliran terenkripsi untuk isi [plainBytes] (berguna untuk cek ruang kosong sebelum menulis). */
    fun encryptedSize(plainBytes: Long, chunkSize: Int = CHUNK_SIZE): Long {
        require(plainBytes >= 0 && chunkSize in MIN_CHUNK..MAX_CHUNK) { "Parameter tidak valid" }
        val chunks = if (plainBytes == 0L) 1L else (plainBytes + chunkSize - 1) / chunkSize
        return HEADER_SIZE + plainBytes + chunks * TAG_SIZE
    }

    fun encrypt(
        vaultKey: ByteArray,
        aad: ByteArray,
        input: InputStream,
        out: OutputStream,
        chunkSize: Int = CHUNK_SIZE,
        maxBytes: Long = MAX_STREAM_BYTES,
    ): Result {
        require(vaultKey.size == 32) { "vaultKey harus 32 byte" }
        require(chunkSize in MIN_CHUNK..MAX_CHUNK) { "Ukuran chunk tidak valid" }
        val salt = ByteArray(16).also(rng::nextBytes)
        val prefix = ByteArray(7).also(rng::nextBytes)
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(MAGIC).put(VERSION).putInt(chunkSize).put(salt).put(prefix).array()
        val key = streamKey(vaultKey, salt, aad)
        val sha = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(chunkSize)
        var total = 0L
        try {
            val fullAad = header + aad
            out.write(header)
            var index = 0
            var pending = readFully(input, buf, 0, chunkSize)
            var lookahead = if (pending == chunkSize) input.read() else -1
            while (true) {
                val last = lookahead < 0
                total += pending
                if (total > maxBytes) throw InvalidVaultException("Berkas melebihi batas ukuran")
                sha.update(buf, 0, pending)
                out.write(seal(key, prefix, index, last, fullAad, buf, pending))
                if (last) break
                // chunk berikutnya diawali byte intip; sisanya dibaca sesudahnya
                buf[0] = lookahead.toByte()
                val more = readFully(input, buf, 1, chunkSize - 1)
                pending = 1 + more
                lookahead = if (pending == chunkSize) input.read() else -1
                index++
                if (index < 0) throw InvalidVaultException("Terlalu banyak chunk")
            }
            out.flush()
            return Result(total, sha.digest())
        } finally {
            key.fill(0)
            buf.fill(0)
        }
    }

    fun decrypt(
        vaultKey: ByteArray,
        aad: ByteArray,
        input: InputStream,
        out: OutputStream,
        maxBytes: Long = MAX_STREAM_BYTES,
    ): Result {
        require(vaultKey.size == 32) { "vaultKey harus 32 byte" }
        val header = ByteArray(HEADER_SIZE)
        if (readFully(input, header, 0, HEADER_SIZE) != HEADER_SIZE) throw InvalidVaultException("Aliran terpotong")
        val bb = ByteBuffer.wrap(header)
        val magic = ByteArray(4).also { bb.get(it) }
        if (!magic.contentEquals(MAGIC)) throw InvalidVaultException("Bukan aliran Molina Vault")
        if (bb.get() != VERSION) throw InvalidVaultException("Versi aliran tidak didukung")
        val chunkSize = bb.int
        if (chunkSize !in MIN_CHUNK..MAX_CHUNK) throw InvalidVaultException("Ukuran chunk tidak valid")
        val salt = ByteArray(16).also { bb.get(it) }
        val prefix = ByteArray(7).also { bb.get(it) }
        val key = streamKey(vaultKey, salt, aad)
        val sha = MessageDigest.getInstance("SHA-256")
        val frame = chunkSize + TAG_SIZE
        val buf = ByteArray(frame)
        var plain: ByteArray? = null
        var total = 0L
        try {
            val fullAad = header + aad
            var index = 0
            var pending = readFully(input, buf, 0, frame)
            var lookahead = if (pending == frame) input.read() else -1
            while (true) {
                val last = lookahead < 0
                if (!last && pending != frame) throw InvalidVaultException("Aliran rusak")
                if (pending < TAG_SIZE) throw InvalidVaultException("Aliran terpotong")
                val p = open(key, prefix, index, last, fullAad, buf, pending)
                plain = p
                total += p.size
                if (total > maxBytes) throw InvalidVaultException("Berkas melebihi batas ukuran")
                sha.update(p)
                out.write(p)
                p.fill(0)
                plain = null
                if (last) break
                buf[0] = lookahead.toByte()
                val more = readFully(input, buf, 1, frame - 1)
                pending = 1 + more
                lookahead = if (pending == frame) input.read() else -1
                index++
                if (index < 0) throw InvalidVaultException("Terlalu banyak chunk")
            }
            out.flush()
            return Result(total, sha.digest())
        } finally {
            key.fill(0)
            buf.fill(0)
            plain?.fill(0)
        }
    }

    // MOLINA-H10B-WRITER
    /**
     * OutputStream yang mengenkripsi aliran per chunk (setara [encrypt], tetapi didorong dari sisi penulis).
     * Dipakai untuk menyambung dekripsi -> enkripsi tanpa menampung berkas (mis. rotasi kunci).
     * WAJIB di-close() agar chunk terakhir tertulis; close() TIDAK menutup [out]. result() hanya valid sesudah close().
     */
    class Writer(
        vaultKey: ByteArray,
        aad: ByteArray,
        private val out: OutputStream,
        private val chunkSize: Int = CHUNK_SIZE,
        private val maxBytes: Long = MAX_STREAM_BYTES,
    ) : OutputStream() {
        private val prefix = ByteArray(7)
        private val fullAad: ByteArray
        private val key: ByteArray
        private val sha = MessageDigest.getInstance("SHA-256")
        private val buf: ByteArray
        private var filled = 0
        private var index = 0
        private var total = 0L
        private var closed = false
        private var digest: ByteArray? = null

        init {
            require(vaultKey.size == 32) { "vaultKey harus 32 byte" }
            require(chunkSize in MIN_CHUNK..MAX_CHUNK) { "Ukuran chunk tidak valid" }
            val salt = ByteArray(16).also(rng::nextBytes)
            rng.nextBytes(prefix)
            val header = ByteBuffer.allocate(HEADER_SIZE)
                .put(MAGIC).put(VERSION).putInt(chunkSize).put(salt).put(prefix).array()
            key = streamKey(vaultKey, salt, aad)
            fullAad = header + aad
            buf = ByteArray(chunkSize)
            out.write(header)
        }

        override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "Writer sudah ditutup" }
            var o = off
            var left = len
            while (left > 0) {
                if (filled == chunkSize) flushChunk(false)
                val n = minOf(left, chunkSize - filled)
                total += n
                if (total > maxBytes) throw InvalidVaultException("Berkas melebihi batas ukuran")
                System.arraycopy(b, o, buf, filled, n)
                filled += n; o += n; left -= n
            }
        }

        private fun flushChunk(last: Boolean) {
            sha.update(buf, 0, filled)
            out.write(seal(key, prefix, index, last, fullAad, buf, filled))
            filled = 0
            if (!last) {
                index++
                if (index < 0) throw InvalidVaultException("Terlalu banyak chunk")
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                flushChunk(true)
                out.flush()
                digest = sha.digest()
            } finally {
                key.fill(0)
                buf.fill(0)
            }
        }

        fun result(): Result {
            val d = digest
            check(closed && d != null) { "result() hanya valid sesudah close() yang berhasil" }
            return Result(total, d)
        }
    }

    // ---- internal ----

    private fun seal(key: ByteArray, prefix: ByteArray, index: Int, last: Boolean, aad: ByteArray, buf: ByteArray, len: Int): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(prefix, index, last)))
        c.updateAAD(aad)
        return c.doFinal(buf, 0, len)
    }

    private fun open(key: ByteArray, prefix: ByteArray, index: Int, last: Boolean, aad: ByteArray, buf: ByteArray, len: Int): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(prefix, index, last)))
        c.updateAAD(aad)
        try {
            return c.doFinal(buf, 0, len)
        } catch (e: GeneralSecurityException) {
            throw WrongPasswordOrTamperedException()
        }
    }

    private fun nonce(prefix: ByteArray, index: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(index).put(if (last) 1 else 0).array()

    /** Membaca sampai [len] byte atau EOF; mengembalikan jumlah yang terbaca. */
    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int): Int {
        var n = 0
        while (n < len) {
            val r = input.read(buf, off + n, len - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /** HKDF-SHA256 (RFC 5869) satu blok: salt acak per aliran, info = domain | aad. */
    private fun streamKey(vaultKey: ByteArray, salt: ByteArray, aad: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(vaultKey)
        try {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(DOMAIN)
            mac.update(aad)
            mac.update(0x01.toByte())
            return mac.doFinal()
        } finally {
            prk.fill(0)
        }
    }
}

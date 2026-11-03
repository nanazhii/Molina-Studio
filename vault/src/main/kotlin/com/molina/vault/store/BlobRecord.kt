package com.molina.vault.store

// MOLINA-H10-STREAM
import com.molina.vault.crypto.InvalidVaultException
import com.molina.vault.crypto.StreamCrypto
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Penyegel field kecil record (meta, small). Di Vault diisi VaultCrypto; di uji JVM diisi AES-GCM sederhana. */
interface RecordSealer {
    fun seal(kind: String, id: String, plain: ByteArray): ByteArray
    /** Melempar WrongPasswordOrTamperedException/InvalidVaultException bila kunci, id, atau isi tidak cocok. */
    fun open(kind: String, id: String, sealed: ByteArray): ByteArray
}

/** Deskriptor blob: indeks field yang berisi blob, panjang isi, dan SHA-256 isi (untuk fingerprint tanpa membuka blob). */
class BlobInfo(val index: Int, val size: Long, val sha256: ByteArray) {
    fun encode(): ByteArray = ByteBuffer.allocate(SIZE).put(index.toByte()).putLong(size).put(sha256).array()

    companion object {
        const val SIZE = 1 + 8 + 32
        fun decode(b: ByteArray): BlobInfo {
            if (b.size != SIZE) throw InvalidVaultException("Info blob rusak")
            val bb = ByteBuffer.wrap(b)
            val idx = bb.get().toInt() and 0xFF
            val size = bb.long
            if (size < 0 || size > StreamCrypto.MAX_STREAM_BYTES) throw InvalidVaultException("Ukuran blob tidak wajar")
            val sha = ByteArray(32).also { bb.get(it) }
            return BlobInfo(idx, size, sha)
        }
    }
}

class BlobHead(val sealedMeta: ByteArray, val sealedSmall: ByteArray) {
    /** Posisi bidang panjang "small" (tepat setelah meta). */
    val smallOffset: Long get() = (9 + sealedMeta.size).toLong()
    /** Posisi awal aliran terenkripsi. */
    val streamOffset: Long get() = smallOffset + 4 + sealedSmall.size
}

class BlobOpened(val info: BlobInfo, val fields: List<ByteArray>)

/**
 * Record v3 untuk berkas besar. Satu berkas, atomik:
 *   "MVRC"(4) | 3(1) | panjangMeta(4) | meta tersegel | panjangSmall(4) | small tersegel | aliran StreamCrypto
 * Awal berkas sama dengan v2 (magic, versi, panjang meta, meta) sehingga pembaca meta v2 hanya perlu menerima versi 3.
 * "small" = PayloadCodec[ BlobInfo, field... ] dengan field blob dikosongkan; blob sendiri hanya ada di aliran.
 * Memori selalu O(64 KB), tidak bergantung ukuran blob.
 */
object BlobRecord {
    const val VERSION: Byte = 3
    private val MAGIC = byteArrayOf(0x4D, 0x56, 0x52, 0x43) // "MVRC", sama dengan v2
    private const val MIN_SEALED = 29
    private const val MAX_META = 65_536
    private const val MAX_SMALL_PLAIN = 1024 * 1024
    private const val BLOCK = 64 * 1024
    private val DISCARD = object : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    fun aadBlob(id: String): ByteArray = "blob|$id".toByteArray(Charsets.UTF_8)

    fun isBlobRecord(f: File): Boolean = try {
        FileInputStream(f).use { ins ->
            val h = ByteArray(5)
            var n = 0
            while (n < 5) { val r = ins.read(h, n, 5 - n); if (r < 0) break; n += r }
            n == 5 && h[0] == MAGIC[0] && h[1] == MAGIC[1] && h[2] == MAGIC[2] && h[3] == MAGIC[3] && h[4] == VERSION
        }
    } catch (e: IOException) { false }

    fun readHead(f: File): BlobHead {
        DataInputStream(BufferedInputStream(FileInputStream(f), BLOCK)).use { d ->
            try {
                val magic = ByteArray(4)
                d.readFully(magic)
                if (!magic.contentEquals(MAGIC) || d.readByte() != VERSION) throw InvalidVaultException("Record tidak valid")
                val n = d.readInt()
                if (n < MIN_SEALED || n > MAX_META) throw InvalidVaultException("Record rusak")
                val meta = ByteArray(n).also { d.readFully(it) }
                val m = d.readInt()
                if (m < MIN_SEALED || m > MAX_SMALL_PLAIN + 64) throw InvalidVaultException("Record rusak")
                val small = ByteArray(m).also { d.readFully(it) }
                val head = BlobHead(meta, small)
                if (f.length() < head.streamOffset + StreamCrypto.HEADER_SIZE + StreamCrypto.TAG_SIZE) {
                    throw InvalidVaultException("Record terpotong")
                }
                return head
            } catch (e: EOFException) {
                throw InvalidVaultException("Record terpotong")
            }
        }
    }

    /**
     * Menulis record baru ke [target] secara atomik (tmp -> fsync -> rename). [fields] berisi semua field; field di
     * [blobIndex] diabaikan (blob diambil dari [blob]). Mengembalikan deskriptor blob untuk UI.
     */
    fun write(
        target: File,
        id: String,
        key: ByteArray,
        sealer: RecordSealer,
        metaJson: ByteArray,
        fields: List<ByteArray>,
        blobIndex: Int,
        blob: InputStream,
        maxBytes: Long = StreamCrypto.MAX_STREAM_BYTES,
    ): BlobInfo {
        require(fields.size in 1..62) { "Jumlah field tidak valid" }
        require(blobIndex in fields.indices) { "blobIndex tidak valid" }
        val sealedMeta = sealer.seal("meta", id, metaJson)
        require(sealedMeta.size in MIN_SEALED..MAX_META) { "Metadata tidak valid" }
        val slots = fields.mapIndexed { i, b -> if (i == blobIndex) ByteArray(0) else b }
        val sealedSmall0 = sealSmall(sealer, id, BlobInfo(blobIndex, 0, ByteArray(32)), slots)
        val head = BlobHead(sealedMeta, sealedSmall0)
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            val info = FileOutputStream(tmp).use { fos ->
                val bo = BufferedOutputStream(fos, BLOCK)
                writeHead(bo, sealedMeta, sealedSmall0)
                val w = StreamCrypto.Writer(key, aadBlob(id), bo, maxBytes = maxBytes)
                try { copy(blob, w) } finally { w.close() }
                val r = w.result()
                bo.flush()
                fos.fd.sync()
                BlobInfo(blobIndex, r.plainBytes, r.sha256)
            }
            // Isi sebenarnya (panjang + hash) baru diketahui setelah aliran selesai: tulis ulang bagian "small" di tempat.
            // Ukuran tersegel sama (plaintext berukuran tetap), jadi tidak ada yang bergeser.
            val sealedSmall = sealSmall(sealer, id, info, slots)
            if (sealedSmall.size != sealedSmall0.size) throw IllegalStateException("Ukuran segel berubah")
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.seek(head.smallOffset + 4)
                raf.write(sealedSmall)
                raf.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("Gagal menyimpan")
            return info
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /** Membuka bagian kecil: deskriptor blob dan field non-blob. Pemanggil wajib me-wipe setiap ByteArray di fields. */
    fun openSmall(f: File, id: String, sealer: RecordSealer): BlobOpened = openSmall(readHead(f), id, sealer)

    fun openSmall(head: BlobHead, id: String, sealer: RecordSealer): BlobOpened {
        val plain = sealer.open("small", id, head.sealedSmall)
        try {
            val parts = PayloadCodec.unpack(plain)
            if (parts.size < 2) throw InvalidVaultException("Record rusak")
            val info = BlobInfo.decode(parts[0])
            parts[0].fill(0)
            val fields = parts.drop(1)
            if (info.index !in fields.indices || fields[info.index].isNotEmpty()) {
                fields.forEach { it.fill(0) }
                throw InvalidVaultException("Record rusak")
            }
            return BlobOpened(info, fields)
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Mendekripsi blob ke [out] chunk demi chunk, lalu mencocokkan panjang dan SHA-256 dengan deskriptor.
     * Setiap chunk sudah terautentikasi sebelum ditulis, tetapi bila gagal di tengah [out] bisa berisi bagian awal:
     * pemanggil harus membuang tujuan ekspor saat ada exception.
     */
    fun exportBlob(f: File, id: String, key: ByteArray, sealer: RecordSealer, out: OutputStream): StreamCrypto.Result {
        val head = readHead(f)
        val opened = openSmall(head, id, sealer)
        opened.fields.forEach { it.fill(0) }
        val r = FileInputStream(f).use { fis ->
            fis.channel.position(head.streamOffset)
            StreamCrypto.decrypt(key, aadBlob(id), BufferedInputStream(fis, BLOCK), out)
        }
        if (r.plainBytes != opened.info.size || !MessageDigest.isEqual(r.sha256, opened.info.sha256)) {
            throw InvalidVaultException("Isi tidak cocok dengan metadata")
        }
        return r
    }

    /** Memeriksa seluruh record (meta, small, dan semua chunk) tanpa menyimpan hasilnya. */
    fun verify(f: File, id: String, key: ByteArray, sealer: RecordSealer) {
        val head = readHead(f)
        sealer.open("meta", id, head.sealedMeta).fill(0)
        exportBlob(f, id, key, sealer, DISCARD)
    }

    /** Mengganti meta (folder/tag/judul) tanpa membuka blob: salin sisa berkas ke berkas baru, fsync, lalu rename atomik. */
    fun relabel(f: File, newSealedMeta: ByteArray) {
        require(newSealedMeta.size in MIN_SEALED..MAX_META) { "Metadata tidak valid" }
        val head = readHead(f)
        val tmp = File(f.parentFile, f.name + ".tmp")
        try {
            FileInputStream(f).use { ins ->
                ins.channel.position(head.smallOffset)
                FileOutputStream(tmp).use { fos ->
                    val bo = BufferedOutputStream(fos, BLOCK)
                    bo.write(MAGIC)
                    bo.write(VERSION.toInt())
                    bo.write(int4(newSealedMeta.size))
                    bo.write(newSealedMeta)
                    copy(BufferedInputStream(ins, BLOCK), bo)
                    bo.flush()
                    fos.fd.sync()
                }
            }
            if (!tmp.renameTo(f)) throw IOException("Gagal menyimpan")
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Rotasi kunci: dekripsi dengan kunci lama dan langsung enkripsi ulang dengan kunci baru ke [dst] (mis. staging),
     * lalu verifikasi baca-ulang dengan kunci baru. [src] tidak diubah. Bila gagal, [dst] dihapus.
     */
    fun rotate(
        src: File, dst: File, id: String,
        oldKey: ByteArray, oldSealer: RecordSealer,
        freshKey: ByteArray, freshSealer: RecordSealer,
    ) {
        val head = readHead(src)
        val meta = oldSealer.open("meta", id, head.sealedMeta)
        val small = try {
            oldSealer.open("small", id, head.sealedSmall)
        } catch (e: Throwable) {
            meta.fill(0)
            throw e
        }
        try {
            val parts = PayloadCodec.unpack(small)
            val info = BlobInfo.decode(parts[0])
            parts.forEach { it.fill(0) }
            val newMeta = freshSealer.seal("meta", id, meta)
            val newSmall = freshSealer.seal("small", id, small)
            try {
                FileInputStream(src).use { ins ->
                    ins.channel.position(head.streamOffset)
                    FileOutputStream(dst).use { fos ->
                        val bo = BufferedOutputStream(fos, BLOCK)
                        writeHead(bo, newMeta, newSmall)
                        val w = StreamCrypto.Writer(freshKey, aadBlob(id), bo)
                        val r = try {
                            StreamCrypto.decrypt(oldKey, aadBlob(id), BufferedInputStream(ins, BLOCK), w)
                        } finally {
                            w.close()
                        }
                        if (r.plainBytes != info.size || !MessageDigest.isEqual(r.sha256, info.sha256)) {
                            throw InvalidVaultException("Isi tidak cocok dengan metadata")
                        }
                        bo.flush()
                        fos.fd.sync()
                    }
                }
                verify(dst, id, freshKey, freshSealer)
            } catch (e: Throwable) {
                dst.delete()
                throw e
            }
        } finally {
            meta.fill(0)
            small.fill(0)
        }
    }

    // ---- internal ----

    private fun sealSmall(sealer: RecordSealer, id: String, info: BlobInfo, slots: List<ByteArray>): ByteArray {
        val infoBytes = info.encode()
        val plain = PayloadCodec.pack(listOf(infoBytes) + slots)
        try {
            require(plain.size <= MAX_SMALL_PLAIN) { "Field non-berkas terlalu besar" }
            return sealer.seal("small", id, plain)
        } finally {
            plain.fill(0)
            infoBytes.fill(0)
        }
    }

    private fun writeHead(o: OutputStream, sealedMeta: ByteArray, sealedSmall: ByteArray) {
        o.write(MAGIC)
        o.write(VERSION.toInt())
        o.write(int4(sealedMeta.size))
        o.write(sealedMeta)
        o.write(int4(sealedSmall.size))
        o.write(sealedSmall)
    }

    private fun int4(n: Int): ByteArray = ByteBuffer.allocate(4).putInt(n).array()

    private fun copy(input: InputStream, out: OutputStream) {
        val buf = ByteArray(BLOCK)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } finally {
            buf.fill(0)
        }
    }
}

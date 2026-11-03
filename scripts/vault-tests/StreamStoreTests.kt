// MOLINA-H10-STREAM
import com.molina.vault.crypto.InvalidVaultException
import com.molina.vault.crypto.StreamCrypto
import com.molina.vault.crypto.WrongPasswordOrTamperedException
import com.molina.vault.store.BlobRecord
import com.molina.vault.store.RecordSealer
import com.molina.vault.store.SecureWipe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Penyegel uji: AES-GCM dengan subkunci HMAC(key, "kind|id") dan AAD "kind|id" (mirip VaultCrypto.encryptRecord). */
private class TestSealer(private val key: ByteArray) : RecordSealer {
    private fun sub(tag: String): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(tag.toByteArray())
    }
    override fun seal(kind: String, id: String, plain: ByteArray): ByteArray {
        val tag = "$kind|$id"
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sub(tag), "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(tag.toByteArray())
        return iv + c.doFinal(plain)
    }
    override fun open(kind: String, id: String, sealed: ByteArray): ByteArray {
        if (sealed.size < 28) throw InvalidVaultException("terlalu pendek")
        val tag = "$kind|$id"
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(sub(tag), "AES"), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            c.updateAAD(tag.toByteArray())
            return c.doFinal(sealed, 12, sealed.size - 12)
        } catch (e: GeneralSecurityException) {
            throw WrongPasswordOrTamperedException()
        }
    }
}

private fun rejected(msg: String, body: () -> Unit) {
    try { body() } catch (e: Throwable) {
        if (e is InvalidVaultException || e is WrongPasswordOrTamperedException) return
        throw AssertionError("$msg: dapat ${e::class.simpleName}: ${e.message}")
    }
    throw AssertionError("$msg: tidak ditolak")
}

private fun pattern(n: Int) = ByteArray(n) { ((it * 31) xor (it ushr 8)).toByte() }
private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

private class DigestSink : OutputStream() {
    val md: MessageDigest = MessageDigest.getInstance("SHA-256")
    var count = 0L
    override fun write(b: Int) { md.update(b.toByte()); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { md.update(b, off, len); count += len }
}

private fun generator(total: Long): InputStream = object : InputStream() {
    var left = total
    var x = 0
    override fun read(): Int { if (left <= 0) return -1; left--; return (x++ * 13) and 0xFF }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = minOf(len.toLong(), left).toInt()
        for (i in 0 until n) b[off + i] = ((x++ * 13) and 0xFF).toByte()
        left -= n
        return n
    }
}

private fun generatorDigest(total: Long): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    val g = generator(total)
    val buf = ByteArray(65536)
    while (true) { val n = g.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    return md.digest()
}

private inline fun withDir(body: (File) -> Unit) {
    val d = java.nio.file.Files.createTempDirectory("mvblob").toFile()
    try { body(d) } finally { d.deleteRecursively() }
}

fun stage2Tests() {
    streamWriterTests()
    secureWipeTests()
    blobRecordTests()
}

private fun streamWriterTests() {
    val key = ByteArray(32) { (it * 5 + 1).toByte() }
    val aad = "blob|id-1".toByteArray()
    val chunk = 1024

    fun viaWriter(data: ByteArray, step: Int): ByteArray {
        val o = ByteArrayOutputStream()
        val w = StreamCrypto.Writer(key, aad, o, chunk)
        var i = 0
        while (i < data.size) { val n = minOf(step, data.size - i); w.write(data, i, n); i += n }
        w.close()
        return o.toByteArray()
    }
    fun dec(ct: ByteArray, k: ByteArray = key): ByteArray {
        val o = ByteArrayOutputStream()
        StreamCrypto.decrypt(k, aad, ByteArrayInputStream(ct), o)
        return o.toByteArray()
    }

    test("writer: hasilnya terbaca decrypt() untuk ukuran batas chunk dan pola tulis berbeda") {
        for (n in listOf(0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, 3 * chunk + 17)) {
            for (step in listOf(1, 7, chunk, 5000)) {
                val d = pattern(n)
                expect(dec(viaWriter(d, step)).contentEquals(d), "n=$n step=$step")
            }
        }
    }
    test("writer: ukuran keluaran sama dengan encryptedSize") {
        for (n in listOf(0, 1, chunk, chunk + 1, 4 * chunk)) {
            expectEq(viaWriter(pattern(n), 100).size.toLong(), StreamCrypto.encryptedSize(n.toLong(), chunk), "n=$n")
        }
    }
    test("writer: result() = panjang + SHA-256 dan hanya valid sesudah close") {
        val d = pattern(3000)
        val o = ByteArrayOutputStream()
        val w = StreamCrypto.Writer(key, aad, o, chunk)
        w.write(d)
        expectThrows<IllegalStateException>("sebelum close") { w.result() }
        w.close()
        expectEq(w.result().plainBytes, 3000L, "panjang")
        expect(w.result().sha256.contentEquals(sha(d)), "hash")
    }
    test("writer: tulis sesudah close ditolak, close dua kali aman") {
        val w = StreamCrypto.Writer(key, aad, ByteArrayOutputStream(), chunk)
        w.close()
        w.close()
        expectThrows<IllegalStateException>("tulis") { w.write(1) }
    }
    test("writer: batas maxBytes ditegakkan") {
        val w = StreamCrypto.Writer(key, aad, ByteArrayOutputStream(), chunk, 2000L)
        rejected("lewat batas") { w.write(pattern(3000)) }
    }
    test("writer: dekripsi kunci lama langsung disambung ke Writer kunci baru (rotasi)") {
        val d = pattern(5 * chunk + 9)
        val old = viaWriter(d, 333)
        val fresh = ByteArray(32) { (it * 11 + 7).toByte() }
        val o = ByteArrayOutputStream()
        val w = StreamCrypto.Writer(fresh, aad, o, chunk)
        StreamCrypto.decrypt(key, aad, ByteArrayInputStream(old), w)
        w.close()
        val re = o.toByteArray()
        expect(dec(re, fresh).contentEquals(d), "isi sesudah rotasi")
        rejected("kunci lama") { dec(re, key) }
    }
}

private fun secureWipeTests() {
    test("wipe: menimpa nol per blok pada berkas 80 MiB dengan memori kecil (-Xmx64m)") {
        withDir { dir ->
            val f = File(dir, "big.bin")
            val size = 80L * 1024 * 1024
            java.io.FileOutputStream(f).use { o ->
                val b = ByteArray(65536) { 0xAA.toByte() }
                var left = size
                while (left > 0) { val n = minOf(left, b.size.toLong()).toInt(); o.write(b, 0, n); left -= n }
            }
            SecureWipe.overwrite(f)
            expectEq(f.length(), size, "ukuran tetap")
            java.io.FileInputStream(f).buffered().use { i ->
                val b = ByteArray(65536)
                while (true) {
                    val n = i.read(b); if (n < 0) break
                    for (k in 0 until n) if (b[k].toInt() != 0) throw AssertionError("bukan nol")
                }
            }
        }
    }
    test("wipe: overwriteAndDelete menghapus berkas; berkas yang tidak ada aman; berkas kosong aman") {
        withDir { dir ->
            val f = File(dir, "a.bin"); f.writeBytes(pattern(1000))
            SecureWipe.overwriteAndDelete(f)
            expect(!f.exists(), "harus terhapus")
            SecureWipe.overwriteAndDelete(File(dir, "tidak-ada"))
            val e = File(dir, "kosong"); e.writeBytes(ByteArray(0))
            SecureWipe.overwriteAndDelete(e)
            expect(!e.exists(), "kosong harus terhapus")
        }
    }
}

private fun blobRecordTests() {
    val key = ByteArray(32) { (it * 3 + 9).toByte() }
    val sealer = TestSealer(key)
    val id = "11111111-1111-1111-1111-111111111111"
    val meta = """{"id":"$id","title":"arsip"}""".toByteArray()

    fun put(dir: File, blob: ByteArray, name: String = "arsip.7z", note: String = "catatan", k: ByteArray = key, s: RecordSealer = sealer, rid: String = id): File {
        val f = File(dir, "rec_$rid")
        BlobRecord.write(f, rid, k, s, meta, listOf(name.toByteArray(), ByteArray(0), note.toByteArray()), 1, ByteArrayInputStream(blob))
        return f
    }
    fun fullCheck(f: File, rid: String = id, k: ByteArray = key, s: RecordSealer = sealer): ByteArray {
        val head = BlobRecord.readHead(f)
        s.open("meta", rid, head.sealedMeta)
        BlobRecord.openSmall(f, rid, s).fields.forEach { it.fill(0) }
        val o = ByteArrayOutputStream()
        BlobRecord.exportBlob(f, rid, k, s, o)
        return o.toByteArray()
    }

    test("blob: tulis, buka, ekspor bolak-balik untuk berbagai ukuran") {
        for (n in listOf(0, 1, 65535, 65536, 65537, 3 * 65536 + 5)) {
            withDir { dir ->
                val d = pattern(n)
                val f = put(dir, d)
                val o = BlobRecord.openSmall(f, id, sealer)
                expectEq(o.info.size, n.toLong(), "ukuran n=$n")
                expect(o.info.sha256.contentEquals(sha(d)), "hash n=$n")
                expectEq(String(o.fields[0]), "arsip.7z", "nama")
                expect(o.fields[1].isEmpty(), "slot blob kosong")
                expectEq(String(o.fields[2]), "catatan", "catatan")
                expect(fullCheck(f).contentEquals(d), "isi n=$n")
                val head = BlobRecord.readHead(f)
                expectEq(f.length(), head.streamOffset + StreamCrypto.encryptedSize(n.toLong()), "panjang berkas n=$n")
                expect(!File(dir, "rec_$id.tmp").exists(), "tmp tertinggal")
            }
        }
    }
    test("blob: isBlobRecord mengenali v3 dan menolak v2 / berkas tidak ada") {
        withDir { dir ->
            val f = put(dir, pattern(10))
            expect(BlobRecord.isBlobRecord(f), "v3")
            val v2 = File(dir, "v2"); v2.writeBytes(byteArrayOf(0x4D, 0x56, 0x52, 0x43, 2, 0, 0, 0, 40))
            expect(!BlobRecord.isBlobRecord(v2), "v2 bukan blob")
            expect(!BlobRecord.isBlobRecord(File(dir, "x")), "tidak ada")
        }
    }
    test("blob: kepala terpotong di panjang mana pun ditolak") {
        withDir { dir ->
            val f = put(dir, pattern(100))
            val bytes = f.readBytes()
            val limit = (BlobRecord.readHead(f).streamOffset + StreamCrypto.HEADER_SIZE + StreamCrypto.TAG_SIZE).toInt()
            for (n in 0 until limit) {
                val t = File(dir, "t"); t.writeBytes(bytes.copyOf(n))
                rejected("potong $n") { BlobRecord.readHead(t) }
            }
        }
    }
    test("blob: ubah SATU byte di posisi mana pun ditolak (berkas kecil, menyeluruh)") {
        withDir { dir ->
            val f = put(dir, pattern(100))
            val bytes = f.readBytes()
            for (i in bytes.indices) {
                val t = File(dir, "t")
                t.writeBytes(bytes.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() })
                rejected("flip $i") { fullCheck(t) }
            }
        }
    }
    test("blob: ubah byte pada berkas 200 KB (bertahap) dan potong sebagian ditolak") {
        withDir { dir ->
            val f = put(dir, pattern(200_000))
            val bytes = f.readBytes()
            val idx = (0 until bytes.size step 997).toMutableList().also { l -> for (k in 1..40) l.add(bytes.size - k) }
            for (i in idx) {
                val t = File(dir, "t")
                t.writeBytes(bytes.copyOf().also { it[i] = (it[i].toInt() xor 0x80).toByte() })
                rejected("flip $i") { fullCheck(t) }
            }
            for (cut in listOf(1, 16, 17, 65552, 100_000)) {
                val t = File(dir, "t"); t.writeBytes(bytes.copyOf(bytes.size - cut))
                rejected("potong $cut") { fullCheck(t) }
            }
        }
    }
    test("blob: id lain, kunci lain, dan penyegel lain ditolak") {
        withDir { dir ->
            val f = put(dir, pattern(5000))
            rejected("id lain") { fullCheck(f, rid = "22222222-2222-2222-2222-222222222222") }
            val k2 = ByteArray(32) { 1 }
            rejected("kunci lain") { fullCheck(f, k = k2, s = TestSealer(k2)) }
            rejected("kunci stream lain") { fullCheck(f, k = k2) }
        }
    }
    test("blob: relabel mengganti meta tanpa menyentuh blob, atomik, tanpa tmp") {
        withDir { dir ->
            val d = pattern(150_000)
            val f = put(dir, d)
            val before = BlobRecord.readHead(f)
            val newMeta = """{"id":"$id","title":"arsip","folder":"Kerja","tags":["a","b"]}""".toByteArray()
            BlobRecord.relabel(f, sealer.seal("meta", id, newMeta))
            val after = BlobRecord.readHead(f)
            expect(String(sealer.open("meta", id, after.sealedMeta)).contains("Kerja"), "meta baru")
            expect(before.sealedSmall.contentEquals(after.sealedSmall), "small tidak berubah")
            expect(fullCheck(f).contentEquals(d), "blob utuh")
            expect(!File(dir, "rec_$id.tmp").exists(), "tmp tertinggal")
        }
    }
    test("blob: rotasi kunci memindahkan record ke kunci baru, sumber tidak berubah") {
        withDir { dir ->
            val d = pattern(300_000)
            val src = put(dir, d)
            val srcBytes = src.readBytes()
            val freshKey = ByteArray(32) { (it * 17 + 2).toByte() }
            val freshSealer = TestSealer(freshKey)
            val dst = File(dir, "staged_$id")
            BlobRecord.rotate(src, dst, id, key, sealer, freshKey, freshSealer)
            expect(src.readBytes().contentEquals(srcBytes), "sumber berubah")
            expect(fullCheck(dst, k = freshKey, s = freshSealer).contentEquals(d), "isi sesudah rotasi")
            expect(String(freshSealer.open("meta", id, BlobRecord.readHead(dst).sealedMeta)) == String(meta), "meta")
            rejected("kunci lama pada hasil") { fullCheck(dst) }
        }
    }
    test("blob: rotasi dengan kunci lama salah gagal dan tidak meninggalkan berkas tujuan") {
        withDir { dir ->
            val src = put(dir, pattern(80_000))
            val dst = File(dir, "staged")
            val bad = ByteArray(32) { 5 }
            rejected("kunci lama salah") { BlobRecord.rotate(src, dst, id, bad, TestSealer(bad), key, sealer) }
            expect(!dst.exists(), "tujuan harus dihapus")
        }
    }
    test("blob: kegagalan sumber di tengah jalan tidak merusak record lama dan tidak meninggalkan tmp") {
        withDir { dir ->
            val old = pattern(4000)
            val f = put(dir, old)
            val boom = object : InputStream() {
                var n = 0
                override fun read(): Int = throw IOException("boom")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (n >= 100_000) throw IOException("boom")
                    val k = minOf(len, 30_000); n += k; return k
                }
            }
            expectThrows<IOException>("sumber gagal") {
                BlobRecord.write(f, id, key, sealer, meta, listOf(ByteArray(1), ByteArray(0)), 1, boom)
            }
            expect(fullCheck(f).contentEquals(old), "record lama harus utuh")
            expect(!File(dir, "rec_$id.tmp").exists(), "tmp tertinggal")
        }
    }
    test("blob: batas maxBytes dan parameter tidak valid") {
        withDir { dir ->
            val f = File(dir, "rec_x")
            rejected("lewat batas") {
                BlobRecord.write(f, id, key, sealer, meta, listOf(ByteArray(1), ByteArray(0)), 1, ByteArrayInputStream(pattern(5000)), 1000L)
            }
            expect(!f.exists() && !File(dir, "rec_x.tmp").exists(), "tidak boleh ada berkas")
            expectThrows<IllegalArgumentException>("blobIndex") {
                BlobRecord.write(f, id, key, sealer, meta, listOf(ByteArray(1)), 3, ByteArrayInputStream(ByteArray(1)))
            }
        }
    }
    test("blob: 48 MiB ditulis, dirotasi, dan diekspor dengan memori kecil (-Xmx64m)") {
        withDir { dir ->
            val total = 48L * 1024 * 1024
            val want = generatorDigest(total)
            val f = File(dir, "rec_big")
            val info = BlobRecord.write(f, id, key, sealer, meta, listOf(ByteArray(1), ByteArray(0)), 1, generator(total))
            expectEq(info.size, total, "ukuran")
            expect(info.sha256.contentEquals(want), "hash tulis")
            val freshKey = ByteArray(32) { (it * 19 + 4).toByte() }
            val freshSealer = TestSealer(freshKey)
            val dst = File(dir, "staged_big")
            BlobRecord.rotate(f, dst, id, key, sealer, freshKey, freshSealer)
            val sink = DigestSink()
            BlobRecord.exportBlob(dst, id, freshKey, freshSealer, sink)
            expectEq(sink.count, total, "panjang ekspor")
            expect(sink.md.digest().contentEquals(want), "hash ekspor")
            BlobRecord.relabel(dst, freshSealer.seal("meta", id, """{"id":"$id","folder":"X"}""".toByteArray()))
            BlobRecord.verify(dst, id, freshKey, freshSealer)
        }
    }
}

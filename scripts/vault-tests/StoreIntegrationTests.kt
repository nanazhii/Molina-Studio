// MOLINA-H11-GLUE
// Uji integrasi VaultStore (v2 + v3) dengan Context palsu, VaultCrypto palsu, dan org.json asli (JSON-java).
import android.content.Context
import com.molina.vault.crypto.InvalidVaultException
import com.molina.vault.crypto.SessionLock
import com.molina.vault.crypto.WrongPasswordOrTamperedException
import com.molina.vault.store.InsufficientSpaceException
import com.molina.vault.store.LargeRecordException
import com.molina.vault.store.RecordType
import com.molina.vault.store.VaultStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

internal const val MASTER = "kuda-biru-melompat-pagar-77!"
internal const val NEW_MASTER = "bulan-merah-menari-ladang-91!"

internal fun gen(total: Long): InputStream = object : InputStream() {
    var pos = 0L
    override fun read(): Int = if (pos >= total) -1 else (((pos++ * 131) xor (pos ushr 5)).toInt() and 0xFF)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= total) return -1
        val n = minOf(len.toLong(), total - pos).toInt()
        for (i in 0 until n) { b[off + i] = (((pos * 131) xor (pos ushr 5)).toInt() and 0xFF).toByte(); pos++ }
        return n
    }
}

internal fun genDigest(total: Long): ByteArray {
    val md = MessageDigest.getInstance("SHA-256"); val ins = gen(total); val buf = ByteArray(65536)
    while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    return md.digest()
}

internal class Sink : OutputStream() {
    val md: MessageDigest = MessageDigest.getInstance("SHA-256"); var count = 0L
    override fun write(b: Int) { md.update(b.toByte()); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { md.update(b, off, len); count += len }
}

internal fun withStore(body: (VaultStore, File) -> Unit) {
    val tmp = File.createTempFile("vstore", "").also { it.delete(); it.mkdirs() }
    try {
        SessionLock.lock()
        val st = VaultStore(Context(tmp))
        st.create(MASTER.toCharArray())
        body(st, File(tmp, "vault"))
    } finally { SessionLock.lock(); tmp.deleteRecursively() }
}

private fun recFiles(d: File) = d.listFiles().orEmpty().filter { it.name.startsWith("rec_") }

fun storeTests() {
    test("store: put v2 kecil tetap berjalan dan terdaftar") {
        withStore { st, _ ->
            val m = st.put(RecordType.PASSWORD, "Bank", listOf("u".toByteArray(), "p".toByteArray()))
            expectEq(st.list().items.size, 1, "jumlah")
            expect(!st.isLarge(m.id), "bukan v3")
            expectEq(String(st.read(m.id)[1]), "p", "field")
        }
    }
    test("store: putFile v3 3 MiB, list, openLarge, exportBlob, read() ditolak") {
        withStore { st, _ ->
            val total = 3L * 1024 * 1024
            val r = st.putFile(RecordType.FILE, "arsip.zip", listOf("arsip.zip".toByteArray(), ByteArray(0)), 1, gen(total), expectedBytes = total, folder = "Gudang")
            expect(st.isLarge(r.meta.id), "v3")
            val l = st.list()
            expectEq(l.items.size, 1, "terdaftar"); expectEq(l.corrupt, 0, "tidak korup")
            expectEq(l.items[0].folder, "Gudang", "folder")
            val o = st.openLarge(r.meta.id)
            expectEq(o.info.size, total, "ukuran"); expect(o.info.sha256.contentEquals(genDigest(total)), "sha")
            expectEq(String(o.fields[0]), "arsip.zip", "nama field")
            val s = Sink(); st.exportBlob(r.meta.id, s)
            expectEq(s.count, total, "panjang"); expect(s.md.digest().contentEquals(genDigest(total)), "hash ekspor")
            expectThrows<LargeRecordException>("read v3") { st.read(r.meta.id) }
        }
    }
    test("store: relabel v3 tanpa memuat blob; blob tetap utuh") {
        withStore { st, _ ->
            val total = 2L * 1024 * 1024
            val r = st.putFile(RecordType.FILE, "a.zip", listOf(ByteArray(0)), 0, gen(total), expectedBytes = total)
            st.relabel(r.meta.id, "Arsip", listOf("kerja"))
            val m = st.list().items.single()
            expectEq(m.folder, "Arsip", "folder baru"); expectEq(m.tags, listOf("kerja"), "tag")
            val s = Sink(); st.exportBlob(r.meta.id, s)
            expect(s.md.digest().contentEquals(genDigest(total)), "blob utuh")
        }
    }
    test("store: ganti master merotasi v2 dan v3; master lama ditolak") {
        withStore { st, dir ->
            val v2 = st.put(RecordType.NOTE, "catatan", listOf("isi".toByteArray()))
            val total = 4L * 1024 * 1024
            val v3 = st.putFile(RecordType.FILE, "b.zip", listOf(ByteArray(0)), 0, gen(total), expectedBytes = total)
            expectEq(st.changeMaster(NEW_MASTER.toCharArray()), 0, "terlewati")
            expect(!File(dir, "rotate.tmp").exists(), "staging bersih")
            SessionLock.lock()
            expectThrows<WrongPasswordOrTamperedException>("master lama") { st.unlock(MASTER.toCharArray()) }
            st.unlock(NEW_MASTER.toCharArray())
            expectEq(String(st.read(v2.id)[0]), "isi", "v2")
            val s = Sink(); st.exportBlob(v3.meta.id, s)
            expectEq(s.count, total, "panjang v3"); expect(s.md.digest().contentEquals(genDigest(total)), "hash v3")
        }
    }
    test("store: ruang kurang menolak putFile dan rotasi tanpa meninggalkan sisa") {
        withStore { st, dir ->
            val v3 = st.putFile(RecordType.FILE, "c.zip", listOf(ByteArray(0)), 0, gen(1_000_000), expectedBytes = 1_000_000)
            st.freeSpace = { 1_000L }
            expectThrows<InsufficientSpaceException>("putFile") { st.putFile(RecordType.FILE, "d", listOf(ByteArray(0)), 0, gen(10), expectedBytes = 10) }
            expectThrows<InsufficientSpaceException>("rotasi") { st.changeMaster(NEW_MASTER.toCharArray()) }
            expect(!File(dir, "rotate.tmp").exists(), "staging dibuang")
            expect(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") }, "tanpa .tmp")
            expectEq(recFiles(dir).size, 1, "hanya record awal")
            st.freeSpace = { Long.MAX_VALUE }
            val s = Sink(); st.exportBlob(v3.meta.id, s)   // sesi dan kunci lama masih berlaku
            expectEq(s.count, 1_000_000L, "masih terbaca")
        }
    }
    test("store: aliran gagal di tengah menyisakan record lama dan tanpa .tmp") {
        withStore { st, dir ->
            val id = UUID.randomUUID().toString()
            st.putFile(RecordType.FILE, "e.zip", listOf(ByteArray(0)), 0, gen(300_000), id = id, expectedBytes = 300_000)
            val bad = object : InputStream() {
                var n = 0
                override fun read(): Int = 1
                override fun read(b: ByteArray, off: Int, len: Int): Int { if (n > 200_000) throw IOException("putus"); n += len; b.fill(7, off, off + len); return len }
            }
            expectThrows<IOException>("putus") { st.putFile(RecordType.FILE, "e2.zip", listOf(ByteArray(0)), 0, bad, id = id) }
            expect(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") }, "tanpa .tmp")
            val s = Sink(); st.exportBlob(id, s)
            expect(s.md.digest().contentEquals(genDigest(300_000)), "record lama utuh")
        }
    }
    test("store: ubah satu byte di blob ketahuan saat ekspor; daftar tetap terbaca") {
        withStore { st, dir ->
            val r = st.putFile(RecordType.FILE, "f.zip", listOf(ByteArray(0)), 0, gen(500_000), expectedBytes = 500_000)
            val f = recFiles(dir).single()
            RandomAccessFile(f, "rw").use { it.seek(f.length() - 100); val b = it.read(); it.seek(f.length() - 100); it.write(b xor 1) }
            expectThrows<WrongPasswordOrTamperedException>("ubah byte") { st.exportBlob(r.meta.id, Sink()) }
            expectEq(st.list().items.size, 1, "meta masih terbaca")
        }
    }
    test("store: delete v3 menghapus berkas") {
        withStore { st, dir ->
            val r = st.putFile(RecordType.FILE, "g.zip", listOf(ByteArray(0)), 0, gen(200_000), expectedBytes = 200_000)
            st.delete(r.meta.id)
            expectEq(recFiles(dir).size, 0, "terhapus"); expectEq(st.list().items.size, 0, "daftar kosong")
        }
    }
    test("store: rotasi v3 rusak dilewati (terhitung), yang lain tetap terotasi") {
        withStore { st, dir ->
            val ok = st.put(RecordType.NOTE, "ok", listOf("x".toByteArray()))
            val bad = st.putFile(RecordType.FILE, "rusak.zip", listOf(ByteArray(0)), 0, gen(400_000), expectedBytes = 400_000)
            val f = File(dir, "rec_${bad.meta.id}.mvr")
            RandomAccessFile(f, "rw").use { it.seek(f.length() - 50); val b = it.read(); it.seek(f.length() - 50); it.write(b xor 1) }
            expectEq(st.changeMaster(NEW_MASTER.toCharArray()), 1, "terlewati")
            expectEq(String(st.read(ok.id)[0]), "x", "yang sehat terotasi")
        }
    }
    test("store: 40 MiB lewat putFile, ekspor, rotasi di -Xmx64m") {
        withStore { st, _ ->
            val total = 40L * 1024 * 1024
            val r = st.putFile(RecordType.FILE, "besar.zip", listOf(ByteArray(0)), 0, gen(total), expectedBytes = total)
            expectEq(st.changeMaster(NEW_MASTER.toCharArray()), 0, "terlewati")
            val s = Sink(); st.exportBlob(r.meta.id, s)
            expectEq(s.count, total, "panjang"); expect(s.md.digest().contentEquals(genDigest(total)), "hash")
        }
    }
}

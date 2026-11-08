// MOLINA-H12-BACKUP
// Uji ekspor/pemulihan cadangan streaming (BackupStore) lintas dua VaultStore, termasuk record v3.
import android.content.Context
import com.molina.vault.crypto.InvalidVaultException
import com.molina.vault.crypto.SessionLock
import com.molina.vault.crypto.WrongPasswordOrTamperedException
import com.molina.vault.store.BackupStore
import com.molina.vault.store.InsufficientSpaceException
import com.molina.vault.store.RecordType
import com.molina.vault.store.VaultStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

private val BK_PASS = "frasa-cadangan-sungguh-beda-55!".toCharArray()
private const val BK_MASTER_B = "master-baru-perangkat-dua-33!"

private fun withTwo(body: (a: VaultStore, b: VaultStore, aDir: File, bDir: File, bak: File) -> Unit) {
    val ta = File.createTempFile("bka", "").also { it.delete(); it.mkdirs() }
    val tb = File.createTempFile("bkb", "").also { it.delete(); it.mkdirs() }
    val bak = File.createTempFile("cad", ".mvault")
    try {
        SessionLock.lock()
        val a = VaultStore(Context(ta))
        a.create(MASTER.toCharArray())
        val b = VaultStore(Context(tb))
        body(a, b, File(ta, "vault"), File(tb, "vault"), bak)
    } finally { SessionLock.lock(); ta.deleteRecursively(); tb.deleteRecursively(); bak.delete() }
}

private fun doExport(a: VaultStore, bak: File, includeLarge: Boolean = true) =
    FileOutputStream(bak).use { BackupStore.export(a, it, BK_PASS, includeLarge) }

private fun doRestore(b: VaultStore, bak: File, pass: CharArray = BK_PASS): Int {
    SessionLock.lock()
    return FileInputStream(bak).use { BackupStore.restore(b, it, pass, BK_MASTER_B.toCharArray()) }
}

private fun flip(f: File, pos: Long) {
    RandomAccessFile(f, "rw").use { it.seek(pos); val v = it.read(); it.seek(pos); it.write(v xor 1) }
}

private fun cleanTarget(b: VaultStore, bDir: File) {
    expect(!b.exists(), "vault tujuan tidak boleh ada")
    expect(!File(bDir, "restore.tmp").exists(), "staging dibuang")
    expect(bDir.listFiles().orEmpty().none { it.name.startsWith("rec_") }, "tanpa record")
}

fun backupStoreTests() {
    test("cadangan: ekspor lalu pulihkan v2 + v3 ke vault lain") {
        withTwo { a, b, _, _, bak ->
            val total = 3L * 1024 * 1024
            val v2 = a.put(RecordType.NOTE, "catatan", listOf("isi".toByteArray()))
            val v3 = a.putFile(RecordType.FILE, "a.zip", listOf(ByteArray(0)), 0, gen(total), expectedBytes = total, folder = "Gudang")
            val r = doExport(a, bak)
            expectEq(r.count, 2, "count"); expectEq(r.skipped, 0, "skipped"); expectEq(r.omitted, 0, "omitted")
            expectEq(doRestore(b, bak), 0, "dilewati")
            b.unlock(BK_MASTER_B.toCharArray())
            expectEq(b.list().items.size, 2, "jumlah pulih")
            expectEq(String(b.read(v2.id)[0]), "isi", "v2")
            val s = Sink(); b.exportBlob(v3.meta.id, s)
            expectEq(s.count, total, "panjang"); expect(s.md.digest().contentEquals(genDigest(total)), "hash v3")
        }
    }
    test("cadangan: estimateBytes sama persis dengan ukuran berkas") {
        withTwo { a, _, _, _, bak ->
            a.put(RecordType.NOTE, "n", listOf("x".toByteArray()))
            a.putFile(RecordType.FILE, "f", listOf(ByteArray(0)), 0, gen(700_000), expectedBytes = 700_000)
            doExport(a, bak)
            expectEq(BackupStore.estimateBytes(a), bak.length(), "ukuran penuh")
            doExport(a, bak, includeLarge = false)
            expectEq(BackupStore.estimateBytes(a, includeLarge = false), bak.length(), "ukuran tanpa gudang")
        }
    }
    test("cadangan: includeLarge=false menghilangkan berkas besar dan dilaporkan") {
        withTwo { a, b, _, _, bak ->
            a.put(RecordType.NOTE, "n", listOf("x".toByteArray()))
            a.putFile(RecordType.FILE, "f", listOf(ByteArray(0)), 0, gen(500_000), expectedBytes = 500_000)
            val r = doExport(a, bak, includeLarge = false)
            expectEq(r.count, 1, "count"); expectEq(r.omitted, 1, "omitted"); expectEq(r.skipped, 0, "skipped")
            doRestore(b, bak); b.unlock(BK_MASTER_B.toCharArray())
            expectEq(b.list().items.size, 1, "hanya database")
        }
    }
    test("cadangan: satu byte diubah ditolak dan vault tujuan tetap kosong") {
        withTwo { a, b, _, bDir, bak ->
            a.putFile(RecordType.FILE, "f", listOf(ByteArray(0)), 0, gen(800_000), expectedBytes = 800_000)
            doExport(a, bak)
            flip(bak, bak.length() / 2)
            expectThrows<InvalidVaultException>("ubah byte") { doRestore(b, bak) }
            cleanTarget(b, bDir)
        }
    }
    test("cadangan: terpotong ditolak; passphrase salah ditolak") {
        withTwo { a, b, _, bDir, bak ->
            a.putFile(RecordType.FILE, "f", listOf(ByteArray(0)), 0, gen(800_000), expectedBytes = 800_000)
            doExport(a, bak)
            expectThrows<WrongPasswordOrTamperedException>("passphrase salah") { doRestore(b, bak, "bukan-frasa-yang-benar-12!".toCharArray()) }
            cleanTarget(b, bDir)
            val len = bak.length()
            RandomAccessFile(bak, "rw").use { it.setLength(len - 1000) }
            expectThrows<InvalidVaultException>("terpotong") { doRestore(b, bak) }
            cleanTarget(b, bDir)
            RandomAccessFile(bak, "rw").use { it.setLength(len + 5) }
            expectThrows<InvalidVaultException>("tambahan/terpotong") { doRestore(b, bak) }
            cleanTarget(b, bDir)
        }
    }
    test("cadangan: ruang kurang saat pemulihan dibatalkan bersih") {
        withTwo { a, b, _, bDir, bak ->
            a.putFile(RecordType.FILE, "f", listOf(ByteArray(0)), 0, gen(600_000), expectedBytes = 600_000)
            doExport(a, bak)
            b.freeSpace = { 1_000L }
            expectThrows<InsufficientSpaceException>("ruang") { doRestore(b, bak) }
            cleanTarget(b, bDir)
        }
    }
    test("cadangan: blob v3 rusak di disk dilewati saat pemulihan, record lain tetap pulih") {
        withTwo { a, b, aDir, _, bak ->
            a.put(RecordType.NOTE, "sehat", listOf("ok".toByteArray()))
            val bad = a.putFile(RecordType.FILE, "rusak", listOf(ByteArray(0)), 0, gen(400_000), expectedBytes = 400_000)
            val f = File(aDir, "rec_${bad.meta.id}.mvr")
            flip(f, f.length() - 50)
            val r = doExport(a, bak)
            expectEq(r.count, 2, "meta masih terbaca saat ekspor")
            expectEq(doRestore(b, bak), 1, "dilewati")
            b.unlock(BK_MASTER_B.toCharArray())
            expectEq(b.list().items.size, 1, "hanya yang sehat")
        }
    }
    test("cadangan: 60 MiB ekspor dan pulihkan lewat berkas di -Xmx64m") {
        withTwo { a, b, _, _, bak ->
            val total = 60L * 1024 * 1024
            val v3 = a.putFile(RecordType.FILE, "besar.zip", listOf(ByteArray(0)), 0, gen(total), expectedBytes = total)
            doExport(a, bak)
            expect(bak.length() > total, "cadangan memuat blob")
            expectEq(doRestore(b, bak), 0, "dilewati")
            b.unlock(BK_MASTER_B.toCharArray())
            val s = Sink(); b.exportBlob(v3.meta.id, s)
            expectEq(s.count, total, "panjang"); expect(s.md.digest().contentEquals(genDigest(total)), "hash")
        }
    }
}

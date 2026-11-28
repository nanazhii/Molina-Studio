// MOLINA-H1
// Uji JVM murni modul Vault: AttemptLogic, SecurityPatch, PassphraseGenerator/BackupPolicy, BackupCrypto.
import com.molina.vault.crypto.AttemptLogic
import com.molina.vault.crypto.AttemptStore
import com.molina.vault.crypto.BackupCrypto
import com.molina.vault.crypto.BackupPolicy
import com.molina.vault.crypto.InvalidVaultException
import com.molina.vault.crypto.PassphraseGenerator
import com.molina.vault.crypto.SecurityPatch
import com.molina.vault.crypto.StreamCrypto
import com.molina.vault.crypto.WrongPasswordOrTamperedException
import java.nio.ByteBuffer
import java.time.LocalDate
import kotlin.system.exitProcess

private var failures = 0
private var total = 0

internal fun test(name: String, body: () -> Unit) {
    total++
    try {
        body()
        println("OK     $name")
    } catch (t: Throwable) {
        failures++
        println("GAGAL  $name: ${t::class.simpleName}: ${t.message}")
    }
}

internal fun expect(cond: Boolean, msg: String) { if (!cond) throw AssertionError(msg) }
internal fun <T> expectEq(a: T, b: T, msg: String) { if (a != b) throw AssertionError("$msg: diharapkan $b, dapat $a") }

internal inline fun <reified E : Throwable> expectThrows(msg: String, body: () -> Unit) {
    try { body() } catch (e: Throwable) {
        if (e is E) return
        throw AssertionError("$msg: diharapkan ${E::class.simpleName}, dapat ${e::class.simpleName}")
    }
    throw AssertionError("$msg: tidak melempar ${E::class.simpleName}")
}

private class FakeStore : AttemptStore {
    val l = HashMap<String, Long>()
    val i = HashMap<String, Int>()
    var writes = 0
    override fun long(key: String, def: Long) = l[key] ?: def
    override fun int(key: String, def: Int) = i[key] ?: def
    override fun commit(longs: Map<String, Long>, ints: Map<String, Int>) { l.putAll(longs); i.putAll(ints); writes++ }
    override fun clear() { l.clear(); i.clear() }
}

private class Env(var now: Long = 1_000_000L, var boot: Long = 7L) {
    val store = FakeStore()
    val lg = AttemptLogic(store, { now }, { boot })
    fun fail(n: Int) { repeat(n) { lg.recordFailure() } }
}

private fun attemptTests() {
    test("limiter: 4 gagal pertama tanpa penguncian") {
        val e = Env(); e.fail(4)
        expectEq(e.lg.remainingLockMs(), 0L, "sisa")
    }
    test("limiter: gagal ke-5 mengunci 30 detik, ke-6 60 detik") {
        val e = Env(); e.fail(5)
        expectEq(e.lg.remainingLockMs(), 30_000L, "sisa ke-5")
        e.now += 30_000; e.fail(1)
        expectEq(e.lg.remainingLockMs(), 60_000L, "sisa ke-6")
    }
    test("limiter: tunda dibatasi 1 jam") {
        expectEq(AttemptLogic.delayFor(12), 3_600_000L, "n=12")
        expectEq(AttemptLogic.delayFor(100), 3_600_000L, "n=100")
        expectEq(AttemptLogic.delayFor(4), 0L, "n=4")
    }
    test("limiter: sisa waktu turun mengikuti jam monoton, tanpa menulis ulang") {
        val e = Env(); e.fail(5)
        val w = e.store.writes
        e.now += 10_000
        expectEq(e.lg.remainingLockMs(), 20_000L, "sisa")
        e.now += 25_000
        expectEq(e.lg.remainingLockMs(), 0L, "habis")
        expectEq(e.store.writes, w, "tidak ada tulis ulang saat polling")
    }
    test("limiter: reboot saat terkunci mengulang tunda penuh") {
        val e = Env(); e.fail(5)
        e.now = 50; e.boot = 8   // jam monoton mulai dari awal setelah reboot
        expectEq(e.lg.remainingLockMs(), 30_000L, "sisa setelah reboot")
        e.now += 5_000
        expectEq(e.lg.remainingLockMs(), 25_000L, "turun lagi setelah rebase")
    }
    test("limiter: reboot setelah tunda habis tetap mengulang tunda (konservatif)") {
        val e = Env(); e.fail(5)
        e.now += 60_000
        expectEq(e.lg.remainingLockMs(), 0L, "habis")
        e.boot = 8; e.now = 100
        expectEq(e.lg.remainingLockMs(), 30_000L, "diulang")
    }
    test("limiter: reboot terdeteksi lewat jam mundur saat BOOT_COUNT tidak tersedia") {
        val e = Env(boot = -1L); e.fail(5)
        e.now = 10
        expectEq(e.lg.remainingLockMs(), 30_000L, "sisa")
    }
    test("limiter: tanpa gagal, reboot tidak menambah kunci") {
        val e = Env(); e.fail(2)
        e.boot = 9; e.now = 5
        expectEq(e.lg.remainingLockMs(), 0L, "sisa")
    }
    test("limiter: migrasi, boot belum tersimpan tidak dianggap reboot") {
        val e = Env()
        e.store.i["fails"] = 6   // data format lama: hanya fails
        expectEq(e.lg.remainingLockMs(), 0L, "sisa")
        expectEq(e.store.long("boot", -9L), 7L, "boot tersimpan")
    }
    test("limiter: sukses menghapus semuanya") {
        val e = Env(); e.fail(6)
        e.lg.recordSuccess()
        expectEq(e.lg.remainingLockMs(), 0L, "sisa")
        e.fail(1)
        expectEq(e.store.int("fails", 0), 1, "hitungan mulai ulang")
    }
}

private fun patchTests() {
    val today = LocalDate.of(2026, 11, 3)
    test("patch: tanggal lama dinilai usang") {
        val a = SecurityPatch.ageDays("2018-02-05", today)
        expect(a != null && a > 365, "umur $a")
        expect(SecurityPatch.isStale(a), "harus usang")
    }
    test("patch: tanggal baru tidak usang") {
        expectEq(SecurityPatch.ageDays("2026-10-05", today), 29L, "umur")
        expect(!SecurityPatch.isStale(29L), "tidak usang")
        expect(!SecurityPatch.isStale(365L), "batas 365 belum usang")
        expect(SecurityPatch.isStale(366L), "366 usang")
    }
    test("patch: format rusak, kosong, dan masa depan jadi null") {
        expectEq(SecurityPatch.ageDays("abc", today), null, "abc")
        expectEq(SecurityPatch.ageDays("", today), null, "kosong")
        expectEq(SecurityPatch.ageDays(null, today), null, "null")
        expectEq(SecurityPatch.ageDays("2027-01-01", today), null, "masa depan")
        expect(!SecurityPatch.isStale(null), "null tidak usang")
    }
    test("patch: hari yang sama berumur 0") {
        expectEq(SecurityPatch.ageDays("2026-11-03", today), 0L, "umur")
    }
}

private fun passphraseTests() {
    test("passphrase: format 6 kelompok x 4 simbol, alfabet tanpa i l o u") {
        val re = Regex("^[0-9a-hjkmnp-tv-z]{4}(-[0-9a-hjkmnp-tv-z]{4}){5}$")
        repeat(300) { expect(re.matches(String(PassphraseGenerator.generate())), "format salah") }
    }
    test("passphrase: 300 hasil unik dan lolos BackupPolicy") {
        val seen = HashSet<String>()
        repeat(300) {
            val p = PassphraseGenerator.generate()
            expect(BackupPolicy.isAcceptable(p), "ditolak policy: ${String(p)}")
            seen.add(String(p))
        }
        expectEq(seen.size, 300, "keunikan")
    }
    test("policy: pendek, non-ASCII, dan keragaman rendah ditolak") {
        expect(!BackupPolicy.isAcceptable("abcdefghijklmnopqrstuvw".toCharArray()), "23 karakter")
        expect(!BackupPolicy.isAcceptable("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".toCharArray()), "1 unik")
        expect(!BackupPolicy.isAcceptable("abcdefghijklmnopqrstuvwxé".toCharArray()), "non-ASCII")
        expect(!BackupPolicy.isAcceptable("abcdefghijklmnopqrstuvw\u0000x".toCharArray()), "karakter kontrol")
        expect(BackupPolicy.isAcceptable("abcdefghijklmnopqrstuvwx".toCharArray()), "24 karakter valid")
    }
}

private fun backupTests() {
    val pass = "correct horse battery staple 1!".toCharArray()
    val vaultKey = ByteArray(32) { (it * 7 + 3).toByte() }
    val header = BackupCrypto.newHeader(3)
    lateinit var wrapped: ByteArray

    test("backup: header valid dan parse ulang identik") {
        val p = BackupCrypto.parseHeader(header.bytes)
        expectEq(p.iterations, BackupCrypto.TARGET_ITERATIONS, "iterasi")
        expectEq(p.count, 3, "jumlah")
        expect(p.salt.contentEquals(header.salt), "salt")
    }
    test("backup: wrap lalu unwrap mengembalikan vaultKey") {
        wrapped = BackupCrypto.wrapKey(pass.copyOf(), header, vaultKey)
        expectEq(wrapped.size, BackupCrypto.WRAPPED_SIZE, "ukuran")
        val out = BackupCrypto.unwrapKey(pass.copyOf(), header, wrapped)
        expect(out.contentEquals(vaultKey), "vaultKey berbeda")
    }
    test("backup: passphrase salah ditolak") {
        expectThrows<WrongPasswordOrTamperedException>("passphrase salah") {
            BackupCrypto.unwrapKey("passphrase yang salah sekali 99".toCharArray(), header, wrapped)
        }
    }
    test("backup: wrapped dimanipulasi ditolak") {
        val bad = wrapped.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        expectThrows<WrongPasswordOrTamperedException>("wrapped diubah") {
            BackupCrypto.unwrapKey(pass.copyOf(), header, bad)
        }
    }
    test("backup: header dimanipulasi ditolak (AAD)") {
        val bytes = header.bytes.copyOf()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()   // ubah jumlah record
        val forged = BackupCrypto.Header(bytes, header.iterations, header.salt, header.count xor 1)
        expectThrows<WrongPasswordOrTamperedException>("header diubah") {
            BackupCrypto.unwrapKey(pass.copyOf(), forged, wrapped)
        }
    }
    test("backup: ukuran wrapped salah ditolak") {
        expectThrows<InvalidVaultException>("wrapped pendek") {
            BackupCrypto.unwrapKey(pass.copyOf(), header, ByteArray(10))
        }
    }
    test("backup: downgrade dan DoS iterasi ditolak parser") {
        fun withIter(it: Int): ByteArray {
            val b = header.bytes.copyOf()
            ByteBuffer.wrap(b).position(5).putInt(it)
            return b
        }
        expectThrows<InvalidVaultException>("iterasi rendah") { BackupCrypto.parseHeader(withIter(1_000)) }
        expectThrows<InvalidVaultException>("iterasi 599.999") { BackupCrypto.parseHeader(withIter(599_999)) }
        expectThrows<InvalidVaultException>("iterasi absurd") { BackupCrypto.parseHeader(withIter(Int.MAX_VALUE)) }
        BackupCrypto.parseHeader(withIter(600_000))   // batas bawah masih diterima
    }
    test("backup: magic, versi, ukuran, dan jumlah record tidak wajar ditolak") {
        val badMagic = header.bytes.copyOf().also { it[0] = 0 }
        val badVer = header.bytes.copyOf().also { it[4] = 9 }
        val badCount = header.bytes.copyOf().also { ByteBuffer.wrap(it).position(25).putInt(-1) }
        val hugeCount = header.bytes.copyOf().also { ByteBuffer.wrap(it).position(25).putInt(BackupCrypto.MAX_RECORDS + 1) }
        expectThrows<InvalidVaultException>("magic") { BackupCrypto.parseHeader(badMagic) }
        expectThrows<InvalidVaultException>("versi") { BackupCrypto.parseHeader(badVer) }
        expectThrows<InvalidVaultException>("count negatif") { BackupCrypto.parseHeader(badCount) }
        expectThrows<InvalidVaultException>("count besar") { BackupCrypto.parseHeader(hugeCount) }
        expectThrows<InvalidVaultException>("ukuran") { BackupCrypto.parseHeader(ByteArray(10)) }
    }
    test("backup: MAC deterministik, berbeda untuk kunci dan data berbeda") {
        val data = "isi cadangan".toByteArray()
        val m1 = BackupCrypto.newMac(vaultKey).doFinal(data)
        val m2 = BackupCrypto.newMac(vaultKey).doFinal(data)
        val k2 = vaultKey.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val m3 = BackupCrypto.newMac(k2).doFinal(data)
        val m4 = BackupCrypto.newMac(vaultKey).doFinal("isi cadangan!".toByteArray())
        expectEq(m1.size, BackupCrypto.MAC_SIZE, "ukuran MAC")
        expect(m1.contentEquals(m2), "tidak deterministik")
        expect(!m1.contentEquals(m3), "kunci berbeda harus beda")
        expect(!m1.contentEquals(m4), "data berbeda harus beda")
    }
}

// MOLINA-H10-STREAM
private fun streamTests() {
    val key = ByteArray(32) { (it * 7 + 3).toByte() }
    val aad = "data|11111111-1111-1111-1111-111111111111".toByteArray()
    val chunk = 1024

    fun enc(data: ByteArray, k: ByteArray = key, a: ByteArray = aad, cs: Int = chunk): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        StreamCrypto.encrypt(k, a, java.io.ByteArrayInputStream(data), o, cs)
        return o.toByteArray()
    }
    fun dec(ct: ByteArray, k: ByteArray = key, a: ByteArray = aad): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        StreamCrypto.decrypt(k, a, java.io.ByteArrayInputStream(ct), o)
        return o.toByteArray()
    }
    fun expectRejected(msg: String, body: () -> Unit) {
        try { body() } catch (e: Throwable) {
            if (e is InvalidVaultException || e is WrongPasswordOrTamperedException) return
            throw AssertionError("$msg: dapat ${e::class.simpleName}: ${e.message}")
        }
        throw AssertionError("$msg: tidak ditolak")
    }
    fun sample(n: Int) = ByteArray(n) { ((it * 31) xor (it ushr 8)).toByte() }

    test("stream: bolak-balik untuk ukuran batas chunk") {
        for (n in listOf(0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, 3 * chunk + 17, 10 * chunk)) {
            val d = sample(n)
            expect(dec(enc(d)).contentEquals(d), "isi berbeda untuk n=$n")
        }
    }
    test("stream: encryptedSize cocok dengan ukuran nyata") {
        for (n in listOf(0, 1, chunk, chunk + 1, 5 * chunk, 5 * chunk + 3)) {
            expectEq(enc(sample(n)).size.toLong(), StreamCrypto.encryptedSize(n.toLong(), chunk), "ukuran n=$n")
        }
    }
    test("stream: aliran kosong tetap punya satu chunk terakhir") {
        expectEq(enc(ByteArray(0)).size, StreamCrypto.HEADER_SIZE + StreamCrypto.TAG_SIZE, "ukuran kosong")
    }
    test("stream: isi sama menghasilkan ciphertext berbeda") {
        val d = sample(3000)
        expect(!enc(d).contentEquals(enc(d)), "salt/prefiks harus acak")
    }
    test("stream: SHA-256 dan panjang dilaporkan benar di enkripsi dan dekripsi") {
        val d = sample(5000)
        val want = java.security.MessageDigest.getInstance("SHA-256").digest(d)
        val o = java.io.ByteArrayOutputStream()
        val r1 = StreamCrypto.encrypt(key, aad, java.io.ByteArrayInputStream(d), o, chunk)
        val r2 = StreamCrypto.decrypt(key, aad, java.io.ByteArrayInputStream(o.toByteArray()), java.io.ByteArrayOutputStream())
        expectEq(r1.plainBytes, 5000L, "panjang enkripsi")
        expectEq(r2.plainBytes, 5000L, "panjang dekripsi")
        expect(r1.sha256.contentEquals(want) && r2.sha256.contentEquals(want), "hash salah")
    }
    test("stream: mengubah SATU byte di posisi mana pun ditolak (uji menyeluruh)") {
        val ct = enc(sample(2500))
        for (i in ct.indices) {
            val bad = ct.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            expectRejected("flip byte $i") { dec(bad) }
        }
    }
    test("stream: pemotongan di panjang mana pun ditolak (uji menyeluruh)") {
        val ct = enc(sample(2500))
        for (n in 0 until ct.size) expectRejected("potong $n") { dec(ct.copyOf(n)) }
    }
    test("stream: potong tepat di batas chunk ditolak") {
        val ct = enc(sample(4 * chunk))
        val frame = chunk + StreamCrypto.TAG_SIZE
        expectRejected("batas chunk") { dec(ct.copyOf(StreamCrypto.HEADER_SIZE + 2 * frame)) }
    }
    test("stream: tambahan byte di ujung ditolak") {
        val ct = enc(sample(2500))
        expectRejected("tambahan") { dec(ct + byteArrayOf(0)) }
        expectRejected("tambahan chunk penuh") { dec(ct + ByteArray(chunk + StreamCrypto.TAG_SIZE)) }
    }
    test("stream: chunk ditukar atau digandakan ditolak") {
        val ct = enc(sample(4 * chunk))
        val frame = chunk + StreamCrypto.TAG_SIZE
        val h = StreamCrypto.HEADER_SIZE
        val swapped = ct.copyOf()
        System.arraycopy(ct, h, swapped, h + frame, frame)
        System.arraycopy(ct, h + frame, swapped, h, frame)
        expectRejected("tukar") { dec(swapped) }
        val dup = ct.copyOf()
        System.arraycopy(ct, h, dup, h + frame, frame)
        expectRejected("gandakan") { dec(dup) }
    }
    test("stream: kunci salah dan aad (id record) salah ditolak") {
        val ct = enc(sample(3000))
        expectRejected("kunci") { dec(ct, k = ByteArray(32)) }
        expectRejected("aad") { dec(ct, a = "data|22222222-2222-2222-2222-222222222222".toByteArray()) }
    }
    test("stream: header tidak valid ditolak") {
        val ct = enc(sample(3000))
        expectRejected("magic") { dec(ct.copyOf().also { it[0] = 0 }) }
        expectRejected("versi") { dec(ct.copyOf().also { it[4] = 9 }) }
        expectRejected("ukuran chunk") { dec(ct.copyOf().also { it[5] = 0x7F }) }
    }
    test("stream: batas ukuran ditegakkan saat enkripsi dan dekripsi") {
        expectRejected("enkripsi") {
            StreamCrypto.encrypt(key, aad, java.io.ByteArrayInputStream(sample(3000)), java.io.ByteArrayOutputStream(), chunk, 2000L)
        }
        val ct = enc(sample(3000))
        expectRejected("dekripsi") {
            StreamCrypto.decrypt(key, aad, java.io.ByteArrayInputStream(ct), java.io.ByteArrayOutputStream(), 2000L)
        }
    }
    test("stream: parameter tidak valid") {
        expectThrows<IllegalArgumentException>("kunci 16 byte") { enc(sample(10), k = ByteArray(16)) }
        expectThrows<IllegalArgumentException>("chunk terlalu kecil") { enc(sample(10), cs = 16) }
    }
    test("stream: 64 MiB mengalir dengan memori tetap (java -Xmx64m)") {
        val total = 64L * 1024 * 1024
        fun gen() = object : java.io.InputStream() {
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
        val tmp = java.io.File.createTempFile("mvst", ".bin")
        try {
            val r1 = java.io.FileOutputStream(tmp).use { StreamCrypto.encrypt(key, aad, gen(), it) }
            expectEq(tmp.length(), StreamCrypto.encryptedSize(total), "ukuran berkas")
            val sink = object : java.io.OutputStream() {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                override fun write(b: Int) { md.update(b.toByte()) }
                override fun write(b: ByteArray, off: Int, len: Int) { md.update(b, off, len) }
            }
            val r2 = java.io.FileInputStream(tmp).buffered().use { StreamCrypto.decrypt(key, aad, it, sink) }
            expectEq(r2.plainBytes, total, "panjang")
            expect(r1.sha256.contentEquals(r2.sha256) && sink.md.digest().contentEquals(r1.sha256), "hash berbeda")
        } finally { tmp.delete() }
    }
}

fun main() {
    attemptTests()
    patchTests()
    passphraseTests()
    backupTests()
    streamTests()
    stage2Tests()
    storeTests()   // MOLINA-H11-GLUE
    backupStoreTests()   // MOLINA-H12-BACKUP
    gudangTests()   // MOLINA-H14-GUDANG
    manifestTests()   // MOLINA-H15-MANIFEST
    println("Ringkasan: ${total - failures} lulus, $failures gagal dari $total uji")
    if (failures > 0) exitProcess(1)
}

// MOLINA-H14-GUDANG
// Uji sunting berkas besar (editLarge), pemeriksaan integritas (audit), dan laporan pemakaian (usage).
import com.molina.vault.crypto.SessionLock
import com.molina.vault.store.RecordType
import java.io.File
import java.io.RandomAccessFile

private fun flipAt(f: File, pos: Long) {
    RandomAccessFile(f, "rw").use { r ->
        r.seek(pos)
        val b = r.read()
        r.seek(pos)
        r.write(b xor 0x01)
    }
}

private fun recOf(d: File, id: String) = File(d, "rec_$id.mvr")

fun gudangTests() {
    test("gudang: editLarge mengubah judul, nama, catatan; blob, folder, tag utuh") {
        withStore { st, _ ->
            val total = 3L * 1024 * 1024
            val r = st.putFile(RecordType.FILE, "a.zip", listOf("a.zip".toByteArray(), ByteArray(0), "lama".toByteArray()), 1, gen(total),
                expectedBytes = total, folder = "Arsip", tags = listOf("kerja"))
            val m = st.editLarge(r.meta.id, "judul baru", listOf("b.zip".toByteArray(), ByteArray(0), "baru".toByteArray()))
            expectEq(m.title, "judul baru", "judul"); expectEq(m.folder, "Arsip", "folder"); expectEq(m.tags, listOf("kerja"), "tag")
            expectEq(st.list().items.single().title, "judul baru", "daftar")
            val o = st.openLarge(r.meta.id)
            expectEq(String(o.fields[0]), "b.zip", "nama"); expectEq(String(o.fields[2]), "baru", "catatan")
            expectEq(o.info.size, total, "ukuran"); expect(o.info.sha256.contentEquals(genDigest(total)), "sha")
            val s = Sink(); st.exportBlob(r.meta.id, s)
            expectEq(s.count, total, "panjang"); expect(s.md.digest().contentEquals(genDigest(total)), "isi blob utuh")
        }
    }
    test("gudang: editLarge menolak field salah, judul kosong, record v2; record tidak berubah") {
        withStore { st, dir ->
            val total = 2L * 1024 * 1024
            val r = st.putFile(RecordType.FILE, "a.zip", listOf("a.zip".toByteArray(), ByteArray(0), ByteArray(0)), 1, gen(total), expectedBytes = total)
            val before = recOf(dir, r.meta.id).readBytes()
            expectThrows<IllegalArgumentException>("jumlah field") { st.editLarge(r.meta.id, "x", listOf(ByteArray(0))) }
            expectThrows<IllegalArgumentException>("judul kosong") { st.editLarge(r.meta.id, " ", listOf(ByteArray(0), ByteArray(0), ByteArray(0))) }
            val v2 = st.put(RecordType.NOTE, "n", listOf("isi".toByteArray()))
            expectThrows<IllegalArgumentException>("v2") { st.editLarge(v2.id, "x", listOf("isi".toByteArray())) }
            expect(before.contentEquals(recOf(dir, r.meta.id).readBytes()), "record v3 tidak berubah")
            expect(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") }, "tanpa sisa .tmp")
        }
    }
    test("gudang: editLarge lalu ganti master; field dan blob tetap terbuka") {
        withStore { st, _ ->
            val total = 2L * 1024 * 1024
            val r = st.putFile(RecordType.FILE, "a.zip", listOf("a.zip".toByteArray(), ByteArray(0), ByteArray(0)), 1, gen(total), expectedBytes = total)
            st.editLarge(r.meta.id, "baru", listOf("c.zip".toByteArray(), ByteArray(0), "cat".toByteArray()))
            expectEq(st.changeMaster(NEW_MASTER.toCharArray()), 0, "terlewati")
            val o = st.openLarge(r.meta.id)
            expectEq(String(o.fields[0]), "c.zip", "nama"); expectEq(String(o.fields[2]), "cat", "catatan")
            val s = Sink(); st.exportBlob(r.meta.id, s)
            expect(s.md.digest().contentEquals(genDigest(total)), "blob utuh")
        }
    }
    test("gudang: byte blob diubah setelah editLarge tetap ketahuan") {
        withStore { st, dir ->
            val total = 2L * 1024 * 1024
            val r = st.putFile(RecordType.FILE, "a.zip", listOf("a.zip".toByteArray(), ByteArray(0), ByteArray(0)), 1, gen(total), expectedBytes = total)
            st.editLarge(r.meta.id, "baru", listOf("a.zip".toByteArray(), ByteArray(0), "c".toByteArray()))
            val f = recOf(dir, r.meta.id)
            flipAt(f, f.length() / 2)
            expectThrows<Exception>("ekspor setelah diubah") { st.exportBlob(r.meta.id, Sink()) }
        }
    }
    test("gudang: audit semua record utuh (v2 dan v3)") {
        withStore { st, _ ->
            st.put(RecordType.PASSWORD, "bank", listOf("u".toByteArray(), "p".toByteArray()))
            st.put(RecordType.NOTE, "catatan", listOf("isi".toByteArray()))
            val total = 2L * 1024 * 1024
            st.putFile(RecordType.FILE, "a.zip", listOf("a.zip".toByteArray(), ByteArray(0), ByteArray(0)), 1, gen(total), expectedBytes = total)
            val steps = ArrayList<Int>()
            val a = st.audit(onProgress = { d, _ -> steps.add(d) })
            expectEq(a.checked, 3, "diperiksa"); expectEq(a.ok, 3, "utuh"); expectEq(a.bad.size, 0, "bermasalah"); expect(!a.cancelled, "tidak dibatalkan")
            expect(a.bytes > total, "ukuran terhitung"); expectEq(steps.last(), 3, "progres akhir")
        }
    }
    test("gudang: audit menangkap blob v3, data v2, dan meta yang diubah satu byte") {
        withStore { st, dir ->
            val total = 2L * 1024 * 1024
            val v3 = st.putFile(RecordType.FILE, "besar.zip", listOf("besar.zip".toByteArray(), ByteArray(0), ByteArray(0)), 1, gen(total), expectedBytes = total)
            val v2 = st.put(RecordType.NOTE, "catatan", listOf("isi".toByteArray()))
            val v2b = st.put(RecordType.NOTE, "utuh", listOf("aman".toByteArray()))
            val v2m = st.put(RecordType.NOTE, "metarusak", listOf("x".toByteArray()))
            flipAt(recOf(dir, v3.meta.id), recOf(dir, v3.meta.id).length() - 40)
            flipAt(recOf(dir, v2.id), recOf(dir, v2.id).length() - 3)
            flipAt(recOf(dir, v2m.id), 9L + 20)
            val a = st.audit()
            expectEq(a.checked, 4, "diperiksa"); expectEq(a.ok, 1, "utuh"); expectEq(a.bad.size, 3, "bermasalah")
            val byId = a.bad.associateBy { it.id }
            expectEq(byId.getValue(v3.meta.id).title, "besar.zip", "judul v3 terbaca")
            expectEq(byId.getValue(v2.id).title, "catatan", "judul v2 terbaca")
            expectEq(byId.getValue(v2m.id).title, null, "meta rusak: judul tidak terbaca")
            expect(!byId.containsKey(v2b.id), "record utuh tidak ikut")
        }
    }
    test("gudang: audit bisa dibatalkan dan berhenti bila vault terkunci") {
        withStore { st, _ ->
            repeat(3) { st.put(RecordType.NOTE, "n$it", listOf("x".toByteArray())) }
            val c = st.audit(isCancelled = { true })
            expect(c.cancelled, "dibatalkan"); expectEq(c.checked, 0, "belum ada yang diperiksa")
            SessionLock.lock()
            expectThrows<IllegalStateException>("terkunci") { st.audit() }
        }
    }
    test("gudang: usage menghitung jenis, berkas besar, record rusak, dan yang terbesar") {
        withStore { st, dir ->
            st.put(RecordType.NOTE, "n1", listOf("a".toByteArray()))
            st.put(RecordType.NOTE, "n2", listOf("b".toByteArray()))
            st.put(RecordType.PASSWORD, "p", listOf("u".toByteArray(), "x".toByteArray()))
            val big = st.putFile(RecordType.FILE, "g.zip", listOf("g.zip".toByteArray(), ByteArray(0), ByteArray(0)), 1, gen(2L * 1024 * 1024), expectedBytes = 2L * 1024 * 1024)
            val bad = st.put(RecordType.NOTE, "rusak", listOf("z".toByteArray()))
            flipAt(recOf(dir, bad.id), 9L + 20)
            val u = st.usage()
            expectEq(u.records, 4, "terbaca"); expectEq(u.corrupt, 1, "rusak")
            expectEq(u.perType[RecordType.NOTE], 2, "catatan"); expectEq(u.perType[RecordType.PASSWORD], 1, "sandi"); expectEq(u.perType[RecordType.FILE], 1, "file")
            expectEq(u.largeCount, 1, "berkas besar"); expect(u.largeDiskBytes > 2L * 1024 * 1024, "ukuran besar")
            expectEq(u.largest.first().first.id, big.meta.id, "terbesar di urutan pertama")
            expect(u.diskBytes >= u.largeDiskBytes, "total di disk")
        }
    }
}

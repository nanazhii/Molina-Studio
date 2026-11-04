// MOLINA-H15-MANIFEST
// Uji manifest tersegel: penghapusan, rollback satu record, sambungan meta/data, rollback folder, manifest dirusak, rotasi, jurnal crash.
import android.content.Context
import com.molina.vault.crypto.SessionLock
import com.molina.vault.store.BackupStore
import com.molina.vault.store.IssueKind
import com.molina.vault.store.ManifestReport
import com.molina.vault.store.ManifestState
import com.molina.vault.store.RecordType
import com.molina.vault.store.VaultStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

private fun mrec(d: File, id: String) = File(d, "rec_$id.mvr")
private fun mflip(f: File, pos: Long) {
    RandomAccessFile(f, "rw").use { r -> r.seek(pos); val b = r.read(); r.seek(pos); r.write(b xor 1) }
}
private fun kinds(r: ManifestReport, k: IssueKind) = r.issues.filter { it.kind == k }.map { it.id }.toSet()
private fun big(st: VaultStore, name: String, total: Long = 1_200_000L) =
    st.putFile(RecordType.FILE, name, listOf(name.toByteArray(), ByteArray(0), ByteArray(0)), 1, gen(total), expectedBytes = total)
private fun ok(st: VaultStore, msg: String) {
    val r = st.checkManifest()
    expectEq(r.state, ManifestState.OK, "$msg: ${r.problem} ${r.issues.map { it.kind.name + ":" + it.title }}")
}

fun manifestTests() {
    test("manifest: vault baru punya manifest kosong dan status OK") {
        withStore { st, dir ->
            expect(File(dir, "manifest.mvm").exists(), "berkas manifest")
            val r = st.checkManifest()
            expectEq(r.state, ManifestState.OK, "status"); expectEq(r.sealed, 0, "entri"); expect(r.counter >= 1, "penghitung")
        }
    }
    test("manifest: put, ubah, relabel v2 dan v3, folder, putFile, editLarge, hapus tetap OK") {
        withStore { st, _ ->
            val a = st.put(RecordType.NOTE, "a", listOf("satu".toByteArray())); ok(st, "put")
            st.put(RecordType.NOTE, "a", listOf("dua".toByteArray()), id = a.id); ok(st, "ubah")
            st.relabel(a.id, "Folder", listOf("t")); ok(st, "relabel v2")
            val b = big(st, "b.zip"); ok(st, "putFile")
            st.relabel(b.meta.id, "Arsip", listOf("x")); ok(st, "relabel v3")
            st.renameFolder("Arsip", "Arsip2"); ok(st, "renameFolder")
            st.editLarge(b.meta.id, "baru", listOf("b2.zip".toByteArray(), ByteArray(0), "cat".toByteArray())); ok(st, "editLarge")
            val c1 = st.checkManifest().counter
            st.delete(a.id); ok(st, "hapus")
            expect(st.checkManifest().counter > c1, "penghitung naik")
            expectEq(st.checkManifest().sealed, 1, "entri tersisa")
        }
    }
    test("manifest: record dihapus diam-diam ketahuan dan judulnya dilaporkan") {
        withStore { st, dir ->
            val a = st.put(RecordType.NOTE, "rahasia penting", listOf("x".toByteArray()))
            st.put(RecordType.NOTE, "lain", listOf("y".toByteArray()))
            mrec(dir, a.id).delete()
            val r = st.checkManifest()
            expectEq(r.state, ManifestState.ALERT, "status")
            expectEq(kinds(r, IssueKind.MISSING), setOf(a.id), "hilang")
            expectEq(r.issues.single().title, "rahasia penting", "judul")
        }
    }
    test("manifest: record diganti versi lama (rollback satu record) ketahuan") {
        withStore { st, dir ->
            val a = st.put(RecordType.NOTE, "n", listOf("satu".toByteArray()))
            val old = mrec(dir, a.id).readBytes()
            st.put(RecordType.NOTE, "n", listOf("dua".toByteArray()), id = a.id)
            mrec(dir, a.id).writeBytes(old)
            expectEq(String(st.read(a.id)[0]), "satu", "versi lama lolos GCM")
            expectEq(kinds(st.checkManifest(), IssueKind.CHANGED), setOf(a.id), "berubah")
        }
    }
    test("manifest: record yang sudah dihapus dihidupkan kembali ketahuan (tak dikenal)") {
        withStore { st, dir ->
            val a = st.put(RecordType.NOTE, "n", listOf("x".toByteArray()))
            val copy = mrec(dir, a.id).readBytes()
            st.delete(a.id); ok(st, "setelah hapus sah")
            mrec(dir, a.id).writeBytes(copy)
            expectEq(kinds(st.checkManifest(), IssueKind.UNKNOWN), setOf(a.id), "tak dikenal")
        }
    }
    test("manifest: meta lama + data baru lolos GCM dan audit, tetapi ketahuan manifest") {
        withStore { st, dir ->
            val a = st.put(RecordType.NOTE, "n", listOf("satu".toByteArray()))
            val old = mrec(dir, a.id).readBytes()
            st.put(RecordType.NOTE, "n", listOf("dua".toByteArray()), id = a.id)
            val cur = mrec(dir, a.id).readBytes()
            fun metaEnd(b: ByteArray) = 9 + ByteBuffer.wrap(b, 5, 4).int
            mrec(dir, a.id).writeBytes(old.copyOfRange(0, metaEnd(old)) + cur.copyOfRange(metaEnd(cur), cur.size))
            expectEq(String(st.read(a.id)[0]), "dua", "sambungan lolos GCM")
            val au = st.audit()
            expectEq(au.bad.size, 0, "audit per record tidak melihat masalah")
            expectEq(au.manifest?.state, ManifestState.ALERT, "manifest melihatnya")
            expectEq(kinds(st.checkManifest(), IssueKind.CHANGED), setOf(a.id), "berubah")
        }
    }
    test("manifest: manifest dirusak satu byte atau dihapus ketahuan") {
        withStore { st, dir ->
            st.put(RecordType.NOTE, "n", listOf("x".toByteArray()))
            val mf = File(dir, "manifest.mvm")
            val saved = mf.readBytes()
            mflip(mf, mf.length() / 2)
            val r1 = st.checkManifest()
            expectEq(r1.state, ManifestState.ALERT, "rusak"); expect(r1.problem?.contains("rusak") == true, "pesan rusak")
            mf.writeBytes(saved); ok(st, "dipulihkan")
            mf.delete()
            val r2 = st.checkManifest()
            expectEq(r2.state, ManifestState.ALERT, "hilang"); expect(r2.problem?.contains("hilang") == true, "pesan hilang")
        }
    }
    test("manifest: seluruh folder dikembalikan ke versi lama ketahuan lewat jangkar penghitung") {
        withStore { st, dir ->
            st.put(RecordType.NOTE, "satu", listOf("x".toByteArray()))
            val snap = File.createTempFile("snap", "").also { it.delete(); it.mkdirs() }
            try {
                dir.copyRecursively(snap, true)
                st.put(RecordType.NOTE, "dua", listOf("y".toByteArray()))
                st.put(RecordType.NOTE, "tiga", listOf("z".toByteArray()))
                dir.deleteRecursively(); snap.copyRecursively(dir, true)
                val r = st.checkManifest()
                expectEq(r.state, ManifestState.ALERT, "status")
                expect(r.problem?.contains("lama") == true, "pesan rollback: ${r.problem}")
            } finally { snap.deleteRecursively() }
        }
    }
    test("manifest: terima keadaan sekarang membuat status OK lagi dan menaikkan penghitung") {
        withStore { st, dir ->
            val a = st.put(RecordType.NOTE, "n", listOf("x".toByteArray()))
            mrec(dir, a.id).delete()
            val before = st.checkManifest()
            expectEq(before.state, ManifestState.ALERT, "alarm")
            st.acceptManifest()
            val after = st.checkManifest()
            expectEq(after.state, ManifestState.OK, "setelah diterima"); expect(after.counter > before.counter, "penghitung naik")
        }
    }
    test("manifest: ganti master menjaga status OK; record rusak yang dilewati ikut tercatat; hapus sesudahnya tetap ketahuan") {
        withStore { st, dir ->
            val v2 = st.put(RecordType.NOTE, "catatan", listOf("isi".toByteArray()))
            val v3 = big(st, "b.zip")
            val rusak = st.put(RecordType.NOTE, "rusak", listOf("z".toByteArray()))
            mflip(mrec(dir, rusak.id), 9L + 20)
            st.acceptManifest()
            val c0 = st.checkManifest().counter
            expectEq(st.changeMaster(NEW_MASTER.toCharArray()), 1, "dilewati")
            val r = st.checkManifest()
            expectEq(r.state, ManifestState.OK, "setelah rotasi: ${r.problem} ${r.issues.map { it.kind.name + it.title }}")
            expect(r.counter > c0, "penghitung naik"); expectEq(r.sealed, 3, "tiga entri tercatat")
            SessionLock.lock(); st.unlock(NEW_MASTER.toCharArray())
            ok(st, "setelah buka ulang dengan master baru")
            mrec(dir, v2.id).delete()
            expectEq(kinds(st.checkManifest(), IssueKind.MISSING), setOf(v2.id), "hapus pasca-rotasi")
            expect(mrec(dir, v3.meta.id).exists(), "v3 utuh")
        }
    }
    test("manifest: jurnal pending memulihkan satu id setelah crash; tanpa jurnal tetap alarm") {
        withStore { st, dir ->
            val a = st.put(RecordType.NOTE, "n", listOf("satu".toByteArray()))
            val old = mrec(dir, a.id).readBytes()
            st.put(RecordType.NOTE, "n", listOf("dua".toByteArray()), id = a.id)
            val b = st.put(RecordType.NOTE, "lain", listOf("y".toByteArray()))
            mrec(dir, a.id).writeBytes(old)
            expectEq(st.checkManifest().state, ManifestState.ALERT, "tanpa jurnal")
            // keadaan tadi tidak dibenarkan: ulangi dengan jurnal untuk id yang sama saja
            st.prefs.setPending(a.id)
            val r = st.checkManifest()
            expectEq(r.state, ManifestState.OK, "dengan jurnal: ${r.issues.map { it.kind.name + it.title }}")
            expectEq(st.prefs.pendingId, null, "jurnal dibersihkan")
            mrec(dir, b.id).delete()
            st.prefs.setPending(a.id)
            expectEq(kinds(st.checkManifest(), IssueKind.MISSING), setOf(b.id), "jurnal tidak menutupi id lain")
        }
    }
    test("manifest: vault lama tanpa manifest diadopsi sekali, lalu dijaga") {
        withStore { st, dir ->
            val a = st.put(RecordType.NOTE, "n", listOf("x".toByteArray()))
            File(dir, "manifest.mvm").delete(); st.prefs.resetManifest()
            val r1 = st.checkManifest()
            expectEq(r1.state, ManifestState.ADOPTED, "adopsi"); expectEq(r1.sealed, 1, "entri")
            ok(st, "setelah adopsi")
            mrec(dir, a.id).delete()
            expectEq(st.checkManifest().state, ManifestState.ALERT, "dijaga")
        }
    }
    test("manifest: pemulihan cadangan ke vault baru diadopsi saat dibuka pertama kali") {
        val ta = File.createTempFile("mfa", "").also { it.delete(); it.mkdirs() }
        val tb = File.createTempFile("mfb", "").also { it.delete(); it.mkdirs() }
        try {
            SessionLock.lock()
            val a = VaultStore(Context(ta)); a.create(MASTER.toCharArray())
            a.put(RecordType.NOTE, "n1", listOf("x".toByteArray())); big(a, "g.zip")
            val pass = "frasa-cadangan-untuk-manifest-77!".toCharArray()
            val bos = ByteArrayOutputStream()
            BackupStore.export(a, bos, pass)
            SessionLock.lock()
            val b = VaultStore(Context(tb))
            BackupStore.restore(b, bos.toByteArray().inputStream(), pass, NEW_MASTER.toCharArray())
            b.unlock(NEW_MASTER.toCharArray())
            val r = b.checkManifest()
            expectEq(r.state, ManifestState.ADOPTED, "adopsi"); expectEq(r.sealed, 2, "dua entri")
            ok(b, "setelah adopsi")
        } finally { SessionLock.lock(); ta.deleteRecursively(); tb.deleteRecursively() }
    }
}

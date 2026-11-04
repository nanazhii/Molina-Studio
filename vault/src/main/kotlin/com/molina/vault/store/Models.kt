package com.molina.vault.store

import com.molina.vault.crypto.InvalidVaultException
import java.nio.ByteBuffer

enum class RecordType { PASSWORD, NOTE, KEYSTORE, SECRET, FILE }

// MOLINA-H6-FOLDER: folder (datar, satu level) dan tag bersifat opsional; record lama tanpa field ini tetap valid.
data class RecordMeta(
    val id: String,
    val type: RecordType,
    val title: String,
    val modifiedAt: Long,
    val folder: String = "",
    val tags: List<String> = emptyList(),
)

object LabelPolicy {
    const val MAX_FOLDER = 60
    const val MAX_TAG = 30
    const val MAX_TAGS = 10

    fun cleanFolder(raw: String): String =
        raw.trim().split(' ').filter { it.isNotEmpty() }.joinToString(" ").take(MAX_FOLDER)

    fun parseTags(raw: String): List<String> =
        raw.split(',', ';')
            .map { it.trim().removePrefix("#").trim().take(MAX_TAG) }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(MAX_TAGS)
}

data class VaultListing(val items: List<RecordMeta>, val corrupt: Int)

// MOLINA-H14-GUDANG: laporan layar Gudang dan hasil pemeriksaan integritas.
class AuditBad(val id: String, val title: String?, val reason: String)
class AuditResult(val checked: Int, val ok: Int, val bad: List<AuditBad>, val cancelled: Boolean, val bytes: Long, val manifest: ManifestReport? = null)

// MOLINA-H15-MANIFEST: hasil pemeriksaan manifest tersegel.
enum class ManifestState { OK, ADOPTED, ALERT }
enum class IssueKind { MISSING, CHANGED, UNKNOWN }
class ManifestIssue(val id: String, val title: String?, val kind: IssueKind)
class ManifestReport(val state: ManifestState, val issues: List<ManifestIssue>, val problem: String?, val sealed: Int, val counter: Long)
class UsageReport(
    val perType: Map<RecordType, Int>,
    val records: Int,
    val corrupt: Int,
    val diskBytes: Long,
    val largeCount: Int,
    val largeDiskBytes: Long,
    val largest: List<Pair<RecordMeta, Long>>,
)

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

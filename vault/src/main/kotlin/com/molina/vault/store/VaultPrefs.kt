package com.molina.vault.store

import android.content.Context

// MOLINA-H7B
/** Pengaturan dan status non-rahasia: auto-lock dan pengingat cadangan. Tidak memuat isi, judul, atau jumlah entri. */
class VaultPrefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("vault_state", Context.MODE_PRIVATE)

    var autoLockMs: Long
        get() = p.getLong("autolock_ms", 120_000L).coerceIn(15_000L, 600_000L)
        set(v) { p.edit().putLong("autolock_ms", v).apply() }

    val lastBackupAt: Long get() = p.getLong("last_backup_at", 0L)
    val changesSinceBackup: Int get() = p.getInt("changes_since_backup", 0)

    // MOLINA-H14-GUDANG: waktu pemeriksaan integritas terakhir yang lolos penuh.
    val lastAuditAt: Long get() = p.getLong("last_audit_at", 0L)

    fun markAudited() { p.edit().putLong("last_audit_at", System.currentTimeMillis()).apply() }

    fun noteChange() { p.edit().putInt("changes_since_backup", changesSinceBackup + 1).apply() }

    fun markBackedUp() {
        p.edit().putLong("last_backup_at", System.currentTimeMillis()).putInt("changes_since_backup", 0).apply()
    }
}

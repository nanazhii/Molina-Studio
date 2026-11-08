package com.molina.vault

// MOLINA-H1
import android.os.Build
import com.molina.vault.crypto.SecurityPatch
import com.molina.vault.crypto.VaultCrypto
import java.time.LocalDate

/** Peringatan kondisi perangkat yang menurunkan jaminan keamanan. Hanya informasi; tidak memblokir. */
internal object DeviceHealth {
    fun warnings(): List<String> {
        val out = ArrayList<String>()
        val patch: String? = Build.VERSION.SECURITY_PATCH
        val age = SecurityPatch.ageDays(patch, LocalDate.now())
        if (SecurityPatch.isStale(age)) {
            val months = (age ?: 0L) / 30
            out.add("Patch keamanan perangkat ($patch) sudah sekitar $months bulan. Celah root yang sudah publik mungkin belum ditambal; jangan simpan rahasia bernilai tertinggi di perangkat ini.")
        }
        if (VaultCrypto.deviceKeyInSecureHardware() == false) {
            out.add("Kunci perangkat hanya berupa perangkat lunak (bukan TEE/StrongBox). Pada perangkat root kunci itu bisa diekstrak, sehingga perlindungan bergantung pada master password.")
        }
        return out
    }
}

package com.molina.core.common

/**
 * Laporan komponen sebagai teks polos untuk ditempel ke laporan bug atau issue [BUNDLE-REPORT]. JVM murni agar bisa diuji.
 * Hanya memuat nama, versi, peran, asal, dan jaminan keamanan: tanpa URL, tanpa data perangkat atau pengguna.
 */
object ComponentReport {
    private const val MAX_VERSION = 120

    /** Satu baris: spasi/baris baru beruntun jadi satu spasi, dipotong agar keluaran alat (mis. ffmpeg) tidak membanjiri laporan. */
    private fun oneLine(s: String): String {
        val t = s.trim().replace(Regex("\\s+"), " ")
        return if (t.length > MAX_VERSION) t.take(MAX_VERSION - 1) + "\u2026" else t
    }

    /**
     * [versionOf] memberi teks versi yang sama dengan yang tampil di kartu; [extrasOf] baris tambahan per komponen
     * (mis. waktu cek terakhir, pembaruan tersedia) yang dicetak menjorok di bawah komponennya.
     */
    fun format(
        items: List<BundledComponent>,
        security: SecurityReport,
        generatedAt: String,
        versionOf: (BundledComponent) -> String,
        extrasOf: (BundledComponent) -> List<String> = { emptyList() }
    ): String {
        val sb = StringBuilder()
        sb.append("Molina - laporan komponen\n")
        sb.append("Dibuat: ").append(oneLine(generatedAt)).append('\n')
        sb.append(security.headline()).append('\n')
        sb.append(
            if (security.requiredVerified) "Semua komponen Wajib terverifikasi."
            else "Perlu ditinjau (Wajib tanpa hash): " + security.requiredWithoutHash.joinToString(", ")
        ).append('\n')
        for (role in ComponentRole.values()) {
            val group = items.filter { it.role == role }
            if (group.isEmpty()) continue
            sb.append('\n').append(role.label).append(" (").append(group.size).append(")\n")
            for (c in group) {
                sb.append("- ").append(c.name).append(' ').append(oneLine(versionOf(c)))
                    .append(" \u00b7 ").append(c.origin.label)
                    .append(" \u00b7 ").append(c.assurance.label).append('\n')
                for (e in extrasOf(c)) {
                    val line = oneLine(e)
                    if (line.isNotEmpty()) sb.append("    ").append(line).append('\n')
                }
            }
        }
        return sb.toString()
    }
}

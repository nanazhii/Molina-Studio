package com.molina.studio.settings

/** [SHIELD-CARD] [SHIELD-REPORT] Ringkasan status perlindungan untuk halaman Molina Shield. Murni (tanpa Android) agar mudah diuji. */
internal object ShieldSummary {
    data class Input(
        val shield: Boolean,
        val trackers: Boolean,
        val lanAllowed: Boolean,
        val secureScreen: Boolean,
        val blockPlaylistRtmp: Boolean,
        val debuggable: Boolean,
        val blocked: Int,
        val fingerprint: String?,
    )

    class Result(val title: String, val body: String, val report: String)

    const val LIMITS = "Tidak tercakup: rtsp; unduhan yt-dlp hanya diperiksa URL awalnya."
    private const val SHORT_FP = 23

    fun build(i: Input): Result {
        // Skor hanya menghitung perlindungan inti. Blokir rtmp di playlist opsional (merusak kanal IPTV rtmp),
        // jadi tidak boleh memaksa pengguna menurunkan fungsi demi skor penuh.
        val core = listOf(
            "Filter http/https (Shield)" to i.shield,
            "Blokir pelacak dan iklan" to i.trackers,
            "Jaringan lokal ditolak" to !i.lanAllowed,
            "Layar privat" to i.secureScreen,
            "Build rilis (tidak debuggable)" to !i.debuggable,
        )
        val on = core.count { it.second }
        val off = core.filter { !it.second }.map { it.first }
        val title = "Perlindungan: $on dari ${core.size} aktif"
        val shortFp = i.fingerprint?.let { if (it.length > SHORT_FP) it.take(SHORT_FP) + "\u2026" else it } ?: "tidak terbaca"

        val body = StringBuilder()
        body.append("Diblokir sesi ini: ").append(i.blocked)
        body.append("\nTanda tangan: ").append(shortFp)
        if (off.isEmpty()) body.append("\nSemua perlindungan inti aktif.")
        else body.append("\nBelum aktif: ").append(off.joinToString(", "))
        if (!i.blockPlaylistRtmp) body.append("\nOpsional: blokir rtmp di playlist mati.")
        body.append("\n").append(LIMITS)
        body.append("\nKetuk untuk menyalin laporan.")

        // Laporan untuk ditempel ke catatan/isu: tanpa host atau URL, hanya status dan sidik jari lengkap.
        val rep = StringBuilder("Molina Shield - laporan perlindungan\n").append(title)
        for ((name, ok) in core) rep.append("\n").append(name).append(": ").append(if (ok) "aktif" else "MATI")
        rep.append("\nOpsional, blokir rtmp di playlist: ").append(if (i.blockPlaylistRtmp) "aktif" else "mati")
        rep.append("\nDiblokir sesi ini: ").append(i.blocked)
        rep.append("\nTanda tangan SHA-256: ").append(i.fingerprint ?: "tidak terbaca")
        rep.append("\n").append(LIMITS)
        return Result(title, body.toString(), rep.toString())
    }
}

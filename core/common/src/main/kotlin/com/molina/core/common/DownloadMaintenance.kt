package com.molina.core.common

/** Sesi perawatan skrip unduhan (tanpa URL): periksa lingkungan atau perbarui yt-dlp. */
enum class MaintenanceKind(val label: String) {
    DOCTOR("Periksa lingkungan"),
    UPDATE("Perbarui yt-dlp"),
}

/** Nada hasil untuk UI: dipetakan ke warna oleh layar. */
enum class MaintenanceTone { GOOD, WARN, BAD, BUSY }

/** Satu baris tabel hasil Doctor. [ok] = false untuk komponen wajib yang bermasalah. */
data class DoctorLine(val label: String, val value: String, val ok: Boolean)

/** Format hasil `--doctor` / `--update-ytdlp` untuk halaman Unduhan. Murni JVM (tanpa Android). */
object DownloadMaintenance {
    const val TIMEOUT_MS = 10L * 60 * 1000

    fun args(kind: MaintenanceKind): List<String> = when (kind) {
        MaintenanceKind.DOCTOR -> DownloadCommand.doctorArgs()
        MaintenanceKind.UPDATE -> DownloadCommand.updateArgs()
    }

    fun doctorLines(d: DownloadEvent.Doctor): List<DoctorLine> = buildList {
        val yt = d.ytdlpVersion?.let { v -> v + (d.ytdlpMethod?.let { " ($it)" } ?: "") }
        add(DoctorLine("yt-dlp", yt ?: "tidak terpasang", yt != null))
        add(DoctorLine("ffmpeg", d.ffmpegVersion ?: "tidak terpasang", d.ffmpegVersion != null))
        add(DoctorLine("ffprobe", if (d.ffprobe) "ada" else "tidak ada", d.ffprobe))
        add(DoctorLine("Sampul M4A/FLAC", if (d.mutagen) "siap (mutagen)" else "belum (opsional)", true))
        if ("no_ca" in d.issues) add(DoctorLine("Sertifikat HTTPS", "tidak ada", false))
        d.python?.let { add(DoctorLine("Python", it, true)) }
        d.videoDir?.let { add(DoctorLine("Folder video", it, true)) }
        d.audioDir?.let { add(DoctorLine("Folder audio", it, true)) }
        d.freeMb?.let { add(DoctorLine("Ruang kosong", DownloadFormat.size(it.coerceAtLeast(0L) * 1024L * 1024L), "low_space" !in d.issues)) }
    }

    /** Petunjuk perbaikan siap tampil per kode isu. Kode tak dikenal tetap ditampilkan agar tidak hilang diam-diam. */
    fun issueHint(code: String): String = when (code) {
        "no_ytdlp" -> "yt-dlp belum terpasang. Di Debian: apt install -y yt-dlp (atau pipx install yt-dlp)."
        "no_ffmpeg" -> "ffmpeg belum terpasang. Di Debian: apt install -y ffmpeg."
        "no_ffprobe" -> "ffprobe tidak ada (biasanya satu paket dengan ffmpeg)."
        "low_space" -> "Ruang kosong menipis; unduhan besar bisa gagal."
        "dir_fallback" -> "Folder tujuan bawaan tidak bisa dipakai; memakai folder cadangan."
        "no_mutagen" -> "Sampul M4A/FLAC butuh python3-mutagen: apt install -y python3-mutagen (MP3 tidak perlu)."
        "no_ca" -> "Sertifikat HTTPS belum ada, unduhan bisa gagal. Jalankan Starter (Setelan > Paket > Debian) atau: apt install -y ca-certificates."
        else -> "Catatan: $code"
    }

    fun issueHints(d: DownloadEvent.Doctor): List<String> = d.issues.distinct().take(10).map(::issueHint)

    /** Sehat = tidak ada isu selain info opsional `no_mutagen`. */
    fun doctorTone(d: DownloadEvent.Doctor): MaintenanceTone =
        if (d.issues.any { it != "no_mutagen" }) MaintenanceTone.WARN else MaintenanceTone.GOOD

    fun updateText(u: DownloadEvent.Update): String = when (u.status) {
        "start" -> "Memperbarui yt-dlp" + (u.method?.let { " lewat $it" } ?: "") + (u.before?.let { " (sekarang $it)" } ?: "") + "…"
        "ok" -> "yt-dlp diperbarui: ${u.before ?: "?"} → ${u.after ?: "?"}"
        "latest" -> u.msg?.let { LogRedact.text(it).take(200) } ?: "yt-dlp sudah terbaru (${u.after ?: u.before ?: "?"})."
        "failed" -> "Pembaruan gagal" + (u.msg?.let { ": " + LogRedact.text(it).take(200) } ?: ".")
        else -> "Status pembaruan tidak dikenal."
    }

    fun updateTone(u: DownloadEvent.Update): MaintenanceTone = when (u.status) {
        "ok", "latest" -> MaintenanceTone.GOOD
        "failed" -> MaintenanceTone.BAD
        "start" -> MaintenanceTone.BUSY
        else -> MaintenanceTone.WARN
    }

    /** Judul ringkas kartu ringkasan saat sesi perawatan berjalan atau selesai. */
    fun summaryText(kind: MaintenanceKind, running: Boolean, fatal: Boolean, hasResult: Boolean): String = when {
        running -> if (kind == MaintenanceKind.DOCTOR) "Memeriksa lingkungan…" else "Memperbarui yt-dlp…"
        fatal -> "${kind.label} gagal dijalankan"
        hasResult -> "${kind.label} selesai"
        else -> "${kind.label} berhenti tanpa hasil"
    }
}

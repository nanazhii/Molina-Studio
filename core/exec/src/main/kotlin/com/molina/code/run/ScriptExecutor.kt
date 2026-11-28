package com.molina.code.run

import com.molina.code.preview.RunPlan

/**
 * Kontrak eksekusi skrip. Modul :feature:code tidak tahu PRoot/Debian; :app memasang implementasinya
 * (lihat CodeSession.scriptExecutor). Semua callback [RunListener] boleh datang dari thread latar.
 */
interface ScriptExecutor {
    /** Mulai menjalankan dan segera kembali. Hasil dilaporkan lewat [listener]. */
    fun start(request: RunRequest, listener: RunListener): RunHandle
}

/**
 * Permintaan satu kali jalan. [scriptText] adalah isi editor saat ini (tidak harus sudah disimpan),
 * [hostDir] folder berkas di Android (untuk cwd bila terlihat di guest), null bila tab belum punya berkas.
 */
class RunRequest(
    val name: String,
    val scriptText: String,
    val plan: RunPlan,
    val hostDir: String?,
    val timeoutMs: Long,
    /** Argumen skrip (argv[1..]), diteruskan apa adanya tanpa shell. Tidak pernah dicatat di log. */
    val args: List<String> = emptyList(),
    /** Variabel lingkungan tambahan di guest (mis. MOLINA_ROOT). Divalidasi oleh ScriptCommand.validate. */
    val env: Map<String, String> = emptyMap(),
    /** Batas total karakter keluaran yang diteruskan ke listener; kelebihannya dibuang (aliran tetap dikuras). */
    val maxOutputChars: Long = 1_000_000L,
    /**
     * [CODE-FORMAT] Bila terisi, jalankan argv ini APA ADANYA di guest (tanpa berkas sementara dan tanpa
     * shell); [plan] hanya dipakai untuk label. argv[0] dicari di PATH guest.
     */
    val guestArgv: List<String>? = null,
    /** [CODE-FORMAT] Teks yang dikirim ke stdin proses (UTF-8) lalu stdin ditutup. Null = stdin langsung ditutup. */
    val stdinText: String? = null,
    /** [CODE-FORMAT] Pesan siap tampil bila argv[0] tidak ditemukan di Debian. */
    val missingHint: String? = null
)

interface RunListener {
    /** Potongan keluaran apa adanya (belum dipecah per baris). */
    fun onOutput(text: String, isError: Boolean)

    /** Dipanggil tepat sekali, setelah semua keluaran terkirim. */
    fun onFinished(result: RunResult)
}

interface RunHandle {
    /** Hentikan skrip dan seluruh proses turunannya. Aman dipanggil berkali-kali dan dari main thread. */
    fun cancel()
}

/**
 * Hasil akhir. [startError] terisi bila skrip tidak pernah berjalan (mis. Debian belum terpasang);
 * pesannya sudah siap tampil.
 */
class RunResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val truncated: Boolean,
    val millis: Long,
    val startError: String? = null
)

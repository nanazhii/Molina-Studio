package com.molina.core.common

/** Seberapa penting komponen bagi Molina. */
enum class ComponentRole(val label: String) {
    Wajib("Wajib"),
    Pendukung("Pendukung"),
    Opsional("Opsional")
}

/** Dari mana komponen datang. */
enum class ComponentOrigin(val label: String) {
    Apk("Dibundel di APK"),
    Runtime("Diunduh saat dipakai"),
    Debian("Dipasang di Debian")
}

/**
 * Jaminan keamanan yang BENAR-BENAR ada di repo. [Sha256] hanya boleh dipakai bila ada pin hash nyata
 * ([BundledComponent.pinRef]); scripts/bundle_gate.py menggagalkan CI bila klaim ini tidak punya pin.
 */
/** [verified] = ada cara independen memeriksa keasliannya (hash/tanda tangan/kode sumber di repo). */
enum class Assurance(val label: String, val verified: Boolean) {
    Sha256("SHA-256 dipin dan diverifikasi", true),
    Source("Kode sumber di repo, dibangun CI", true),
    Apt("Repositori resmi Debian (apt)", true),
    Commit("Sumber dipin ke commit", false),
    Version("Versi dipin di Gradle", false),
    Asset("Aset dibundel beserta lisensi", false)
}

/** Ringkasan jujur jaminan keamanan seluruh daftar (ditampilkan di UI). */
data class SecurityReport(
    val total: Int,
    val hashPinned: Int,
    val aptSigned: Int,
    val sourceInRepo: Int,
    val versionPinned: Int,
    val assets: Int,
    val requiredWithoutHash: List<String>
) {
    /** true bila semua komponen Wajib punya hash terverifikasi atau kode sumber di repo. */
    val requiredVerified: Boolean get() = requiredWithoutHash.isEmpty()

    fun headline(): String =
        "$hashPinned dari $total diverifikasi SHA-256 \u00b7 $sourceInRepo kode sumber di repo \u00b7 " +
            "$aptSigned apt resmi \u00b7 $versionPinned dipin versi/commit \u00b7 $assets aset berlisensi"
}

/**
 * Satu komponen pihak ketiga yang ada di Molina. [version] kosong = ditentukan saat berjalan (mis. yt-dlp).
 * [source] ditulis tanpa skema URL agar gerbang telemetri tetap mengawasi semua host di kode.
 */
data class BundledComponent(
    val id: String,
    val name: String,
    val version: String,
    val role: ComponentRole,
    val origin: ComponentOrigin,
    val assurance: Assurance,
    val source: String,
    val license: String,
    val usedBy: String,
    val note: String,
    val pinRef: String? = null
)

/** Daftar tunggal komponen yang dibundel/dipakai Molina. JVM murni agar bisa diuji. */
object BundledComponents {
    const val PIN_NEWPIPE = "scripts/newpipe/pins.sha256"
    const val PIN_NATIVE = "native/prebuilt.env:NATIVE_SHA256"
    const val PIN_ROOTFS = "Rootfs.kt:ROOTFS_SHA256"
    const val PIN_YTDLP = "yt-dlp:SHA2-256SUMS"
    const val PIN_PROOT = "scripts/proot/pins.sha256"
    const val PIN_SEVENZIP = "scripts/sevenzip/pins.sha256"

    /** [newpipeVersion] disuntikkan dari NewPipeInfo.VERSION agar hanya ada satu sumber kebenaran. */
    fun all(newpipeVersion: String): List<BundledComponent> = listOf(
        BundledComponent(
            "newpipe", "NewPipeExtractor", newpipeVersion, ComponentRole.Wajib, ComponentOrigin.Apk, Assurance.Sha256,
            "TeamNewPipe/NewPipeExtractor via JitPack", "GPL-3.0", "Pencarian dan pemutaran YouTube (Cinema)",
            "Mesin utama. Jar dan dependensinya dipin SHA-256; build gagal bila hash tidak cocok.", PIN_NEWPIPE
        ),
        BundledComponent(
            "libmpv", "libmpv + FFmpeg", "FFmpeg n9.0.2", ComponentRole.Wajib, ComponentOrigin.Apk, Assurance.Sha256,
            "mpv-android snapshot 62013d4 (mpv, dav1d, libass, libplacebo, FFmpeg dipin commit)", "GPL-3.0-or-later (mpv GPL-2.0+, FFmpeg GPL, dav1d BSD-2, libass ISC, libplacebo LGPL-2.1+)",
            "Molina Player / Cinema", "Dibangun lewat CI dari commit dipin; tgz hasil build diverifikasi SHA-256 saat build APK.", PIN_NATIVE
        ),
        BundledComponent(
            "terminal", "Terminal emulator + view", "Termux v0.118.0", ComponentRole.Wajib, ComponentOrigin.Apk, Assurance.Source,
            "termux/termux-app commit 6e2689f", "Apache-2.0", "Molina Terminal",
            "Kode sumber disalin dan di-rebrand (com.molina.terminal); atribusi dipertahankan.", null
        ),
        BundledComponent(
            "proot", "PRoot", "commit 01f83b88", ComponentRole.Pendukung, ComponentOrigin.Apk, Assurance.Sha256,
            "green-green-avk/build-proot-android", "GPL-2.0-or-later", "Debian, yt-dlp, Script execution",
            "Biner prebuilt di jniLibs (arm64), hash dipin dan dicek CI. Tanpa PRoot, fitur berbasis Debian tidak jalan.", PIN_PROOT
        ),
        BundledComponent(
            "sevenzip", "7-Zip", "26.05 (7zzs statis, ditambal seccomp)", ComponentRole.Pendukung, ComponentOrigin.Apk, Assurance.Sha256,
            "ip7z/7zip rilis resmi (7-zip.org)", "LGPL-2.1-or-later / BSD / unRAR restriction", "Kompres dan ekstrak arsip di Penjelajah",
            "Biner statis arm64 di jniLibs (lib7zz.so), hash dipin dan dicek CI. Berjalan langsung (daftar isi/kompres); ekstrak di PRoot bila Debian ada. Tanpa apt install.", PIN_SEVENZIP
        ),
        BundledComponent(
            "rootfs", "Debian rootfs", "trixie-slim arm64", ComponentRole.Pendukung, ComponentOrigin.Runtime, Assurance.Sha256,
            "debuerreotype/docker-debian-artifacts commit 9c4818e", "Beragam (paket Debian)", "Terminal Debian, alat Debian, yt-dlp",
            "Diunduh saat dipasang; hash SHA-256 diperiksa dan unduhan yang tidak cocok dibuang.", PIN_ROOTFS
        ),
        BundledComponent(
            "ytdlp", "yt-dlp", "", ComponentRole.Opsional, ComponentOrigin.Runtime, Assurance.Sha256,
            "yt-dlp/yt-dlp rilis resmi", "Unlicense", "Mesin cadangan unduhan (default mati)",
            "Hanya diunduh saat Anda menekan Pasang/Perbarui; diverifikasi lewat SHA2-256SUMS rilis.", PIN_YTDLP
        ),
        BundledComponent(
            "fonts", "JetBrains Mono", "", ComponentRole.Opsional, ComponentOrigin.Apk, Assurance.Asset,
            "JetBrains/JetBrainsMono", "SIL OFL 1.1", "Font Terminal",
            "Salinan lisensi OFL disertakan.", null
        ),
        BundledComponent(
            "archive", "commons-compress, zip4j, xz", "1.27.1 / 2.11.5 / 1.10", ComponentRole.Pendukung, ComponentOrigin.Apk, Assurance.Version,
            "Apache Commons, srikanth-lingala/zip4j, tukaani.org (Maven Central)", "Apache-2.0 / 0BSD", "Arsip di Penjelajah (zip, tar, 7z)",
            "RAR/unrar sengaja tidak disertakan (syarat lisensi).", null
        ),
        BundledComponent(
            "python3", "Python 3", "", ComponentRole.Opsional, ComponentOrigin.Debian, Assurance.Apt,
            "apt Debian", "PSF", "yt-dlp dan skrip Python", "Dipasang dari Paket > Alat Debian. yt-dlp membutuhkannya.", null
        ),
        BundledComponent(
            "git", "Git", "", ComponentRole.Opsional, ComponentOrigin.Debian, Assurance.Apt,
            "apt Debian", "GPL-2.0", "Terminal Debian", "Dipasang dari Paket > Alat Debian.", null
        ),
        BundledComponent(
            "ffmpeg", "FFmpeg (Debian)", "", ComponentRole.Opsional, ComponentOrigin.Debian, Assurance.Apt,
            "apt Debian", "LGPL/GPL", "Konversi audio/video, unduhan yt-dlp", "Dipasang dari Paket > Alat Debian.", null
        )
    )

    /** Jumlah komponen per peran (untuk ringkasan di UI). */
    fun roleCounts(items: List<BundledComponent>): Map<ComponentRole, Int> =
        ComponentRole.values().associateWith { r -> items.count { it.role == r } }

    /** Saring menurut peran; null = semua. */
    fun filter(items: List<BundledComponent>, role: ComponentRole?): List<BundledComponent> =
        if (role == null) items else items.filter { it.role == role }

    /** Hitung ringkasan keamanan; [SecurityReport.requiredWithoutHash] = komponen Wajib yang tak bisa diperiksa mandiri. */
    fun securityReport(items: List<BundledComponent>): SecurityReport = SecurityReport(
        total = items.size,
        hashPinned = items.count { it.assurance == Assurance.Sha256 },
        aptSigned = items.count { it.assurance == Assurance.Apt },
        sourceInRepo = items.count { it.assurance == Assurance.Source },
        versionPinned = items.count { it.assurance == Assurance.Commit || it.assurance == Assurance.Version },
        assets = items.count { it.assurance == Assurance.Asset },
        requiredWithoutHash = items.filter { it.role == ComponentRole.Wajib && !it.assurance.verified }.map { it.name }
    )
}

/**
 * Bantuan "naikkan pin NewPipe": tag dari jaringan TIDAK BOLEH masuk ke papan klip tanpa divalidasi ketat,
 * karena perintah yang disalin akan ditempel pengguna ke terminal (cegah injeksi shell).
 */
object NewPipeBump {
    const val WORKFLOW = "bump-newpipe.yml"
    private val TAG = Regex("v\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}")

    fun isValidTag(tag: String): Boolean = TAG.matches(tag)

    /** Perintah gh untuk memicu workflow; null bila tag tidak valid. */
    fun ghCommand(tag: String): String? = if (isValidTag(tag)) "gh workflow run $WORKFLOW -f version=$tag" else null

    /** Teks status pin untuk kartu NewPipe. */
    fun pinStatus(version: String): String = "Dipin di $version; build gagal bila hash tidak cocok"

    /** Baris ringkasan di panel keamanan: APK yang ada hanya lahir bila CI (verifyNewPipePins + bundle_gate) lulus. */
    fun pinSummary(version: String): String = "Pin NewPipe $version diverifikasi CI saat APK ini dibangun"
}

package com.molina.studio.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** [SIG-INFO] Sidik jari sertifikat tanda tangan APK dan status debuggable, untuk dicocokkan dengan rilis resmi. Lokal, tanpa jaringan. */
object AppSignature {
    data class Info(val sha256: String?, val debuggable: Boolean)

    @Suppress("DEPRECATION")
    fun read(ctx: Context): Info {
        val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val cert: ByteArray? = runCatching {
            val pm = ctx.packageManager
            if (Build.VERSION.SDK_INT >= 28) {
                val si = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                val list = when {
                    si == null -> null
                    si.hasMultipleSigners() -> si.apkContentsSigners
                    else -> si.signingCertificateHistory
                }
                list?.lastOrNull()?.toByteArray()
            } else {
                pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()?.toByteArray()
            }
        }.getOrNull()
        val hex = cert?.let { b ->
            MessageDigest.getInstance("SHA-256").digest(b).joinToString(":") { "%02X".format(it) }
        }
        return Info(hex, debuggable)
    }
}

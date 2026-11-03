package com.molina.vault

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import com.molina.vault.crypto.SessionLock

/** Basis semua layar Vault: anti-screenshot, anti-overlay, dan kunci otomatis saat keluar. */
open class BunkerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        window.decorView.filterTouchesWhenObscured = true
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) SessionLock.lock()
    }

    /** Paket accessibility service non-sistem yang aktif (potensi pembaca layar). */
    protected fun untrustedAccessibilityServices(): List<String> {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .filter { (it.resolveInfo.serviceInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.resolveInfo.serviceInfo.packageName }
    }
}

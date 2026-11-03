package com.molina.vault

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

class MainActivity : BunkerActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(TextView(this).apply {
            text = "Molina Vault"
            textSize = 24f
            setPadding(48, 96, 48, 48)
        })
    }
}

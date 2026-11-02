package com.molina.cinema

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.PendingIntentCompat

class NotificationButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        MpvLog.v(TAG, "NotificationButtonReceiver: ${intent!!.action}")
        // remember to update AndroidManifest.xml too when adding here
        when (intent.action) {
            "$PREFIX.PLAY_PAUSE" -> MPVLib.command(arrayOf("cycle", "pause"))
            "$PREFIX.ACTION_PREV" -> PlaylistNav.prev()
            "$PREFIX.ACTION_NEXT" -> PlaylistNav.next()
        }
    }

    companion object {
        fun createIntent(context: Context, action: String): PendingIntent {
            val intent = Intent("$PREFIX.$action")
            // turn into explicit intent
            intent.component = ComponentName(context, NotificationButtonReceiver::class.java)
            return PendingIntentCompat.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT, false)!!
        }

        private const val TAG = "mpv"
        private const val PREFIX = "com.molina.cinema"
    }
}

package com.molina.studio.cinema

import android.content.Context
import com.molina.core.api.MediaGateway

/** [MEDIA-GW] Implementasi kontrak [MediaGateway]: validasi tautan lewat [CinemaActions], lalu buka pemutar. */
class MediaGatewayImpl(context: Context) : MediaGateway {
    private val app = context.applicationContext

    override fun playUrl(url: String): Boolean {
        val input = CinemaActions.classify(url)
        return input is CinemaActions.Input.Direct && CinemaActions.play(app, input.uri)
    }
}

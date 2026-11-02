// Stub android.media.MediaMetadataRetriever yang dikendalikan tes lewat StubMedia.
package android.media

import android.graphics.Bitmap

object StubMedia {
    val frames = HashMap<String, Bitmap>()
    val covers = HashMap<String, ByteArray>()
    var frameCalls = 0

    fun reset() { frames.clear(); covers.clear(); frameCalls = 0 }
}

class MediaMetadataRetriever {
    companion object {
        const val METADATA_KEY_DURATION = 9
        const val OPTION_CLOSEST_SYNC = 2
    }

    private var path = ""
    fun setDataSource(p: String) { path = p }
    fun extractMetadata(key: Int): String? = if (key == METADATA_KEY_DURATION) "10000" else null
    fun getFrameAtTime(us: Long, option: Int): Bitmap? { StubMedia.frameCalls++; return StubMedia.frames[path] }
    val embeddedPicture: ByteArray? get() = StubMedia.covers[path]
    fun release() {}
}

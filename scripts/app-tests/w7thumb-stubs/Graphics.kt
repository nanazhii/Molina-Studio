// Stub android.graphics untuk uji JVM W7Thumb (hanya bagian yang dipakai). Dekode lewat ImageIO.
package android.graphics

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

class Bitmap(val img: BufferedImage, val tag: String) {
    val width: Int get() = img.width
    val height: Int get() = img.height
    var recycled = false
    fun recycle() { recycled = true }

    companion object {
        fun createScaledBitmap(src: Bitmap, w: Int, h: Int, filter: Boolean): Bitmap {
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            g.drawImage(src.img, 0, 0, w, h, null)
            g.dispose()
            return Bitmap(out, src.tag)
        }
    }
}

object BitmapFactory {
    class Options {
        var inJustDecodeBounds = false
        var inSampleSize = 1
        var outWidth = 0
        var outHeight = 0
    }

    /** Jumlah panggilan decodeFile (termasuk mode bounds); dipakai tes untuk membuktikan tidak ada dekode/cache bekerja. */
    var decodeFileCalls = 0

    fun reset() { decodeFileCalls = 0 }

    fun decodeFile(path: String, o: Options?): Bitmap? {
        decodeFileCalls++
        return decode(try { File(path).readBytes() } catch (e: Exception) { return null }, o, "file")
    }

    fun decodeByteArray(b: ByteArray, off: Int, len: Int, o: Options?): Bitmap? = decode(b.copyOfRange(off, off + len), o, "bytes")

    private fun decode(bytes: ByteArray, o: Options?, tag: String): Bitmap? {
        val img = try { ImageIO.read(ByteArrayInputStream(bytes)) } catch (e: Exception) { null } ?: return null
        if (o != null && o.inJustDecodeBounds) {
            o.outWidth = img.width
            o.outHeight = img.height
            return null
        }
        val s = maxOf(1, o?.inSampleSize ?: 1)
        val full = Bitmap(img, tag)
        if (s == 1) return full
        return Bitmap.createScaledBitmap(full, (img.width + s - 1) / s, (img.height + s - 1) / s, true)
    }
}

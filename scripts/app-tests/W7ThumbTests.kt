import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.StubMedia
import com.molina.studio.cinema.W7Thumb
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

/** withContext di stub berjalan inline, jadi coroutine selesai sinkron. */
private fun <T> blocking(block: suspend () -> T): T {
    var out: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
    return out!!.getOrThrow()
}

private fun img(w: Int, h: Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
private fun writeImg(f: File, w: Int, h: Int, fmt: String) { ImageIO.write(img(w, h), fmt, f) }
private fun frameBmp() = Bitmap(img(320, 180), "frame")
private fun reset() { StubMedia.reset(); BitmapFactory.reset() }

fun main() {
    val d = Files.createTempDirectory("w7thumb").toFile()
    try {
        // 1. tanpa poster -> perilaku lama: frame video
        reset()
        val v1 = File(d, "a.mp4").apply { writeText("x") }
        StubMedia.frames[v1.path] = frameBmp()
        val r1 = blocking { W7Thumb.load(v1) }
        check("tanpa poster: memakai frame video", r1 != null && r1.tag == "frame" && StubMedia.frameCalls >= 1)
        check("tanpa poster: hasil <= 160x90", r1 != null && r1.width <= 160 && r1.height <= 90)

        // 2. poster jpg di samping video -> dipakai, frame tidak disentuh
        reset()
        val v2 = File(d, "b [x1].mp4").apply { writeText("x") }
        writeImg(File(d, "b [x1].jpg"), 640, 360, "jpg")
        StubMedia.frames[v2.path] = frameBmp()
        val r2 = blocking { W7Thumb.load(v2) }
        check("poster: dipakai, frame video tidak diekstrak", r2 != null && r2.tag == "file" && StubMedia.frameCalls == 0)
        check("poster: didekode dengan sampling ke 160x90", r2 != null && r2.width == 160 && r2.height == 90)
        val calls = BitmapFactory.decodeFileCalls
        val r2b = blocking { W7Thumb.load(v2) }
        check("poster: pemuatan kedua dari cache tanpa dekode ulang", r2b === r2 && BitmapFactory.decodeFileCalls == calls)

        // 3. poster muncul belakangan -> kunci cache berubah, thumbnail frame lama digantikan
        reset()
        val v3 = File(d, "c.mkv").apply { writeText("x") }
        StubMedia.frames[v3.path] = frameBmp()
        val first = blocking { W7Thumb.load(v3) }
        check("poster belakangan: awalnya frame", first != null && first.tag == "frame")
        writeImg(File(d, "c.png"), 400, 225, "png")
        val second = blocking { W7Thumb.load(v3) }
        check("poster belakangan: berikutnya memakai poster png", second != null && second.tag == "file")

        // 4. poster rusak -> kembali ke frame
        reset()
        val v4 = File(d, "e.mp4").apply { writeText("x") }
        File(d, "e.jpg").writeBytes(ByteArray(2000) { (it * 7).toByte() })
        StubMedia.frames[v4.path] = frameBmp()
        val r4 = blocking { W7Thumb.load(v4) }
        check("poster rusak: jatuh ke frame video", r4 != null && r4.tag == "frame")

        // 5. poster terlalu besar (> 8 MB) -> tidak didekode sama sekali
        reset()
        val v5 = File(d, "f.mp4").apply { writeText("x") }
        RandomAccessFile(File(d, "f.jpg"), "rw").use { it.setLength(9L * 1024 * 1024) }
        StubMedia.frames[v5.path] = frameBmp()
        val r5 = blocking { W7Thumb.load(v5) }
        check("poster > 8 MB: tidak didekode, memakai frame", r5 != null && r5.tag == "frame" && BitmapFactory.decodeFileCalls == 0)

        // 6. audio dengan berkas gambar senama -> jalur cover art lama, poster diabaikan
        reset()
        val v6 = File(d, "g.mp3").apply { writeText("x") }
        writeImg(File(d, "g.jpg"), 300, 300, "jpg")
        val coverBytes = java.io.ByteArrayOutputStream().also { ImageIO.write(img(200, 200), "png", it) }.toByteArray()
        StubMedia.covers[v6.path] = coverBytes
        val r6 = blocking { W7Thumb.load(v6) }
        check("audio: memakai cover art tertanam, bukan jpg senama", r6 != null && r6.tag == "bytes" && BitmapFactory.decodeFileCalls == 0)

        // 7. video tanpa poster dan frame gagal -> null, kegagalan dicatat (tidak dicoba berulang)
        reset()
        val v7 = File(d, "h.mp4").apply { writeText("x") }
        check("gagal: hasil null", blocking { W7Thumb.load(v7) } == null)
        val fc = StubMedia.frameCalls
        check("gagal: tidak diulang pada pemuatan berikutnya", blocking { W7Thumb.load(v7) } == null && StubMedia.frameCalls == fc)

        // 8. poster milik video lain tidak ikut
        reset()
        val v8 = File(d, "i.mp4").apply { writeText("x") }
        writeImg(File(d, "lain.jpg"), 640, 360, "jpg")
        StubMedia.frames[v8.path] = frameBmp()
        val r8 = blocking { W7Thumb.load(v8) }
        check("poster video lain tidak dipakai", r8 != null && r8.tag == "frame" && BitmapFactory.decodeFileCalls == 0)
    } finally {
        d.deleteRecursively()
    }
    if (fails > 0) { println("$fails pemeriksaan GAGAL"); System.exit(1) }
    println("semua uji lulus")
}

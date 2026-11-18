import com.molina.core.exec.ProotHostEnv
import java.io.File
import kotlin.system.exitProcess

private var fails = 0
private fun check(name: String, ok: Boolean) { if (!ok) { fails++; println("GAGAL: $name") } }

fun main() {
    val tmp = File("/c/proot-tmp")
    val home = File("/f")
    val full = mapOf("ANDROID_ROOT" to "/system", "ANDROID_DATA" to "/data")
    val m = ProotHostEnv.map("/n", tmp, home) { full[it] }
    check("loader", m["PROOT_LOADER"] == "/n/libproot_loader.so")
    check("tmp dir", m["PROOT_TMP_DIR"] == tmp.absolutePath && m["TMPDIR"] == tmp.absolutePath)
    check("PATH host", m["PATH"] == "/system/bin")
    check("HOME", m["HOME"] == home.absolutePath)
    check("ANDROID_ROOT/DATA diteruskan", m["ANDROID_ROOT"] == "/system" && m["ANDROID_DATA"] == "/data")
    check("urutan tetap", m.keys.toList() == listOf("PROOT_LOADER", "PROOT_TMP_DIR", "TMPDIR", "PATH", "HOME", "ANDROID_ROOT", "ANDROID_DATA"))
    val bare = ProotHostEnv.map("/n", tmp, home) { null }
    check("tanpa ANDROID_* bila host tidak punya", "ANDROID_ROOT" !in bare && "ANDROID_DATA" !in bare && bare.size == 5)
    val arr = ProotHostEnv.asArray("/n", tmp, home) { full[it] }
    check("asArray = map", arr.toList() == m.map { "${it.key}=${it.value}" })
    check("nama berkas pustaka", ProotHostEnv.PROOT_LIB == "libproot.so" && ProotHostEnv.TMP_DIR_NAME == "proot-tmp")
    if (fails > 0) exitProcess(1)
    println("ProotHostEnv OK")
}

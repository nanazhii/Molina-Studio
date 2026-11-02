import com.molina.core.common.YtdlpCommand
import com.molina.studio.code.ScriptCommand

private var fails = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("ok   $name") else { fails++; println("GAGAL $name") }
}

fun main() {
    val base = ScriptCommand.prootArgs("/r", "/root", listOf("--bind=/a:/b"), "/usr/bin/python3", "/tmp/molina-run/x.py")

    val golden = listOf(
        "--link2symlink", "--kill-on-exit", "-0", "--rootfs=/r", "--cwd=/root",
        "--bind=/dev", "--bind=/proc", "--bind=/sys", "--bind=/a:/b",
        "/usr/bin/env", "-i", "HOME=/root", "LANG=C.UTF-8", "TERM=dumb",
        "MALLOC_ARENA_MAX=2", "DEBIAN_FRONTEND=noninteractive",
        "PYTHONDONTWRITEBYTECODE=1", "PYTHONIOENCODING=utf-8", "PYTHONUNBUFFERED=1",
        "PATH=${YtdlpCommand.GUEST_PATH}", "/usr/bin/python3", "/tmp/molina-run/x.py"
    )
    check("tanpa args/env = bentuk lama", base == golden)

    val nasty = listOf("--json", "https://x.test/a b?c=1&d=\"2\"", "; rm -rf /", "\$(id)", "`id`", "-", "--", "judul \u00e9\u00e8 \u2603", "")
    val a = ScriptCommand.prootArgs("/r", "/root", emptyList(), "/bin/sh", "/s.sh", args = nasty)
    check("args menyusul setelah skrip, harfiah dan berurutan", a.takeLast(nasty.size) == nasty && a[a.size - nasty.size - 1] == "/s.sh")
    check("args tidak mengubah awalan", a.dropLast(nasty.size).dropLast(2).takeLast(1) == listOf("PATH=${YtdlpCommand.GUEST_PATH}"))

    // [CODE-FORMAT] mode argv: awalan sama dengan prootArgs, perintah guest utuh dan harfiah
    val av = ScriptCommand.prootArgv("/r", "/root", listOf("--bind=/a:/b"), listOf("black", "-q", "-"))
    check("argv: awalan sama dengan prootArgs", av.dropLast(3) == golden.dropLast(2))
    check("argv: perintah guest utuh di akhir", av.takeLast(3) == listOf("black", "-q", "-"))
    val avEnv = ScriptCommand.prootArgv("/r", "/root", emptyList(), listOf("prettier", "--stdin-filepath=a b.ts"), linkedMapOf("X_Y" to "1"))
    check("argv: env sebelum perintah, argumen harfiah", avEnv.takeLast(3) == listOf("X_Y=1", "prettier", "--stdin-filepath=a b.ts"))

    val e = ScriptCommand.prootArgs("/r", "/root", emptyList(), "/bin/sh", "/s.sh", env = linkedMapOf("MOLINA_ROOT" to "/sdcard/Molina", "B_VAR" to "x y"))
    val ip = e.indexOf("/bin/sh")
    check("env masuk sebelum interpreter, berurutan", e[ip - 2] == "MOLINA_ROOT=/sdcard/Molina" && e[ip - 1] == "B_VAR=x y")

    check("validate kosong = aman", ScriptCommand.validate(emptyList(), emptyMap()) == null)
    check("validate args normal", ScriptCommand.validate(nasty, mapOf("MOLINA_ROOT" to "/sdcard/Molina")) == null)
    check("tolak NUL di argumen", ScriptCommand.validate(listOf("a\u0000b"), emptyMap()) != null)
    check("tolak argumen kepanjangan", ScriptCommand.validate(listOf("x".repeat(4097)), emptyMap()) != null)
    check("tolak terlalu banyak argumen", ScriptCommand.validate(List(257) { "u$it" }, emptyMap()) != null)
    check("terima 256 argumen", ScriptCommand.validate(List(256) { "u$it" }, emptyMap()) == null)
    check("tolak nama env huruf kecil", ScriptCommand.validate(emptyList(), mapOf("molina" to "1")) != null)
    check("tolak nama env berisi =", ScriptCommand.validate(emptyList(), mapOf("A=B" to "1")) != null)
    check("tolak nama env kosong", ScriptCommand.validate(emptyList(), mapOf("" to "1")) != null)
    check("tolak PATH/HOME", ScriptCommand.validate(emptyList(), mapOf("PATH" to "/x")) != null && ScriptCommand.validate(emptyList(), mapOf("HOME" to "/x")) != null)
    check("tolak LD_PRELOAD dan PROOT_*", ScriptCommand.validate(emptyList(), mapOf("LD_PRELOAD" to "x")) != null && ScriptCommand.validate(emptyList(), mapOf("PROOT_NO_SECCOMP" to "1")) != null)
    check("tolak NUL di nilai env", ScriptCommand.validate(emptyList(), mapOf("A" to "x\u0000")) != null)
    check("pesan galat tidak membocorkan nilai", ScriptCommand.validate(emptyList(), mapOf("LD_X" to "RAHASIA"))?.contains("RAHASIA") == false)

    if (fails > 0) { println("$fails uji GAGAL"); kotlin.system.exitProcess(1) }
    println("semua uji lulus")
}

import com.molina.core.common.SensitivePaths
import kotlin.system.exitProcess

private var runs = 0
private var fails = 0
private fun check(name: String, ok: Boolean) { runs++; if (!ok) { fails++; println("GAGAL: $name") } }

fun main() {
    val s = SensitivePaths::isSensitive
    check("molina-keys folder", s("/data/user/0/com.molina.studio/files/rootfs/debian/root/molina-keys/molina.p12"))
    check("keystore lepas", s("/sdcard/Download/molina.p12") && s("/x/Molina.P12") && s("/x/a.jks") && s("/x/a.keystore") && s("/x/a.pfx"))
    check("p12.b64", s("/x/molina.p12.b64"))
    check(".ssh isi apa pun", s("/root/.ssh/id_ed25519") && s("/root/.ssh/known_hosts"))
    check(".gnupg", s("/root/.gnupg/private-keys-v1.d/abc.key"))
    check(".config/gh", s("/root/.config/gh/hosts.yml"))
    check("kredensial", s("/root/.git-credentials") && s("/root/.netrc") && s("/root/.npmrc") && s("/root/.pypirc"))
    check("backslash dinormalkan", s("C:\\x\\.ssh\\id_rsa"))
    check("berkas biasa lolos", !s("/sdcard/Movies/film.mp4") && !s("/root/catatan.txt") && !s("/root/proyek/src/Main.kt"))
    check("sertifikat .pem publik tidak diblokir", !s("/sdcard/Download/cacert.pem"))
    check(".config lain tidak diblokir", !s("/root/.config/htop/htoprc") && !s("/root/.config/ghost/x"))
    check("nama mirip tidak diblokir", !s("/root/ssh/notes.txt") && !s("/root/my.p12notes.txt"))
    check("kredensial cloud dan kunci lepas [SHARE-GUARD+]", s("/root/.aws/credentials") && s("/root/.kube/config") && s("/root/.docker/config.json") && s("/sdcard/id_rsa") && s("/root/.env") && s("/root/.bash_history"))
    check("mirip kredensial tidak diblokir", !s("/root/awsnotes.txt") && !s("/root/env.txt") && !s("/root/id_rsa_notes.txt"))
    check("kosong", !s("") && !s("/"))
    // [SHARE-GUARD++]
    check("token di remote git dan CLI cloud", s("/root/proyek/.git/config") && s("/root/.config/gcloud/credentials.db") && s("/root/.config/rclone/rclone.conf") && s("/root/.config/git/credentials") && s("/root/.azure/accessTokens.json") && s("/root/.cargo/credentials.toml") && s("/root/.gem/credentials"))
    check("kredensial basis data dan vault", s("/root/.pgpass") && s("/root/.my.cnf") && s("/root/.s3cfg") && s("/root/.boto") && s("/root/.vault-token") && s("/root/.htpasswd"))
    check("kunci lain dan salinan keystore", s("/x/kunci.ppk") && s("/x/sandi.kdbx") && s("/sdcard/Download/molina-b64.txt") && s("/sdcard/Download/molina-keystore-cadangan.txt"))
    check("mirip tapi aman [SHARE-GUARD++]", !s("/root/proyek/.git/HEAD") && !s("/root/proyek/.git/objects/ab/cd") && !s("/root/.config/gitk/x") && !s("/root/.cargo/registry/index") && !s("/root/azure.txt") && !s("/sdcard/molina-notes.txt"))
    println("SensitivePathsTests: ${runs - fails} lulus, $fails gagal")
    if (fails > 0) exitProcess(1)
}

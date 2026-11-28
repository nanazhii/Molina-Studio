package com.molina.studio.packages

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Satu alat di Settings > Paket: tiga skrip (pasang, versi, hapus) yang dijalankan di Debian. */
data class ToolItem(
    val id: String,
    val name: String,
    val note: String,
    val install: String,
    val version: String,
    val remove: String,
    val builtin: Boolean = false,
    /** Paket apt yang di-purge saat HAPUS (dipakai pratinjau hapus); kosong = tanpa pratinjau. */
    val purgePkgs: List<String> = emptyList(),
    /** Berkas konfigurasi non-rahasia yang bisa dicadangkan sebelum HAPUS. */
    val backupPaths: List<String> = emptyList(),
    /** [PAKET-SERAGAM] True = pemasangan lewat kode Kotlin (unduh + verifikasi SHA-256), bukan skrip shell. */
    val nativeInstall: Boolean = false,
    /** [PAKET-DEPEN] id alat bawaan yang harus ada lebih dulu (yt-dlp butuh b-python3). */
    val requires: List<String> = emptyList(),
    /** [FFMPEG-LITE] Lencana kecil di samping nama kartu (mis. "Disarankan"); kosong = tanpa lencana. */
    val badge: String = "",
    /** True = lencana menonjol (warna merek); false = lencana redup. */
    val badgeStrong: Boolean = false
)

/** Hasil pemeriksaan versi terakhir; [text] kosong = belum diperiksa. */
class ToolCheck(val text: String, val at: Long, val auto: Boolean = false) // [PAKET-OTOMATIS] auto = hasil cek otomatis

class ToolResult(val ok: Boolean, val message: String, val id: String? = null)

private const val APT = "-o APT::Sandbox::User=root"

/** Alat bawaan (tidak bisa diubah atau dibuang). yt-dlp punya halaman sendiri karena memverifikasi SHA-256. */
object BuiltinTools {
    private val PYTHON_REMOVE = ToolPurge.script(
        listOf("python3"),
        listOf("/root/.cache/pip", "/root/.local/lib/python3*", "/root/.python_history", "/usr/local/lib/python3*"),
        true
    )
    private val YTDLP_REMOVE = ToolPurge.script(listOf("yt-dlp"), ToolPurge.YTDLP_PATHS, false)

    /** [FFMPEG-LITE] Pin rilis ffmpeg-cli; HARUS sama dengan native/ffmpeg-cli.env (dijaga scripts/bundle_gate.py). */
    private const val FFMPEG_LITE_TAG = "ffmpeg-cli-51994c4-run1"
    private const val FFMPEG_LITE_SHA256 = "be04fc3494026b3185b92a7374ed5153b9da610734417f4a12307c4e04e460cd"

    /**
     * [FFMPEG-LITE] Dijalankan di Debian: unduh tar.xz rilis (HTTPS), verifikasi SHA-256 terpin, ekstrak HANYA
     * ffmpeg dan ffprobe, ganti berkas secara atomik di /usr/local/bin, lalu uji jalan. Hanya butuh python3.
     */
    private val FFMPEG_LITE_INSTALL = """set -e
python3 - <<'PYEOF'
import hashlib, io, os, subprocess, sys, tarfile, urllib.request
TAG = "${FFMPEG_LITE_TAG}"
SHA = "${FFMPEG_LITE_SHA256}"
URL = "https://github.com/nanazhii/Molina-Studio/releases/download/" + TAG + "/ffmpeg-molina-arm64.tar.xz"
DEST = "/usr/local/bin"
MAXB = 60 * 1024 * 1024
MINB = 1000000

def fail(msg):
    print("GAGAL: " + msg)
    sys.exit(1)

print("== [1/4] Mengunduh " + TAG + " ==")
try:
    with urllib.request.urlopen(URL, timeout=60) as r:
        data = r.read(MAXB + 1)
except Exception as e:
    fail("unduhan gagal (" + e.__class__.__name__ + "). Periksa internet dan pastikan ca-certificates terpasang.")
if len(data) > MAXB or len(data) < MINB:
    fail("ukuran unduhan tidak wajar")

print("== [2/4] Verifikasi SHA-256 ==")
if hashlib.sha256(data).hexdigest() != SHA:
    fail("SHA-256 tidak cocok; berkas dibuang")
print("SHA-256 cocok")

print("== [3/4] Memasang ke " + DEST + " ==")
os.makedirs(DEST, exist_ok=True)
try:
    tf = tarfile.open(fileobj=io.BytesIO(data), mode="r:xz")
except Exception as e:
    fail("arsip tidak bisa dibuka (" + e.__class__.__name__ + ")")
with tf:
    for name in ("ffmpeg", "ffprobe"):
        try:
            m = tf.getmember(name)
        except KeyError:
            fail(name + " tidak ada di arsip")
        if not m.isreg():
            fail(name + " bukan berkas biasa")
        blob = tf.extractfile(m).read()
        if len(blob) < MINB:
            fail(name + " terlalu kecil")
        part = os.path.join(DEST, "." + name + ".part")
        with open(part, "wb") as f:
            f.write(blob)
        os.chmod(part, 0o755)
        os.replace(part, os.path.join(DEST, name))
        print("Terpasang: " + os.path.join(DEST, name))

print("== [4/4] Uji jalan ==")
for name in ("ffmpeg", "ffprobe"):
    try:
        p = subprocess.run([os.path.join(DEST, name), "-version"], capture_output=True, text=True, timeout=60)
    except Exception as e:
        fail(name + " tidak bisa dijalankan (" + e.__class__.__name__ + ")")
    if p.returncode != 0 or not p.stdout.strip():
        fail(name + " uji versi gagal (kode " + str(p.returncode) + ")")
    print(p.stdout.splitlines()[0])
print("Tuntas.")
PYEOF
"""

    /** [FFMPEG-LITE] Hapus hanya biner Lite di /usr/local/bin; FFmpeg apt (bila ada) tidak disentuh. */
    private val FFMPEG_LITE_REMOVE = """set -e
rm -f /usr/local/bin/ffmpeg /usr/local/bin/ffprobe /usr/local/bin/.ffmpeg.part /usr/local/bin/.ffprobe.part
if [ -e /usr/local/bin/ffmpeg ] || [ -e /usr/local/bin/ffprobe ]; then
  echo "SISA berkas di /usr/local/bin. Penghapusan BELUM tuntas."
  exit 3
fi
echo "Tuntas: FFmpeg Lite dihapus. FFmpeg dari apt (bila ada) tidak disentuh."
"""

    val all: List<ToolItem> = listOf(
        ToolItem(
            "b-python3", "Python 3", "Interpreter Python di Debian. yt-dlp membutuhkannya.",
            "apt update $APT\napt install -y $APT python3 ca-certificates", "python3 --version", PYTHON_REMOVE, true, listOf("python3")
        ),
        ToolItem( // [FFMPEG-LITE]
            "b-ffmpeg-lite", "FFmpeg Lite",
            "Alternatif ringan FFmpeg: ffmpeg + ffprobe statis arm64 (sekitar 9 MB per biner, tanpa apt). Dipasang ke /usr/local/bin sehingga didahulukan dari FFmpeg apt. Butuh Python 3. SHA-256 rilis dipin.",
            FFMPEG_LITE_INSTALL,
            "test -x /usr/local/bin/ffmpeg && /usr/local/bin/ffmpeg -hide_banner -version | head -n 1",
            FFMPEG_LITE_REMOVE, true, emptyList(), emptyList(), false, listOf("b-python3"),
            badge = "Disarankan", badgeStrong = true
        ),
        ToolItem( // [PAKET-SERAGAM]
            "b-ytdlp", "yt-dlp", "Pengunduh tambahan untuk TikTok, Facebook, dan situs lain. Butuh Python 3. Pemasangan memverifikasi SHA-256 rilis resmi.",
            "", "command -v yt-dlp >/dev/null && yt-dlp --version | head -n 1", YTDLP_REMOVE, true, emptyList(), emptyList(), true, listOf("b-python3")
        )
    )
}

/**
 * Skrip "hapus tuntas" di Debian: purge paket (bukan sekadar remove), autoremove dependensi yatim, hapus
 * konfigurasi/cache/sisa berkas, lalu verifikasi. Keluar dengan kode 3 bila masih ada sisa.
 */
object ToolPurge {
    private const val STATE = "^(ii|rc|hi|iU|iF|iH|iW|iT) "
    val YTDLP_PATHS = listOf(
        "/usr/local/bin/yt-dlp*", com.molina.core.download.YtdlpFast.GUEST_DIR, "/root/.local/bin/yt-dlp*", "/usr/bin/yt-dlp",
        "/root/.cache/yt-dlp", "/root/.config/yt-dlp", "/root/yt-dlp.conf", "/etc/yt-dlp.conf", "/etc/yt-dlp"
    )

    /** Simulasi (apt -s, tidak mengubah apa pun): paket yang akan di-purge, termasuk dependen dan dependensi yatim. */
    fun preview(pkgs: List<String>): String {
        val list = pkgs.joinToString(" ") { "'$it'" }
        return buildString {
            append("apt purge --auto-remove -s -y $APT $list > /tmp/.molina_prev.out 2>&1 || true\n")
            append("grep -E '^(Purg|E:)' /tmp/.molina_prev.out || true\n")
            append("rm -f /tmp/.molina_prev.out\n")
        }
    }

    /**
     * Cadangkan berkas konfigurasi non-rahasia ke folder kerja Molina (.molina-backup, dikecualikan dari git lewat
     * .git/info/exclude). Gagal -> keluar kode 4 sehingga penghapusan di bawahnya tidak jalan.
     */
    fun backupScript(name: String, paths: List<String>): String {
        val slug = name.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "alat" }
        val ws = "/sdcard/" + com.molina.studio.terminal.TerminalSessions.WORKSPACE_DIR // [WORKSPACE-NEUTRAL]
        return buildString {
            append("echo '== [0/4] Cadangkan konfigurasi =='\n")
            append("D=$ws/.molina-backup/$slug-`date +%Y%m%d-%H%M%S`\n")
            append("mkdir -p \"\$D\" || { echo 'GAGAL: folder cadangan tidak bisa dibuat, penghapusan dibatalkan.'; exit 4; }\n")
            append("if [ -d $ws/.git ]; then\n")
            append("  mkdir -p $ws/.git/info\n")
            append("  grep -qxF '.molina-backup/' $ws/.git/info/exclude 2>/dev/null || echo '.molina-backup/' >> $ws/.git/info/exclude\n")
            append("fi\n")
            append("for f in ${paths.joinToString(" ")}; do\n")
            append("  if [ -e \"\$f\" ]; then\n")
            append("    cp -r \"\$f\" \"\$D\"/ || { echo \"GAGAL menyalin \$f, penghapusan dibatalkan.\"; exit 4; }\n")
            append("    echo \"Dicadangkan: \$f\"\n")
            append("  fi\n")
            append("done\n")
            append("echo \"Cadangan tersimpan di: \$D\"\n")
        }
    }

    fun script(pkgs: List<String>, paths: List<String>, autoremove: Boolean): String = buildString {
        append("echo '== [1/4] Purge paket =='\n")
        for (p in pkgs) {
            append("if dpkg -l '$p' 2>/dev/null | grep -qE '$STATE'; then\n")
            append("  apt purge -y $APT '$p'\n")
            append("else\n  echo 'Paket $p tidak terpasang, dilewati'\nfi\n")
        }
        if (autoremove) {
            append("echo '== [2/4] Autoremove dependensi yatim =='\n")
            append("apt autoremove --purge -y $APT\n")
            append("apt clean\n")
        }
        append("echo '== [3/4] Hapus konfigurasi, cache, dan sisa berkas =='\n")
        if (paths.isNotEmpty()) append("rm -rf ${paths.joinToString(" ")}\n")
        append("echo '== [4/4] Verifikasi =='\n")
        append("rm -f /tmp/.molina_left\n")
        for (p in pkgs) {
            append("if dpkg -l '$p' 2>/dev/null | grep -qE '$STATE'; then echo 'SISA paket: $p'; touch /tmp/.molina_left; fi\n")
        }
        for (f in paths) {
            append("if ls -d $f >/dev/null 2>&1; then echo 'SISA berkas: $f'; touch /tmp/.molina_left; fi\n")
        }
        append("if [ -e /tmp/.molina_left ]; then rm -f /tmp/.molina_left; echo 'Penghapusan BELUM tuntas, lihat baris SISA di atas.'; exit 3; fi\n")
        append("echo 'Tuntas: tidak ada sisa paket maupun berkas.'\n")
    }
}

/**
 * Alat buatan pengguna (SharedPreferences, JSON) dan hasil cek versi terakhir tiap alat. Skrip alat
 * khusus untuk memasang/memeriksa/menghapus satu alat; skrip umum ada di Script Execution.
 */
object ToolStore {
    const val MAX_TOOLS = 30
    const val MAX_NAME = 40
    const val MAX_NOTE = 200
    const val MAX_SCRIPT = 20_000
    private const val PREFS = "molina_tools"

    private var prefs: SharedPreferences? = null
    private val _custom = MutableStateFlow<List<ToolItem>>(emptyList())
    private val _checks = MutableStateFlow<Map<String, ToolCheck>>(emptyMap())
    val custom: StateFlow<List<ToolItem>> = _custom.asStateFlow()
    val checks: StateFlow<Map<String, ToolCheck>> = _checks.asStateFlow()

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _custom.value = decodeTools(p.getString("custom", null))
        _checks.value = decodeChecks(p.getString("checks", null))
        if ("b-git" in _checks.value) setCheck("b-git", "") // [GIT-DIHAPUS] buang hasil cek versi lama
    }

    @Synchronized
    fun save(id: String?, name: String, note: String, install: String, version: String, remove: String): ToolResult {
        val n = name.trim()
        if (n.isEmpty()) return ToolResult(false, "Nama alat wajib diisi")
        if (n.length > MAX_NAME) return ToolResult(false, "Nama maksimal $MAX_NAME karakter")
        val i = norm(install)
        val v = norm(version)
        val r = norm(remove)
        if (i.isEmpty() && v.isEmpty() && r.isEmpty()) return ToolResult(false, "Isi minimal satu skrip (pasang, versi, atau hapus)")
        if (listOf(i, v, r).any { it.length > MAX_SCRIPT }) return ToolResult(false, "Skrip terlalu panjang (maks ${MAX_SCRIPT / 1000} rb karakter)")
        val nt = note.trim().take(MAX_NOTE)
        val list = _custom.value
        if (id == null) {
            if (list.size >= MAX_TOOLS) return ToolResult(false, "Daftar alat penuh ($MAX_TOOLS)")
            val item = ToolItem(newId(list), n, nt, i, v, r)
            persist(list + item)
            return ToolResult(true, "\"$n\" ditambahkan", item.id)
        }
        if (list.none { it.id == id }) return ToolResult(false, "Alat tidak ditemukan (mungkin sudah dibuang)")
        persist(list.map { if (it.id == id) it.copy(name = n, note = nt, install = i, version = v, remove = r) else it })
        return ToolResult(true, "\"$n\" disimpan", id)
    }

    @Synchronized
    fun delete(id: String) {
        persist(_custom.value.filterNot { it.id == id })
        setCheck(id, "")
    }

    /** Simpan hasil cek versi; [text] kosong menghapus hasil lama. */
    @Synchronized
    fun setCheck(id: String, text: String, auto: Boolean = false) {
        val m = _checks.value.toMutableMap()
        if (text.isBlank()) m.remove(id) else m[id] = ToolCheck(text.trim().take(120), System.currentTimeMillis(), auto)
        _checks.value = m
        val a = JSONObject()
        for ((k, c) in m) a.put(k, JSONObject().put("t", c.text).put("at", c.at).put("a", c.auto))
        prefs?.edit()?.putString("checks", a.toString())?.apply()
    }

    private fun norm(s: String): String = s.replace("\r\n", "\n").replace('\r', '\n').trim()

    private fun newId(existing: List<ToolItem>): String {
        while (true) {
            val id = "c-" + UUID.randomUUID().toString().replace("-", "").take(8)
            if (existing.none { it.id == id }) return id
        }
    }

    private fun persist(list: List<ToolItem>) {
        _custom.value = list
        val arr = JSONArray()
        for (t in list) {
            arr.put(
                JSONObject().put("id", t.id).put("name", t.name).put("note", t.note)
                    .put("install", t.install).put("version", t.version).put("remove", t.remove)
            )
        }
        prefs?.edit()?.putString("custom", arr.toString())?.apply()
    }

    private fun decodeTools(raw: String?): List<ToolItem> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id")
                    if (id.isEmpty() || any { it.id == id }) continue
                    add(
                        ToolItem(
                            id, o.optString("name", "Alat"), o.optString("note"),
                            o.optString("install"), o.optString("version"), o.optString("remove")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun decodeChecks(raw: String?): Map<String, ToolCheck> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return try {
            val o = JSONObject(raw)
            buildMap { for (k in o.keys()) put(k, ToolCheck(o.getJSONObject(k).optString("t"), o.getJSONObject(k).optLong("at"), o.getJSONObject(k).optBoolean("a"))) }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}

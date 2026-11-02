#!/usr/bin/env python3
"""Gerbang telemetri Molina: gagalkan build bila ada dependency pelacak, host URL di luar
allowlist, izin manifest baru, atau cleartext traffic. Ubah daftar di bawah SECARA SADAR."""
import os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

ALLOWED_HOSTS = {
    "jitpack.io",  # build-time saja: NewPipeExtractor (grup com.github.TeamNewPipe), dipin SHA-256
    "github.com",  # runtime, opt-in: unduh yt-dlp rilis resmi saat pengguna menekan Pasang/Perbarui (diverifikasi SHA2-256SUMS), bukan telemetri
    "raw.githubusercontent.com",  # rootfs Debian (dipin ke commit + SHA-256)
    "schemas.android.com", "www.w3.org",  # namespace XML, bukan koneksi
    "dev.w3.org",  # komentar regex di grammar TextMate CSS (VS Code, MIT): tautan spesifikasi W3C, bukan koneksi
    "mozilla.org", "www.gnu.org",  # teks lisensi di header berkas (MPL/LGPL), bukan koneksi
    "youtube.com",  # runtime: oEmbed judul asli pengunggah (OriginalTitles); satu GET per hasil pencarian, tanpa cookie/identitas, bukan telemetri
}
ALLOWED_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.WAKE_LOCK",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",  # pemutaran latar belakang mpv (seperti upstream)
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",  # DownloadService (unduhan yt-dlp opt-in); izin normal, bukan telemetri
}
FORBIDDEN_DEPS = [
    "firebase", "crashlytics", "google-services", "play-services-ads",
    "play-services-analytics", "appcenter", "sentry", "mixpanel", "amplitude",
    "appsflyer", "flurry", "bugsnag", "datadog", "onesignal", "analytics",
]
SKIP_DIRS = {".git", "build", ".gradle", "docs", "scripts", "node_modules", ".idea"}
CODE_EXT = (".kt", ".java", ".xml", ".kts", ".gradle", ".properties", ".c", ".h",
            ".cpp", ".sh", ".yml", ".yaml", ".toml", ".json")
DEP_EXT = (".kts", ".gradle", ".properties", ".toml")

URL = re.compile(r"https?://([A-Za-z0-9._-]+)")
PERM = re.compile(r'uses-permission[^>]*android:name="([^"]+)"')
TRAILING = re.compile(r"\s//\s.*$")


def files():
    for d, dirs, names in os.walk(ROOT):
        dirs[:] = [x for x in dirs if x not in SKIP_DIRS]
        for n in names:
            if n.endswith(CODE_EXT):
                yield os.path.join(d, n)


def is_comment(line):
    t = line.strip()
    return t.startswith(("//", "*", "/*", "#", "<!--"))


def host_ok(h):
    return any(h == a or h.endswith("." + a) for a in ALLOWED_HOSTS)


problems = []
for p in files():
    rel = os.path.relpath(p, ROOT)
    try:
        lines = open(p, encoding="utf-8", errors="ignore").read().splitlines()
    except OSError:
        continue
    for i, line in enumerate(lines, 1):
        if is_comment(line):
            continue
        code = TRAILING.sub("", line)
        for h in URL.findall(code):
            if not host_ok(h):
                problems.append("%s:%d host tidak diizinkan: %s" % (rel, i, h))
        if p.endswith(DEP_EXT) or p.endswith("AndroidManifest.xml"):
            low = code.lower()
            for tok in FORBIDDEN_DEPS:
                if tok in low:
                    problems.append("%s:%d token terlarang: %s" % (rel, i, tok))
        for perm in PERM.findall(code):
            if perm not in ALLOWED_PERMISSIONS:
                problems.append("%s:%d izin tidak diizinkan: %s" % (rel, i, perm))
        if 'usesCleartextTraffic="true"' in code:
            problems.append("%s:%d cleartext traffic aktif" % (rel, i))

if problems:
    print("GERBANG TELEMETRI GAGAL:")
    for x in sorted(set(problems)):
        print("  - " + x)
    sys.exit(1)
print("Gerbang telemetri lulus: tidak ada pelacak, host asing, atau izin baru.")

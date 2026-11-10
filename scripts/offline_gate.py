#!/usr/bin/env python3
"""Gerbang offline Molina: kode yang memegang data pengguna (editor Code, Penjelajah, Preview,
Exec) TIDAK BOLEH menyentuh jaringan. mpv, Terminal, dan engine NewPipe sengaja di luar cakupan.
Gagal bila: API jaringan di kode, dependensi jaringan/pelacak, izin jaringan di manifest modul,
dependensi proyek ke modul berjaringan, WebView tidak terkunci, atau Penjelajah di :app
memakai kelas berjaringan."""
import glob, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Modul penuh yang terkunci.
LOCKED = ["feature/code", "feature/preview", "core/exec"]
FORBIDDEN_PROJECTS = [":core:common", ":core:storage", ":feature:mpv", ":engine:newpipe",
                      ":terminal-emulator", ":terminal-view", ":app", ":vault"]

# Penjelajah (Berkas) hidup di :app (paket cinema), bercampur dengan kode berjaringan
# (IPTV, pencarian, thumbnail web). Hanya berkas Penjelajah yang dikunci per berkas.
CINEMA = "app/src/main/java/com/molina/studio/cinema/"
EXPLORER_GLOBS = [CINEMA + g for g in (
    "Explorer*.kt", "W7*.kt", "Win7*.kt", "FileOps.kt", "TvFileOps.kt", "TvBrowser.kt",
    "Archive*.kt", "DebianArchive*.kt")]
APP_NET_REFS = r"\b(ShieldProxy|IptvFetch|SearchThumbs|IptvSources|UpdateChecker|OriginalTitles|DownloadService)\b"

# Zona Jaringan (Paket, Unduhan) boleh berjaringan, tetapi tidak boleh mengimpor kelas UI/data Code atau
# Penjelajah. Yang boleh: runner proses (com.molina.code.run, com.molina.code.preview) dan widget/pemilih UI
# (W7*, Win7*) karena pemilih folder dipilih sendiri oleh pengguna.
ZONE_DIRS = ["app/src/main/java/com/molina/studio/packages", "app/src/main/java/com/molina/studio/download"]
ZONE_FORBIDDEN_IMPORT = (
    r"^\s*import\s+(com\.molina\.code\.(ui|history|guard|lsp|format|check|nav)\b"
    r"|com\.molina\.studio\.cinema\.(Explorer\w*|FileOps|TvFileOps|TvBrowser|Archive\w*|DebianArchive\w*)\b)"
)

CODE_PATTERNS = [
    r"^\s*import\s+(java\.net|javax\.net|android\.net|okhttp3|okio|retrofit2|io\.ktor)\b",
    r"\b(HttpURLConnection|HttpsURLConnection|DownloadManager|ServerSocket|DatagramSocket)\b",
    r"\bSocket\s*\(", r"\bURL\s*\(", r"\.openConnection\s*\(", r"\.openStream\s*\(",
    r"\bConnectivityManager\b", r"\b(Firebase\w*|Crashlytics)\b",
]
# WebView: pengaturan ini wajib tertulis tetap (bukan opsi pengguna) dan yang berbahaya dilarang.
WEBVIEW_REQUIRED = [
    r"settings\.javaScriptEnabled\s*=\s*false", r"settings\.allowFileAccess\s*=\s*false",
    r"settings\.allowContentAccess\s*=\s*false", r"settings\.domStorageEnabled\s*=\s*false",
    r"settings\.blockNetworkLoads\s*=\s*true",
]
WEBVIEW_FORBIDDEN = [
    r"javaScriptEnabled\s*=\s*true", r"blockNetworkLoads\s*=\s*false", r"\.loadUrl\s*\(",
    r"addJavascriptInterface", r"[aA]llowUniversalAccessFromFileURLs", r"[aA]llowFileAccessFromFileURLs",
    r"mixedContentMode", r"setDownloadListener", r"loadDataWithBaseURL\(\s*(?!null\b)",
]
BAD_DEPS = ["okhttp", "retrofit", "ktor", "volley", "coil", "glide", "firebase", "crashlytics",
            "play-services", "sentry", "grpc", "apollo", "fuel", "newpipe"]
BAD_PERMS = ["INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "CHANGE_NETWORK_STATE"]

errors = []

def fail(msg):
    errors.append(msg)

def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"(?m)(?<![:\"'])//.*$", "", text)

def line_of(src, pos):
    return src.count("\n", 0, pos) + 1

def scan_kotlin(rel, explorer=False):
    src = strip_comments(open(os.path.join(ROOT, rel), encoding="utf-8", errors="ignore").read())
    for pat in CODE_PATTERNS + ([APP_NET_REFS] if explorer else []):
        for m in re.finditer(pat, src, flags=re.M):
            fail(f"{rel}:{line_of(src, m.start())}: API/kelas jaringan terlarang -> {m.group(0).strip()}")
    if re.search(r"\bWebView\s*\(", src):
        for pat in WEBVIEW_REQUIRED:
            if not re.search(pat, src):
                fail(f"{rel}: WebView wajib memuat '{pat}'")
        for pat in WEBVIEW_FORBIDDEN:
            for m in re.finditer(pat, src):
                fail(f"{rel}:{line_of(src, m.start())}: WebView tidak boleh -> {m.group(0).strip()}")

for mod in LOCKED:
    base = os.path.join(ROOT, mod)
    if not os.path.isdir(base):
        fail(f"{mod}: modul terkunci tidak ditemukan (perbarui LOCKED secara sadar)")
        continue
    for dp, _, files in os.walk(base):
        if "/build" in dp.replace(base, ""):
            continue
        for f in files:
            p = os.path.join(dp, f)
            rel = os.path.relpath(p, ROOT)
            if f.endswith((".kt", ".java")):
                scan_kotlin(rel)
            elif f == "AndroidManifest.xml":
                x = open(p, encoding="utf-8", errors="ignore").read()
                for perm in BAD_PERMS:
                    if perm in x:
                        fail(f"{rel}: izin jaringan {perm}")
            elif f in ("build.gradle.kts", "build.gradle"):
                g = strip_comments(open(p, encoding="utf-8", errors="ignore").read())
                for dep in BAD_DEPS:
                    if re.search(r'["\':/.-]' + re.escape(dep) + r'\b', g, flags=re.I):
                        fail(f"{rel}: dependensi jaringan/pelacak '{dep}'")
                for proj in FORBIDDEN_PROJECTS:
                    if re.search(r'project\(\s*"' + re.escape(proj) + r'"\s*\)', g):
                        fail(f"{rel}: bergantung ke modul berjaringan {proj}")

explorer = sorted({os.path.relpath(p, ROOT) for g in EXPLORER_GLOBS for p in glob.glob(os.path.join(ROOT, g))})
if not explorer:
    fail("Penjelajah: tidak ada berkas yang cocok dengan EXPLORER_GLOBS (paket dipindah? perbarui secara sadar)")
for rel in explorer:
    scan_kotlin(rel, explorer=True)

zone = sorted({os.path.relpath(p, ROOT) for d in ZONE_DIRS for p in glob.glob(os.path.join(ROOT, d, "**", "*.kt"), recursive=True)})
if not zone:
    fail("Zona Jaringan: tidak ada berkas di ZONE_DIRS (paket dipindah? perbarui secara sadar)")
for rel in zone:
    src = strip_comments(open(os.path.join(ROOT, rel), encoding="utf-8", errors="ignore").read())
    for m in re.finditer(ZONE_FORBIDDEN_IMPORT, src, flags=re.M):
        fail(f"{rel}:{line_of(src, m.start())}: zona jaringan tidak boleh mengimpor kelas data Code/Penjelajah -> {m.group(0).strip()}")

if errors:
    print("GAGAL: gerbang offline Molina (kode data pengguna tidak boleh berjaringan)")
    for e in errors:
        print(" -", e)
    sys.exit(1)
print("OK: " + ", ".join(LOCKED) + f" + {len(explorer)} berkas Penjelajah bebas jaringan; arah zona jaringan terjaga ({len(zone)} berkas)")

#!/usr/bin/env python3
"""Molina DL v3 - pengunduh video/audio (backend: yt-dlp).

Instalasi dependensi di Debian (Molina Terminal):
    apt update && apt install -y python3 ffmpeg yt-dlp
Alternatif bila paket yt-dlp di apt terlalu lama:
    apt install -y pipx && pipx install yt-dlp && pipx ensurepath

Mode interaktif (menu, antrian banyak URL, bar progres):
    python3 molina_dl.py

Mode non-interaktif (dipakai halaman Unduhan native; stdout hanya berisi JSON, satu objek per baris):
    python3 molina_dl.py --json URL [URL ...] [--mode video|audio] [--height 720] [--ext mp4|mkv]
                         [--audio-format mp3|m4a|flac] [--bitrate 128k|192k|320k] [--dir FOLDER] [--no-compat]
    python3 molina_dl.py --info --json URL      (hanya metadata + daftar resolusi)
    python3 molina_dl.py --json URL --subs id,en [--subs-mode embed|file]   (subtitle: embed = disematkan, file = .srt di samping video)
    python3 molina_dl.py --json URL --mode audio [--no-cover]               (sampul + tag judul/artis disematkan ke MP3/M4A)
    python3 molina_dl.py --json URL --limit-rate 2M                         (batasi kecepatan, mis. 500K, 2M)
    python3 molina_dl.py --json URL --per-channel                           (simpan ke subfolder per kanal: <folder>/<Nama Kanal>/)
    python3 molina_dl.py --json URL --sidecar                               (video: poster .jpg + metadata ringkas .info.json di samping video)
    python3 molina_dl.py --json --doctor        (cek yt-dlp, ffmpeg, folder, ruang kosong)
    python3 molina_dl.py --json --update-ytdlp  (perbarui atau pasang yt-dlp)
    python3 molina_dl.py --version

Kode keluar: 0 semua berhasil, 1 ada yang gagal, 2 galat penggunaan/lingkungan, 130 dibatalkan.

Kontrak event JSON (v=1), tiap baris satu objek dengan kunci "v" dan "ev":
    hello    {script, version}                      awal sesi
    warn     {code, msg}                            mis. dir_fallback, low_space, no_ffmpeg, channel_dir_failed
    queue    {total}                                jumlah URL
    item     {i, n, url}                            item i dari n mulai
    info     {i, title, id, duration, uploader, thumbnail, heights}
    progress {i, stream, pct, done, total, speed, eta}   stream = video|audio|single; pct/total bisa null
    stage    {i, stage}                             merge | extract | recode | subs | cover
    done     {i, dir, files:[{name, path, size}], existed, sidecars?:[{name, path, size}]}
                                                    dir = folder hasil (subfolder kanal bila --per-channel)
                                                    --per-channel: video yang sama (id + ekstensi) yang sudah ada di folder datar/kanal lain
                                                    tidak diunduh ulang; dilaporkan existed=true dengan berkas lamanya di files
                                                    sidecars hanya ada bila --sidecar dan poster/metadata ditemukan
    error    {i, code, msg}                         i = 0 untuk galat global
    summary  {ok, failed, cancelled}                akhir sesi
    doctor   {ytdlp, ytdlp_method, ffmpeg, ffprobe, mutagen, python, video_dir, audio_dir, root_bound, free_mb, issues:[kode]}
    update   {status, method, before, after, msg}   status = start | ok | latest | failed

Folder simpan (mengikuti struktur penyimpanan Molina):
    Video -> /sdcard/Molina/Media/Video
    Audio -> /sdcard/Molina/Media/Audio
Environment (opsional):
    MOLINA_ROOT    akar folder aplikasi (bawaan /sdcard/Molina)
    MOLINA_DL_DIR  satu folder untuk video DAN audio (menimpa semuanya; --dir lebih utama)
    MOLINA_DL_PER_CHANNEL=1  sama dengan --per-channel (juga berlaku di menu interaktif)
    MOLINA_DL_ASCII=1  bar ASCII tanpa karakter blok;   NO_COLOR=1  tanpa warna
Bila /sdcard/Molina tidak terlihat di Debian (belum di-bind), skrip memakai folder cadangan
<folder kerja>/unduhan/{Video,Audio} (env MOLINA_WORKSPACE), atau ~/unduhan/{Video,Audio}, dan memberi peringatan.
"""
import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
import unicodedata

SCRIPT_NAME = "molina_dl"
SCRIPT_VERSION = 9
PROTOCOL = 1

URL_RE = re.compile(r"^https?://[^\s/$.?#][^\s]*$", re.IGNORECASE)
VIDEO_EXTS = ("mp4", "mkv")
AUDIO_FORMATS = ("mp3", "m4a", "flac")
BITRATES = ("128k", "192k", "320k")
FALLBACK_HEIGHTS = (1080, 720, 480, 360)
DEFAULT_ROOT = "/sdcard/Molina"
LEGACY_BASE = os.environ.get("MOLINA_WORKSPACE", "/sdcard/Molina/Workspace").rstrip("/") + "/unduhan"
MIN_FREE_MB = 500
SNAPSHOT_DEPTH = 2          # folder simpan + satu tingkat subfolder (cukup untuk folder per kanal)
NO_CHANNEL = "Tanpa Kanal"
FOLDER_NAME_MAX_BYTES = 100
BAD_NAME_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f\x7f]')
RESERVED_NAMES = frozenset(["con", "prn", "aux", "nul"] + [f"com{n}" for n in range(1, 10)] + [f"lpt{n}" for n in range(1, 10)])
UPDATE_TIMEOUT = 600
SUB_MODES = ("embed", "file")
MAX_SUB_LANGS = 5
SUB_LANG_RE = re.compile(r"^[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8}){0,2}$")
SUB_EXTS = (".srt", ".vtt", ".ass")
RATE_RE = re.compile(r"^[1-9][0-9]*(?:\.[0-9]+)?[KkMm]?$")
THUMB_EXTS = (".jpg", ".jpeg", ".png", ".webp")
INFO_SUFFIX = ".info.json"
INFO_DESC_MAX = 2000
INFO_TAGS_MAX = 20
INFO_KEEP = ("id", "title", "fulltitle", "uploader", "channel", "duration", "upload_date", "release_year",
             "description", "webpage_url", "extractor_key", "view_count", "like_count", "categories", "tags",
             "width", "height", "fps", "ext", "thumbnail")

PROGRESS_TEMPLATE = ("download:MDL|%(progress.status)s|%(progress.downloaded_bytes)s|"
                     "%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|"
                     "%(progress.speed)s|%(progress.eta)s|%(info.vcodec)s|%(info.acodec)s")
PP_TEMPLATE = "postprocess:MDLPP|%(progress.status)s|%(progress.postprocessor)s"
FILE_PRINT = "after_move:MDLFILE|%(filepath)s"
PP_STAGES = {"Merger": "merge", "ExtractAudio": "extract", "EmbedSubtitle": "subs", "EmbedThumbnail": "cover"}


# ---------------------------------------------------------------- format angka

def human_size(n):
    if n is None:
        return "?"
    n = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024


def human_speed(bps):
    return "--" if not bps else human_size(bps).replace(" ", "") + "/s"


def human_eta(sec):
    if sec is None:
        return "--:--"
    sec = int(sec)
    h, rem = divmod(sec, 3600)
    m, s = divmod(rem, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def to_num(text):
    """Angka dari kolom template yt-dlp; 'NA'/kosong -> None."""
    try:
        v = float(text)
    except (TypeError, ValueError):
        return None
    return v if v == v else None  # tolak NaN


# ---------------------------------------------------------------- pelapor

class JsonReporter:
    """stdout = NDJSON murni. Tidak ada teks manusia di stdout."""
    interactive = False

    def __init__(self):
        self._last = {}

    def emit(self, ev, **fields):
        obj = {"v": PROTOCOL, "ev": ev}
        obj.update(fields)
        sys.stdout.write(json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n")
        sys.stdout.flush()

    def hello(self):
        self.emit("hello", script=SCRIPT_NAME, version=SCRIPT_VERSION)

    def warn(self, code, msg):
        self.emit("warn", code=code, msg=msg)

    def queue(self, total):
        self.emit("queue", total=total)

    def item(self, i, n, url):
        self.emit("item", i=i, n=n, url=url)

    def info(self, i, info):
        self.emit("info", i=i, **info_fields(info))

    def progress(self, i, stream, pct, done, total, speed, eta, final=False):
        now = time.monotonic()
        key = (i, stream)
        last = self._last.get(key)
        if not final and last and now - last < 0.5:
            return
        self._last[key] = now
        self.emit("progress", i=i, stream=stream,
                  pct=None if pct is None else round(pct, 1),
                  done=None if done is None else int(done),
                  total=None if total is None else int(total),
                  speed=None if speed is None else int(speed),
                  eta=None if eta is None else int(eta))

    def stage(self, i, stage):
        self.emit("stage", i=i, stage=stage)

    def done(self, i, outdir, files, existed, sidecars=None):
        extra = {"sidecars": [{"name": n, "path": p, "size": s} for n, p, s in sidecars]} if sidecars else {}
        self.emit("done", i=i, dir=outdir, existed=existed,
                  files=[{"name": n, "path": p, "size": s} for n, p, s in files], **extra)

    def error(self, i, code, msg):
        self.emit("error", i=i, code=code, msg=msg)

    def summary(self, ok, failed, cancelled):
        self.emit("summary", ok=ok, failed=failed, cancelled=cancelled)

    def doctor(self, data):
        self.emit("doctor", **data)

    def update(self, status, **fields):
        self.emit("update", status=status, **fields)

    def note(self, text):  # teks bebas tidak dikirim di mode JSON
        pass

    def finish_line(self):
        pass


class TextReporter:
    """UI terminal: bar emas gaya IDM, satu baris per aliran, mundur ke cetak per 10% bila bukan TTY."""
    interactive = True

    def __init__(self):
        self.tty = sys.stdout.isatty()
        self.color = self.tty and not os.environ.get("NO_COLOR")
        enc = (sys.stdout.encoding or "").lower()
        self.ascii = bool(os.environ.get("MOLINA_DL_ASCII")) or not enc.startswith("utf")
        self._open = False      # ada baris bar yang belum diakhiri newline
        self._stream = None
        self._last_draw = 0.0
        self._decile = {}

    # -- util tampilan
    def _c(self, code, text):
        return f"\x1b[{code}m{text}\x1b[0m" if self.color else text

    def _cols(self):
        return shutil.get_terminal_size((50, 24)).columns

    def finish_line(self):
        if self._open:
            sys.stdout.write("\n")
            sys.stdout.flush()
            self._open = False

    def note(self, text):
        self.finish_line()
        print(text)
        sys.stdout.flush()

    # -- API pelapor
    def hello(self):
        pass

    def warn(self, code, msg):
        self.note(f"Peringatan: {msg}")

    def queue(self, total):
        if total > 1:
            self.note(f"\nAntrian: {total} URL")

    def item(self, i, n, url):
        self._stream = None
        self.note(f"\n[{i}/{n}] {url}" if n > 1 else "\nMemeriksa URL...")

    def info(self, i, info):
        self.note(f"Judul: {info.get('title') or '(tanpa judul)'}")

    def stage(self, i, stage):
        self.note({"merge": "Menggabungkan video + audio...", "extract": "Mengonversi audio...",
                                       "recode": "Menyesuaikan ke H.264/AAC (kompatibel WhatsApp)...",
                                       "subs": "Menyematkan subtitle...", "cover": "Menyematkan sampul..."}.get(stage, stage))

    def progress(self, i, stream, pct, done, total, speed, eta, final=False):
        label = {"video": "Video", "audio": "Audio"}.get(stream, "Data ")
        if self._stream != stream:
            self.finish_line()
            self._stream = stream
            self._decile.pop(stream, None)
        if not self.tty:
            tens = int((pct or 0) // 10)
            if final or tens > self._decile.get(stream, -1):
                self._decile[stream] = tens
                p = "100" if final else f"{int(pct or 0)}"
                print(f"{label} {p}% {human_speed(speed)} ETA {human_eta(eta)}")
                sys.stdout.flush()
            return
        now = time.monotonic()
        if not final and now - self._last_draw < 0.1:
            return
        self._last_draw = now
        cols = self._cols()
        wide = cols >= 70
        width = max(8, min(30, cols - 30 - (16 if wide else 0)))
        fill_n = int(width * min(max(pct or 0, 0), 100) / 100)
        full, empty = ("#", "-") if self.ascii else ("\u2588", "\u2591")
        bar = self._c("38;5;178", full * fill_n) + self._c("38;5;240", empty * (width - fill_n))
        pct_txt = f"{pct:5.1f}%" if pct is not None else "  --.-%"
        line = f"{label} {bar} {self._c('38;5;178', pct_txt)} {human_speed(speed):>9} {human_eta(eta):>5}"
        if wide:
            line += f" {human_size(done)}/{human_size(total) if total else '?'}"
        sys.stdout.write("\r" + line + "\x1b[K")
        sys.stdout.flush()
        self._open = True
        if final:
            self.finish_line()

    def done(self, i, outdir, files, existed, sidecars=None):
        self.finish_line()
        print(f"Selesai! Folder: {outdir}")
        if existed and not files:
            print("Berkas ini sudah ada, tidak diunduh ulang.")
        for name, _path, size in files:
            print(f"  - {name} ({human_size(size)})")
        for name, _path, size in sidecars or ():
            print(f"  + {name} ({human_size(size)})")
        sys.stdout.flush()

    def error(self, i, code, msg):
        self.finish_line()
        print(f"Galat: {msg}")
        sys.stdout.flush()

    def doctor(self, d):
        self.finish_line()

        def mark(ok):
            return self._c("32", "OK") if ok else self._c("31", "--")

        origin = f" [{d['ytdlp_method']}]" if d.get("ytdlp_method") else ""
        free = d.get("free_mb")
        print("\n=== Lingkungan Molina DL ===")
        print(f"{mark(d['ytdlp'])} yt-dlp   {d['ytdlp'] or 'tidak ada -> apt install -y yt-dlp'}{origin}")
        print(f"{mark(d['ffmpeg'])} ffmpeg   {d['ffmpeg'] or 'tidak ada -> apt install -y ffmpeg'}")
        print(f"{mark(d['ffprobe'])} ffprobe  {'ada' if d['ffprobe'] else 'tidak ada (cek kompatibilitas MP4 dilewati)'}")
        print(f"{mark(d['mutagen'])} mutagen  "
              f"{'ada' if d['mutagen'] else 'tidak ada -> sampul M4A/FLAC butuh: apt install -y python3-mutagen'}")
        print(f"{mark(d['root_bound'])} /sdcard/Molina  {'terhubung' if d['root_bound'] else 'tidak terlihat dari Debian'}")
        print(f"{mark(free is None or free >= MIN_FREE_MB)} ruang kosong  {'?' if free is None else str(free) + ' MB'}")
        print(f"Video -> {d['video_dir']}")
        print(f"Audio -> {d['audio_dir']}")
        sys.stdout.flush()

    def update(self, status, **f):
        self.finish_line()
        if status == "start":
            print(f"Memperbarui yt-dlp lewat {f.get('method')}... (butuh internet)")
        else:
            print(f.get("msg") or status)
        sys.stdout.flush()

    def summary(self, ok, failed, cancelled):
        if cancelled:
            self.note("Dibatalkan oleh pengguna. Berkas sebagian (.part) dilanjutkan bila URL yang sama diunduh lagi.")
        elif ok + failed > 1:
            self.note(f"\nRingkasan: {ok} berhasil, {failed} gagal.")


def info_fields(info):
    thumb = info.get("thumbnail")
    return {
        "title": info.get("title"),
        "id": info.get("id"),
        "duration": info.get("duration"),
        "uploader": info.get("uploader") or info.get("channel"),
        "thumbnail": thumb if isinstance(thumb, str) else None,
        "heights": available_heights(info),
    }


# ---------------------------------------------------------------- util input

def ask(prompt):
    """input() yang mengembalikan None saat Ctrl+D (EOF) atau Ctrl+C."""
    try:
        return input(prompt).strip()
    except (EOFError, KeyboardInterrupt):
        print()
        return None


def choose(title, options, default=1):
    """Daftar bernomor; kembalikan indeks (0-based) atau None bila dibatalkan."""
    print(f"\n{title}")
    for i, label in enumerate(options, 1):
        print(f"  {i}. {label}")
    while True:
        raw = ask(f"Pilih [1-{len(options)}] (Enter = {default}, 0 = batal): ")
        if raw is None or raw == "0":
            return None
        if raw == "":
            return default - 1
        if raw.isdigit() and 1 <= int(raw) <= len(options):
            return int(raw) - 1
        print("Pilihan tidak valid.")


# ---------------------------------------------------------------- penjaga koneksi [DL-NETGUARD]
# yt-dlp dijalankan lewat pembungkus yang menolak connect() ke alamat lokal PADA SAAT KONEKSI (setelah DNS),
# jadi redirect ke 127.0.0.1/IP privat dan DNS rebinding ikut tertangkap. Aturan sama dengan UrlPolicy.kt.
# Batas: hanya koneksi dari proses Python yt-dlp; ffmpeg/curl yang dipanggil yt-dlp tidak terjaga.
GUARD_SRC = r'''
import ipaddress, os, socket, sys

def _v4(n, lan):
    b1, b2, b3 = (n >> 24) & 255, (n >> 16) & 255, (n >> 8) & 255
    if b1 == 0 or b1 == 127 or (b1 == 169 and b2 == 254) or b1 >= 224:
        return "loopback"
    private = (b1 == 10 or (b1 == 172 and 16 <= b2 <= 31) or (b1 == 192 and b2 == 168)
               or (b1 == 100 and 64 <= b2 <= 127) or (b1 == 192 and b2 == 0 and b3 == 0)
               or (b1 == 198 and b2 in (18, 19)))
    return "private" if (private and not lan) else None

def classify(ip, lan=False):
    # None = boleh; "loopback" atau "private" = ditolak
    try:
        a = ipaddress.ip_address(str(ip).split("%")[0])
    except ValueError:
        return "loopback"
    n = int(a)
    if a.version == 4:
        return _v4(n, lan)
    if n == 0 or n == 1:
        return "loopback"
    top = n >> 32
    if top == 0 or top == 0xffff or top == (0x0064ff9b << 64):
        return _v4(n & 0xffffffff, lan)
    first = n >> 112
    if first == 0x2002:
        return _v4((n >> 80) & 0xffffffff, lan)
    if 0xfe80 <= first <= 0xfeff or first >= 0xff00:
        return "loopback"
    if 0xfc00 <= first <= 0xfdff:
        return None if lan else "private"
    if (first & 0xe000) == 0x2000:
        return None
    return "loopback"

def install():
    lan = os.environ.get("MOLINA_NETGUARD_ALLOW_LAN") == "1"
    loop = os.environ.get("MOLINA_NETGUARD_ALLOW_LOOPBACK") == "1"
    orig_connect = socket.socket.connect
    orig_connect_ex = socket.socket.connect_ex

    def verdict(sock, address):
        if sock.family not in (socket.AF_INET, socket.AF_INET6) or not isinstance(address, tuple) or not address:
            return None, None
        host = address[0]
        try:
            ipaddress.ip_address(str(host).split("%")[0])
            ips = [host]
        except ValueError:
            try:
                ips = [ai[4][0] for ai in socket.getaddrinfo(host, address[1] if len(address) > 1 else None, sock.family)]
            except OSError:
                return None, None
        for ip in ips:
            why = classify(ip, lan)
            if why == "private" or (why == "loopback" and not loop):
                return ip, why
        return None, None

    def connect(self, address):
        ip, why = verdict(self, address)
        if why:
            raise OSError("[MOLINA-NETGUARD] koneksi ke alamat %s ditolak (%s)" % (ip, why))
        return orig_connect(self, address)

    def connect_ex(self, address):
        ip, why = verdict(self, address)
        if why:
            return 1
        return orig_connect_ex(self, address)

    socket.socket.connect = connect
    socket.socket.connect_ex = connect_ex

def run(target, argv):
    import runpy
    install()
    if target == "-m:yt_dlp":
        sys.argv = ["yt-dlp"] + list(argv)
        runpy.run_module("yt_dlp", run_name="__main__", alter_sys=True)
    else:
        sys.argv = [target] + list(argv)
        runpy.run_path(target, run_name="__main__")
'''

_GUARD_PY = {}


def guard_python(ytdlp):
    # Interpreter Python yang menjalankan yt-dlp ini (skrip pip/pipx/apt atau `python -m yt_dlp`); None bila tak bisa ditentukan.
    key = tuple(ytdlp or ())
    if key in _GUARD_PY:
        return _GUARD_PY[key]
    py = None
    try:
        if len(key) >= 3 and key[1] == "-m":
            py = key[0]
        elif key:
            with open(key[0], "rb") as f:
                head = f.readline(300)
            if head.startswith(b"#!"):
                parts = head[2:].decode("utf-8", "replace").split()
                if parts:
                    py = parts[0]
                    if os.path.basename(py) == "env" and len(parts) > 1:
                        py = shutil.which(parts[1])
        if py and not os.access(py, os.X_OK):
            py = None
    except OSError:
        py = None
    _GUARD_PY[key] = py
    return py


def guarded(ytdlp):
    # Perintah yt-dlp yang sama, tetapi lewat penjaga koneksi; yt-dlp asli bila pembungkus tidak bisa dipasang.
    py = guard_python(ytdlp)
    if not py:
        return ytdlp
    module_form = len(ytdlp) >= 3 and ytdlp[1] == "-m"
    target = "-m:yt_dlp" if module_form else ytdlp[0]
    rest = list(ytdlp[3:] if module_form else ytdlp[1:])
    return [py, "-c", GUARD_SRC + "\nrun(sys.argv[1], sys.argv[2:])\n", target] + rest


# ---------------------------------------------------------------- lingkungan

def find_ytdlp():
    """Perintah dasar yt-dlp: binary di PATH, atau modul python bila terpasang lewat pip."""
    exe = shutil.which("yt-dlp")
    if exe:
        return [exe]
    try:
        import yt_dlp  # noqa: F401
        return [sys.executable, "-m", "yt_dlp"]
    except ImportError:
        return None


def has_mutagen():
    try:
        import mutagen  # noqa: F401
        return True
    except ImportError:
        return False


def cover_supported(afmt):
    """MP3 disematkan lewat ffmpeg. M4A/FLAC butuh mutagen (atau AtomicParsley untuk M4A)."""
    if afmt == "mp3":
        return True
    if afmt == "m4a" and shutil.which("AtomicParsley"):
        return True
    return has_mutagen()


def parse_subs(raw):
    """'id,en' -> (['id', 'en'], None); tidak valid -> ([], pesan)."""
    langs = []
    for part in (raw or "").split(","):
        part = part.strip()
        if part and part not in langs:
            langs.append(part)
    if not langs:
        return [], None
    if len(langs) > MAX_SUB_LANGS:
        return [], f"Maksimal {MAX_SUB_LANGS} bahasa subtitle."
    bad = [x for x in langs if not SUB_LANG_RE.match(x)]
    if bad:
        return [], "Kode bahasa subtitle tidak valid: " + ", ".join(bad[:3])
    return langs, None


def ensure_dir(path):
    try:
        os.makedirs(path, exist_ok=True)
        return os.access(path, os.W_OK)
    except OSError:
        return False


def resolve_dirs(override=None):
    """Kembalikan (folder_video, folder_audio, peringatan atau None).

    Akar /sdcard/Molina dibuat oleh aplikasi, jadi TIDAK dibuat di sini: bila belum ada berarti
    belum ter-bind ke Debian, dan membuatnya hanya menghasilkan folder tersembunyi di dalam rootfs.
    """
    custom = override or os.environ.get("MOLINA_DL_DIR")
    if custom:
        base = os.path.expanduser(custom)
        if ensure_dir(base):
            return base, base, None

    root = os.environ.get("MOLINA_ROOT", DEFAULT_ROOT)
    if os.path.isdir(root):
        video = os.path.join(root, "Media", "Video")
        audio = os.path.join(root, "Media", "Audio")
        if ensure_dir(video) and ensure_dir(audio):
            return video, audio, None

    if os.path.isdir(os.path.dirname(LEGACY_BASE)):
        base, where = LEGACY_BASE, "folder cadangan %s/unduhan" % os.path.basename(os.path.dirname(LEGACY_BASE))
    else:
        base, where = os.path.expanduser("~/unduhan"), "folder cadangan ~/unduhan (tidak terlihat di Android)"
    video, audio = os.path.join(base, "Video"), os.path.join(base, "Audio")
    ensure_dir(video)
    ensure_dir(audio)
    warn = (f"{root} tidak terlihat dari Debian, jadi unduhan masuk ke {where}. "
            "Aktifkan 'Akses penyimpanan penuh' di Setelan Terminal lalu buka sesi baru.")
    return video, audio, warn


def free_space_low(path):
    try:
        return shutil.disk_usage(path).free < MIN_FREE_MB * 1024 * 1024
    except OSError:
        return False


# ---------------------------------------------------------------- sertifikat SSL

CA_BUNDLE = "/etc/ssl/certs/ca-certificates.crt"
CA_MIN_BYTES = 10000
SSL_MARKERS = ("certificate_verify_failed", "certificate verify failed", "local issuer certificate",
               "self-signed certificate", "self signed certificate")
_ca_repair_tried = False


def is_ssl_error(msg):
    low = (msg or "").lower()
    return any(k in low for k in SSL_MARKERS)


def ca_bundle_ok():
    try:
        return os.path.getsize(CA_BUNDLE) >= CA_MIN_BYTES
    except OSError:
        return False


def apply_ca_env():
    """Arahkan Python/yt-dlp ke bundel CA sistem bila ada (aman dipanggil berulang)."""
    if ca_bundle_ok():
        os.environ.setdefault("SSL_CERT_FILE", CA_BUNDLE)
        os.environ.setdefault("REQUESTS_CA_BUNDLE", CA_BUNDLE)


def repair_ca():
    """Pasang ulang ca-certificates + segarkan bundel, sekali per proses. True bila bundel CA sehat sesudahnya."""
    global _ca_repair_tried
    if _ca_repair_tried:
        return False
    _ca_repair_tried = True
    env = dict(os.environ, DEBIAN_FRONTEND="noninteractive")
    steps = []
    if shutil.which("apt-get"):
        steps.append((["apt-get", "update"], 180))
        steps.append((["apt-get", "install", "-y", "--no-install-recommends", "--reinstall", "ca-certificates"], 300))
    if shutil.which("update-ca-certificates"):
        steps.append((["update-ca-certificates", "--fresh"], 120))
    for cmd, limit in steps:
        try:
            subprocess.run(cmd, capture_output=True, text=True, errors="replace", timeout=limit,
                           stdin=subprocess.DEVNULL, env=env)
        except (OSError, subprocess.TimeoutExpired):
            pass
    apply_ca_env()
    return ca_bundle_ok()


# ---------------------------------------------------------------- yt-dlp

def fetch_info(ytdlp, url, rep=None):
    """Metadata video. Mengembalikan (info, None) atau (None, (kode, pesan)). Sekaligus memvalidasi URL.
    Galat sertifikat SSL: bundel CA diperbaiki otomatis sekali lalu dicoba ulang."""
    for attempt in (0, 1):
        try:
            res = subprocess.run(
                guarded(ytdlp) + ["--ignore-config", "-J", "--no-playlist", "--no-warnings", url],  # [DL-NO-CONFIG]
                capture_output=True, text=True, timeout=90, stdin=subprocess.DEVNULL,
            )
        except subprocess.TimeoutExpired:
            return None, ("timeout", "Waktu habis saat mengambil info. Periksa koneksi internet.")
        if res.returncode != 0 and attempt == 0 and is_ssl_error(res.stderr) and repair_ca():
            if rep is not None:
                rep.warn("ssl_repair", "Sertifikat keamanan diperbaiki otomatis. Mencoba lagi...")
            continue
        break
    if res.returncode != 0:
        lines = [ln for ln in res.stderr.strip().splitlines() if ln.strip()]
        reason = lines[-1] if lines else "URL tidak didukung atau koneksi bermasalah."
        code = "ssl_cert" if is_ssl_error(res.stderr) else ("blocked_local" if "molina-netguard" in res.stderr.lower() else "info_failed")
        return None, (code, reason.replace("ERROR: ", ""))
    try:
        info = json.loads(res.stdout)
    except json.JSONDecodeError:
        return None, ("info_failed", "Respons yt-dlp tidak bisa dibaca.")
    if info.get("_type") in ("playlist", "multi_video"):
        return None, ("playlist", "URL ini berupa playlist/kanal. Gunakan URL satu video.")
    return info, None


def available_heights(info):
    heights = {
        f["height"] for f in info.get("formats", [])
        if f.get("height") and f.get("vcodec") not in (None, "none")
    }
    return sorted(heights, reverse=True)[:8] or list(FALLBACK_HEIGHTS)


def video_format(height, ext, has_ffmpeg, compat=True):
    """Selector format yt-dlp. Mode compat: MP4 diprioritaskan H.264+AAC (WhatsApp/HP lain).
    Tanpa ffmpeg hanya format gabungan (satu berkas) yang aman."""
    cap = f"[height<={height}]" if height else ""
    if not has_ffmpeg:
        return f"b{cap}/b"
    if ext == "mp4":
        if compat:
            return (f"bv*{cap}[vcodec^=avc1]+ba[acodec^=mp4a]/"
                    f"bv*{cap}[vcodec^=avc1]+ba[ext=m4a]/"
                    f"b{cap}[vcodec^=avc1][acodec^=mp4a]/"
                    f"bv*{cap}+ba/b{cap}")
        return f"bv*{cap}[ext=mp4]+ba[ext=m4a]/bv*{cap}+ba/b{cap}"
    return f"bv*{cap}+ba/b{cap}"


def build_cmd(ytdlp, url, outdir, spec, has_ffmpeg):
    cmd = ytdlp + [
        "--ignore-config",        # [DL-NO-CONFIG] jangan baca yt-dlp.conf (cegah --exec/-o dari konfigurasi luar)
        "--no-playlist", "--quiet", "--no-warnings", "--progress", "--newline",
        "--progress-template", PROGRESS_TEMPLATE,
        "--progress-template", PP_TEMPLATE,
        "--print", FILE_PRINT,
        "--no-mtime",             # waktu berkas = waktu unduh, bukan tanggal unggah
        "--windows-filenames",    # nama aman untuk storage bersama Android
        "--retries", "10", "--fragment-retries", "10", "--concurrent-fragments", "4",
        "-o", os.path.join(outdir, "%(title).150B [%(id)s].%(ext)s"),
    ]
    if has_ffmpeg:
        cmd.append("--embed-metadata")
    if has_ffmpeg and spec["mode"] == "video" and spec["ext"] == "mp4":
        cmd += ["--ppa", "Merger+ffmpeg_o:-movflags +faststart"]
    if spec.get("limit"):
        cmd += ["--limit-rate", spec["limit"]]
    if spec["mode"] == "video" and spec.get("sidecar"):
        cmd += ["--write-thumbnail", "--write-info-json"]
        if has_ffmpeg:
            cmd += ["--convert-thumbnails", "jpg"]
    if spec["mode"] == "video" and spec.get("subs"):
        embed = has_ffmpeg and spec.get("subs_mode", "embed") == "embed"
        cmd += ["--write-subs", "--write-auto-subs", "--sub-langs", ",".join(spec["subs"]), "--sleep-subtitles", "1"]
        if has_ffmpeg:
            cmd += ["--convert-subs", "srt"]
        if embed:
            # no-keep-subs: tanpa ini yt-dlp menyisakan berkas .srt di folder setelah subtitle disematkan.
            cmd += ["--embed-subs", "--compat-options", "no-keep-subs"]
    if spec["mode"] == "audio" and has_ffmpeg and spec.get("cover", True) and cover_supported(spec["afmt"]):
        cmd += ["--embed-thumbnail", "--convert-thumbnails", "jpg"]
    if spec["mode"] == "audio":
        cmd += ["-f", "bestaudio/best", "-x", "--audio-format", spec["afmt"]]
        if spec.get("bitrate") and spec["afmt"] != "flac":  # FLAC lossless: tanpa bitrate
            cmd += ["--audio-quality", spec["bitrate"].upper()]
    else:
        cmd += ["-f", video_format(spec.get("height"), spec["ext"], has_ffmpeg, spec.get("compat", True))]
        if has_ffmpeg:
            cmd += ["--merge-output-format", spec["ext"]]
    return cmd + [url]


def stream_kind(vcodec, acodec):
    novid = vcodec in (None, "", "NA", "none")
    noaud = acodec in (None, "", "NA", "none")
    if not novid:
        return "video"
    return "audio" if not noaud else "single"


def classify_error(msg):
    low = msg.lower()
    if "molina-netguard" in low:
        return "blocked_local"
    if "no space" in low:
        return "no_space"
    if is_ssl_error(low):
        return "ssl_cert"
    if "http error 429" in low or "too many requests" in low:
        return "rate_limited"
    if any(k in low for k in ("timed out", "temporary failure", "network", "connection", "unable to download")):
        return "network"
    if any(k in low for k in ("private", "sign in", "members-only", "not available in your country", "geo")):
        return "restricted"
    return "download_failed"


def is_subs_error(msg):
    """True bila yt-dlp gagal HANYA karena subtitle (mis. HTTP 429 dari endpoint timedtext YouTube)."""
    low = (msg or "").lower()
    return "unable to download video subtitles" in low or "unable to download subtitle" in low


def terminate(proc):
    if proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()


def run_item(ytdlp, rep, i, n, url, spec, outdir, has_ffmpeg, info=None, _retry=False, _ssl=False):
    """Unduh satu URL. Mengembalikan 'ok' | 'failed' | 'cancelled'.
    _retry=True: percobaan ulang tanpa subtitle (item/info sudah diumumkan, tidak diumumkan dua kali)."""
    if not _retry and not _ssl:
        rep.item(i, n, url)
        if info is None:
            info, err = fetch_info(ytdlp, url, rep)
            if err:
                rep.error(i, err[0], err[1])
                return "failed"
        rep.info(i, info)
    if free_space_low(outdir):
        rep.warn("low_space", f"Ruang kosong kurang dari {MIN_FREE_MB} MB, unduhan bisa gagal.")
    if spec.get("per_channel"):
        dup = find_existing(outdir, info, spec)
        if dup:  # sudah ada (folder datar/kanal lain): jangan unduh ulang dan jangan buat folder kanal kosong
            side = collect_sidecars([dup]) if spec["mode"] == "video" and spec.get("sidecar") else []
            rep.done(i, os.path.dirname(dup[1]), [dup], True, side)
            return "ok"
    item_dir = outdir
    if spec.get("per_channel"):
        item_dir = channel_dir(outdir, info)
        if not ensure_dir(item_dir):
            rep.warn("channel_dir_failed", "Folder kanal tidak bisa dibuat; unduhan disimpan di folder utama.")
            item_dir = outdir
    before = snapshot(item_dir)
    cmd = build_cmd(guarded(ytdlp), url, item_dir, spec, has_ffmpeg)
    if rep.interactive:
        rep.note("Mengunduh... (Ctrl+C untuk membatalkan)")
    try:
        proc = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, bufsize=1, errors="replace")
    except OSError as e:
        rep.error(i, "spawn_failed", f"Gagal menjalankan yt-dlp: {e}")
        return "failed"

    files, last_err, last_stream, last_pct = [], "", None, {}
    try:
        for raw in proc.stdout:
            line = raw.strip()
            if line.startswith("MDL|"):
                p = line.split("|")
                if len(p) < 9:
                    continue
                downloaded, total, est = to_num(p[2]), to_num(p[3]), to_num(p[4])
                total = total or est
                finished = p[1] == "finished"
                stream = stream_kind(p[7], p[8])
                if finished and downloaded is None:
                    downloaded = total
                pct = 100.0 if finished else (downloaded / total * 100 if downloaded is not None and total else None)
                last_stream = stream
                last_pct[stream] = pct
                rep.progress(i, stream, pct, downloaded, total, to_num(p[5]), to_num(p[6]), final=finished)
            elif line.startswith("MDLPP|"):
                p = line.split("|")
                if len(p) >= 3 and p[1] == "started" and p[2] in PP_STAGES:
                    rep.stage(i, PP_STAGES[p[2]])
            elif line.startswith("MDLFILE|"):
                files.append(line[len("MDLFILE|"):])
            elif line.startswith("ERROR:"):
                last_err = line[len("ERROR:"):].strip()
        rc = proc.wait()
    except KeyboardInterrupt:
        terminate(proc)
        raise
    finally:
        rep.finish_line()

    if rc != 0:
        msg = last_err or f"yt-dlp berhenti dengan kode {rc}. Periksa koneksi atau perbarui yt-dlp."
        if not _ssl and is_ssl_error(msg) and repair_ca():
            rep.warn("ssl_repair", "Sertifikat keamanan diperbaiki otomatis. Mencoba lagi...")
            return run_item(ytdlp, rep, i, n, url, spec, outdir, has_ffmpeg, info, _retry=_retry, _ssl=True)
        if not _retry and spec["mode"] == "video" and spec.get("subs") and is_subs_error(msg):
            # yt-dlp terbaru menganggap kegagalan subtitle sebagai error fatal (ignoreerrors tidak True).
            # Subtitle diunduh SEBELUM video, jadi video belum ada: ulangi tanpa subtitle agar unduhan tetap jadi.
            why = ("YouTube membatasi permintaan (HTTP 429)" if classify_error(msg) == "rate_limited"
                   else "subtitle tidak bisa diunduh")
            rep.warn("subs_failed", f"Subtitle ({', '.join(spec['subs'])}) dilewati: {why}. "
                                    "Video diunduh tanpa subtitle; coba lagi nanti atau perbarui yt-dlp.")
            return run_item(ytdlp, rep, i, n, url, dict(spec, subs=[]), outdir, has_ffmpeg, info, _retry=True)
        rep.error(i, classify_error(msg), msg)
        return "failed"

    result, seen = [], set()
    for path in files:
        if path in seen:
            continue
        seen.add(path)
        try:
            result.append((os.path.basename(path), path, os.path.getsize(path)))
        except OSError:
            pass
    existed = any(rel_name(p_, item_dir) in before for _n, p_, _s in result)
    if not result:  # cadangan: selisih isi folder (mis. yt-dlp lama tanpa after_move), termasuk subfolder
        for rel in sorted(snapshot(item_dir) - before):
            base = os.path.basename(rel)
            if ".part" in base or base.endswith((".ytdl", ".temp")):
                continue
            if spec.get("sidecar") and base.lower().endswith(THUMB_EXTS + (INFO_SUFFIX,)):
                continue  # poster/metadata dilaporkan terpisah (sidecars), bukan sebagai hasil unduhan
            path = os.path.join(item_dir, rel)
            try:
                result.append((base, path, os.path.getsize(path)))
            except OSError:
                pass
        existed = not result
    if spec["mode"] == "video" and spec.get("subs") and spec.get("subs_mode") == "file":
        have = {p_ for _n, p_, _s in result}
        for rel in sorted(snapshot(item_dir) - before):  # def_sidecar_subs
            path = os.path.join(item_dir, rel)
            if rel.lower().endswith(SUB_EXTS) and path not in have:
                try:
                    result.append((os.path.basename(rel), path, os.path.getsize(path)))
                except OSError:
                    pass
    if spec["mode"] == "video" and spec["ext"] == "mp4" and has_ffmpeg and spec.get("compat", True):
        result = [ensure_compat(rep, i, n_, p_, s_) for n_, p_, s_ in result]
    if spec["mode"] == "video" and spec.get("subs") and not existed:
        warn_missing_subs(rep, result, spec, has_ffmpeg)
    sidecars = []
    if spec["mode"] == "video" and spec.get("sidecar"):
        sidecars = collect_sidecars(result)
        if not existed and not any(n_.lower().endswith(THUMB_EXTS) for n_, _p, _s in sidecars):
            rep.warn("no_poster", "Poster tidak tersedia untuk video ini; hanya metadata yang disimpan.")
    rep.done(i, item_dir, result, existed, sidecars)
    return "ok"


# ---------------------------------------------------------------- sidecar poster + metadata

def slim_info_json(path):
    """Ringkas .info.json yt-dlp (bisa beberapa MB karena daftar format) jadi berkas kecil berisi kunci yang
    dibutuhkan Molina Cinema. Idempoten (penanda _molina_slim) dan atomik. True bila hasilnya ringkas."""
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return False
    if not isinstance(data, dict):
        return False
    if data.get("_molina_slim") == 1:
        return True
    slim = {k: data[k] for k in INFO_KEEP if data.get(k) not in (None, "", [], {})}
    desc = slim.get("description")
    if isinstance(desc, str):
        slim["description"] = desc[:INFO_DESC_MAX]
    tags = slim.get("tags")
    if isinstance(tags, list):
        slim["tags"] = [t for t in tags if isinstance(t, str)][:INFO_TAGS_MAX]
    slim["_molina_slim"] = 1
    tmp = path + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(slim, f, ensure_ascii=False, separators=(",", ":"))
        os.replace(tmp, path)
    except OSError:
        try:
            os.remove(tmp)
        except OSError:
            pass
        return False
    return True


def find_sidecars(media_path):
    """Poster (.jpg/.jpeg/.png/.webp, satu saja) dan .info.json yang namanya sama dengan video, bila ada.
    Mengembalikan [(name, path, size)], poster lebih dulu."""
    base = os.path.splitext(media_path)[0]
    found = []
    for group in (THUMB_EXTS, (INFO_SUFFIX,)):
        for suffix in group:
            p = base + suffix
            try:
                if os.path.isfile(p):
                    found.append((os.path.basename(p), p, os.path.getsize(p)))
                    break
            except OSError:
                continue
    return found


def collect_sidecars(result):
    """Sidecar tiap video di [result]. .info.json diringkas lebih dulu supaya ukuran yang dilaporkan sudah final."""
    exts = tuple("." + e for e in VIDEO_EXTS)
    out = []
    for name, path, _size in result:
        if not name.lower().endswith(exts):
            continue
        info = os.path.splitext(path)[0] + INFO_SUFFIX
        if os.path.isfile(info):
            slim_info_json(info)
        out.extend(find_sidecars(path))
    return out


def probe_codecs(path):
    """(vcodec, acodec, pix_fmt) via ffprobe; (None, None, None) bila tidak terbaca."""
    try:
        res = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name,pix_fmt",
             "-of", "json", path],
            capture_output=True, text=True, timeout=30, stdin=subprocess.DEVNULL)
        streams = json.loads(res.stdout).get("streams", [])
    except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError):
        return None, None, None
    v = next((s for s in streams if s.get("codec_type") == "video"), {})
    a = next((s for s in streams if s.get("codec_type") == "audio"), {})
    return v.get("codec_name"), a.get("codec_name"), v.get("pix_fmt")


def count_sub_streams(path):
    """Jumlah aliran subtitle (ffprobe); None bila tidak terbaca."""
    try:
        res = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "s", "-show_entries", "stream=index",
                              "-of", "csv=p=0", path],
                             capture_output=True, text=True, timeout=30, stdin=subprocess.DEVNULL)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if res.returncode != 0:
        return None
    return len([ln for ln in res.stdout.splitlines() if ln.strip()])


def warn_missing_subs(rep, result, spec, has_ffmpeg):
    """Beri tahu bila bahasa yang diminta ternyata tidak tersedia (yt-dlp hanya memberi peringatan biasa)."""
    langs = ", ".join(spec["subs"])
    if spec.get("subs_mode") == "file" or not has_ffmpeg:
        if not any(n_.lower().endswith(SUB_EXTS) for n_, _p, _s in result):
            rep.warn("no_subs", f"Subtitle ({langs}) tidak tersedia untuk video ini.")
        return
    first = next((p_ for _n, p_, _s in result if p_.lower().endswith((".mp4", ".mkv"))), None)
    if first and shutil.which("ffprobe") and count_sub_streams(first) == 0:
        rep.warn("no_subs", f"Subtitle ({langs}) tidak tersedia untuk video ini.")


def ensure_compat(rep, i, name, path, size):
    """Pastikan MP4 = H.264 + AAC + yuv420p + faststart. Konversi hanya bila perlu."""
    if not path.lower().endswith(".mp4") or not shutil.which("ffprobe"):
        return name, path, size
    vcodec, acodec, pix = probe_codecs(path)
    if vcodec is None:
        return name, path, size
    video_ok = vcodec == "h264" and pix in (None, "yuv420p")
    audio_ok = acodec in (None, "aac")
    if video_ok and audio_ok:
        return name, path, size
    rep.stage(i, "recode")
    tmp = path[:-4] + ".compat.mp4"
    cmd = ["ffmpeg", "-y", "-v", "error", "-i", path, "-map", "0:v:0", "-map", "0:a:0?", "-map", "0:s?"]
    cmd += ["-c:v", "copy"] if video_ok else ["-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p"]
    cmd += ["-c:a", "copy"] if audio_ok else ["-c:a", "aac", "-b:a", "160k"]
    cmd += ["-c:s", "mov_text", "-movflags", "+faststart", tmp]
    try:
        rc = subprocess.run(cmd, stdin=subprocess.DEVNULL, capture_output=True).returncode
        if rc == 0 and os.path.getsize(tmp) > 0:
            os.replace(tmp, path)
            return name, path, os.path.getsize(path)
        rep.warn("recode_failed", f"Gagal mengonversi {name} ke H.264/AAC; file asli dipertahankan.")
    except OSError:
        rep.warn("recode_failed", f"Gagal mengonversi {name} ke H.264/AAC; file asli dipertahankan.")
    finally:
        if os.path.exists(tmp):
            try:
                os.remove(tmp)
            except OSError:
                pass
    return name, path, size


def snapshot(folder, depth=SNAPSHOT_DEPTH):
    """Himpunan path berkas RELATIF terhadap [folder] (pemisah '/'), sedalam [depth] tingkat.
    depth=1 = hanya isi folder itu sendiri. Folder tersembunyi (awalan '.') dilewati."""
    found = set()

    def walk(path, prefix, left):
        try:
            entries = list(os.scandir(path))
        except OSError:
            return
        for e in entries:
            try:
                if e.is_file():
                    found.add(prefix + e.name)
                elif left > 1 and e.is_dir() and not e.name.startswith("."):
                    walk(e.path, prefix + e.name + "/", left - 1)
            except OSError:
                continue

    walk(folder, "", max(1, depth))
    return found


def rel_name(path, base):
    """Path relatif [path] terhadap [base] dengan pemisah '/', cocok dengan isi snapshot(); None bila gagal."""
    try:
        return os.path.relpath(path, base).replace(os.sep, "/")
    except ValueError:
        return None


def sanitize_folder_name(name, fallback=NO_CHANNEL, max_bytes=FOLDER_NAME_MAX_BYTES):
    """Nama kanal -> nama folder aman untuk storage bersama Android (FAT/sdcardfs) dan mudah dibaca."""
    text = unicodedata.normalize("NFC", str(name or ""))
    text = "".join(c for c in text if unicodedata.category(c) != "Cf")   # zero-width, penanda RTL
    text = BAD_NAME_CHARS.sub("_", text)
    text = re.sub(r"\s+", " ", text).strip().lstrip(".")
    text = text.encode("utf-8")[:max_bytes].decode("utf-8", "ignore").rstrip(". ")
    if not text or set(text) <= {"_", " "}:
        return fallback
    if text.split(".")[0].strip().lower() in RESERVED_NAMES:
        text = "_" + text
    return text


def channel_dir(outdir, info):
    """Subfolder kanal di bawah [outdir]; kanal diambil dari info.channel, lalu info.uploader."""
    info = info or {}
    return os.path.join(outdir, sanitize_folder_name(info.get("channel") or info.get("uploader")))


def find_existing(outdir, info, spec):
    """Berkas yang SAMA (id video + ekstensi sama) di mana pun di bawah [outdir] (satu tingkat subfolder).
    Dipakai mode per kanal: yt-dlp hanya melewati berkas bernama sama di folder tujuan, jadi video yang sudah
    ada di folder datar atau folder kanal lain (mis. nama kanal berubah) akan terunduh ulang tanpa ini.
    Mengembalikan (nama, path, ukuran) atau None. Ekstensi harus sama persis (mp4 vs mkv = berkas berbeda)."""
    vid = (info or {}).get("id")
    if not isinstance(vid, str) or not vid:
        return None
    ext = spec.get("afmt") if spec.get("mode") == "audio" else spec.get("ext")
    if not ext:
        return None
    suffix = f"[{vid}].{ext}".lower()
    best = None
    for rel in snapshot(outdir):
        base = os.path.basename(rel)
        if not base.lower().endswith(suffix) or ".part" in base:
            continue
        path = os.path.join(outdir, rel)
        try:
            cand = (os.path.getmtime(path), base, path, os.path.getsize(path))
        except OSError:
            continue
        if best is None or cand[0] > best[0]:
            best = cand  # beberapa salinan: pakai yang terbaru
    return None if best is None else (best[1], best[2], best[3])


def env_flag(name):
    return os.environ.get(name, "").strip().lower() in ("1", "true", "yes", "ya", "on")


def run_queue(ytdlp, rep, items, spec, outdir, has_ffmpeg):
    """items = [(url, info_atau_None)]. Mengembalikan (ok, failed, cancelled)."""
    ok = failed = 0
    rep.queue(len(items))
    try:
        for idx, (url, info) in enumerate(items, 1):
            status = run_item(ytdlp, rep, idx, len(items), url, spec, outdir, has_ffmpeg, info)
            if status == "ok":
                ok += 1
            else:
                failed += 1
    except KeyboardInterrupt:
        rep.summary(ok, failed, True)
        return ok, failed, True
    rep.summary(ok, failed, False)
    return ok, failed, False


# ---------------------------------------------------------------- doctor & update

def run_capture(cmd, timeout):
    """Jalankan perintah; (kode, keluaran gabungan). Waktu habis = 124, gagal jalan = 127."""
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, errors="replace", timeout=timeout,
                             stdin=subprocess.DEVNULL)
    except subprocess.TimeoutExpired:
        return 124, "Waktu habis. Periksa koneksi internet."
    except OSError as e:
        return 127, str(e)
    return res.returncode, ((res.stdout or "") + "\n" + (res.stderr or "")).strip()


def last_line(text, limit=200):
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    return (lines[-1] if lines else "")[:limit]


def tool_version(cmd, timeout=20):
    rc, out = run_capture(cmd, timeout)
    if rc != 0:
        return None
    first = out.strip().splitlines()
    return first[0].strip() if first else None


def ytdlp_version(ytdlp):
    if not ytdlp:
        return None
    return tool_version(ytdlp + ["--version"]) or "?"


def ytdlp_origin(ytdlp):
    """Asal pemasangan: apt | pip | pipx | binary | None (tidak terpasang)."""
    if not ytdlp:
        return None
    if len(ytdlp) > 1:  # python -m yt_dlp
        return "pip"
    real = os.path.realpath(ytdlp[0])
    if "/pipx/" in real:
        return "pipx"
    if real.startswith("/usr/local/") or "/.local/" in real:
        return "pip"
    if real.startswith("/usr/"):
        return "apt"
    return "binary"


def ffmpeg_version():
    if not shutil.which("ffmpeg"):
        return None
    line = tool_version(["ffmpeg", "-version"]) or ""
    m = re.match(r"ffmpeg version (\S+)", line)
    return m.group(1) if m else (line or "ada")


def collect_doctor(ytdlp, dir_override=None):
    video_dir, audio_dir, warn = resolve_dirs(dir_override)
    root = os.environ.get("MOLINA_ROOT", DEFAULT_ROOT)
    try:
        free = int(shutil.disk_usage(video_dir).free // (1024 * 1024))
    except OSError:
        free = None
    data = {
        "ytdlp": ytdlp_version(ytdlp),
        "ytdlp_method": ytdlp_origin(ytdlp),
        "ffmpeg": ffmpeg_version(),
        "ffprobe": shutil.which("ffprobe") is not None,
        "mutagen": has_mutagen(),
        "python": "%d.%d.%d" % sys.version_info[:3],
        "video_dir": video_dir,
        "audio_dir": audio_dir,
        "root_bound": os.path.isdir(root),
        "free_mb": free,
    }
    issues = []
    if not data["ytdlp"]:
        issues.append("no_ytdlp")
    if not data["ffmpeg"]:
        issues.append("no_ffmpeg")
    elif not data["ffprobe"]:
        issues.append("no_ffprobe")
    if free is not None and free < MIN_FREE_MB:
        issues.append("low_space")
    if warn:
        issues.append("dir_fallback")
    if not ca_bundle_ok():
        issues.append("no_ca")  # rootfs slim tanpa ca-certificates: unduhan HTTPS gagal
    if not data["mutagen"]:
        issues.append("no_mutagen")  # info: sampul M4A/FLAC
    data["issues"] = issues
    return data


def pip_available():
    return tool_version([sys.executable, "-m", "pip", "--version"]) is not None


def run_update(rep, ytdlp):
    """Perbarui (atau pasang) yt-dlp. Mengembalikan kode keluar 0/1."""
    before = ytdlp_version(ytdlp)
    if ytdlp_origin(ytdlp) == "pipx" and shutil.which("pipx"):
        method, cmd = "pipx", ["pipx", "upgrade", "yt-dlp"]
    elif pip_available():
        method = "pip"
        cmd = [sys.executable, "-m", "pip", "install", "-U", "--disable-pip-version-check",
               "--break-system-packages", "yt-dlp"]
    else:
        rep.update("failed", method=None, before=before, after=before,
                   msg="pip tidak ada. Pasang dulu: apt install -y python3-pip")
        return 1
    rep.update("start", method=method, before=before, after=None, msg=None)
    rc, out = run_capture(cmd, UPDATE_TIMEOUT)
    if rc != 0 and method == "pip" and "no such option" in out.lower():  # pip lama tanpa --break-system-packages
        cmd.remove("--break-system-packages")
        rc, out = run_capture(cmd, UPDATE_TIMEOUT)
    if rc != 0:
        rep.update("failed", method=method, before=before, after=before,
                   msg=last_line(out) or f"Pembaruan gagal (kode {rc}).")
        return 1
    after = ytdlp_version(find_ytdlp())
    if before and after == before:
        if method == "pip" and "successfully installed" in out.lower():
            rep.update("failed", method=method, before=before, after=after,
                       msg="Versi baru terpasang, tetapi PATH masih memakai yt-dlp lama. "
                           "Pastikan /usr/local/bin lebih dulu dari /usr/bin.")
            return 1
        rep.update("latest", method=method, before=before, after=after, msg=f"yt-dlp sudah terbaru ({after}).")
        return 0
    rep.update("ok", method=method, before=before, after=after,
               msg=f"yt-dlp diperbarui: {before or 'belum ada'} -> {after}.")
    return 0


def run_maintenance(args, ytdlp):
    rep = JsonReporter() if args.json else TextReporter()
    rep.hello()
    rc = 0
    if args.update_ytdlp:
        rc = run_update(rep, ytdlp)
        ytdlp = find_ytdlp()
    if args.doctor:
        data = collect_doctor(ytdlp, args.dir)
        rep.doctor(data)
        if "no_ytdlp" in data["issues"]:
            rc = rc or 1
    return rc


# ---------------------------------------------------------------- alur menu

def read_urls(ytdlp):
    """Minta satu atau banyak URL (dipisah spasi). Satu URL divalidasi dan dibaca infonya.
    Mengembalikan daftar [(url, info_atau_None)] atau None bila batal."""
    while True:
        raw = ask("\nMasukkan URL (banyak URL: pisahkan dengan spasi; kosong = kembali): ")
        if not raw:
            return None
        urls, bad = [], []
        for u in raw.split():
            (urls if URL_RE.match(u) else bad).append(u)
        if bad:
            print("Dilewati (bukan URL http/https): " + ", ".join(bad))
        if not urls:
            continue
        if len(urls) > 1:
            return [(u, None) for u in urls]
        print("Memeriksa URL...")
        info, err = fetch_info(ytdlp, urls[0])
        if err:
            print(f"Galat: {err[1]}")
            continue
        print(f"Judul: {info.get('title') or '(tanpa judul)'}")
        return [(urls[0], info)]


def flow_video(ytdlp, has_ffmpeg, outdir):
    items = read_urls(ytdlp)
    if not items:
        return
    first_info = items[0][1] if len(items) == 1 else None
    heights = available_heights(first_info) if first_info else list(FALLBACK_HEIGHTS)
    idx = choose("Resolusi:", ["Kualitas terbaik"] + [f"{h}p" for h in heights])
    if idx is None:
        return
    e = choose("Format video:", [x.upper() for x in VIDEO_EXTS])
    if e is None:
        return
    if not has_ffmpeg:
        print("Peringatan: ffmpeg tidak ditemukan, hanya format gabungan yang bisa diunduh "
              "(resolusi tinggi bisa tidak tersedia). Pasang: apt install -y ffmpeg")
    s = choose("Subtitle:", ["Tanpa subtitle", "Indonesia (id)", "Inggris (en)", "Indonesia + Inggris"])
    if s is None:
        return
    spec = {"mode": "video", "height": None if idx == 0 else heights[idx - 1], "ext": VIDEO_EXTS[e],
            "subs": [[], ["id"], ["en"], ["id", "en"]][s], "subs_mode": "embed" if has_ffmpeg else "file",
            "per_channel": env_flag("MOLINA_DL_PER_CHANNEL")}
    run_queue(ytdlp, TextReporter(), items, spec, outdir, has_ffmpeg)


def flow_audio(ytdlp, has_ffmpeg, outdir):
    if not has_ffmpeg:
        print("\nUnduh audio butuh ffmpeg untuk konversi. Pasang: apt install -y ffmpeg")
        return
    items = read_urls(ytdlp)
    if not items:
        return
    f = choose("Format audio:", ["MP3", "M4A", "FLAC (lossless)"])
    if f is None:
        return
    fmt = AUDIO_FORMATS[f]
    bitrate = None
    if fmt != "flac":
        b = choose("Kualitas (bitrate):", list(BITRATES), default=2)
        if b is None:
            return
        bitrate = BITRATES[b]
    spec = {"mode": "audio", "afmt": fmt, "bitrate": bitrate, "per_channel": env_flag("MOLINA_DL_PER_CHANNEL")}
    run_queue(ytdlp, TextReporter(), items, spec, outdir, has_ffmpeg)


def menu(ytdlp, has_ffmpeg, video_dir, audio_dir, warn):
    while True:
        print("\n=== Molina DL ===")
        print("1. Unduh Video")
        print("2. Unduh Audio Saja")
        print("3. Periksa lingkungan (doctor)")
        print("4. Perbarui yt-dlp")
        print("5. Keluar")
        print(f"Video -> {video_dir}")
        print(f"Audio -> {audio_dir}")
        if warn:
            print(f"Peringatan: {warn}")
        if not has_ffmpeg:
            print("Peringatan: ffmpeg tidak ada (audio dan video resolusi tinggi dibatasi)")
        choice = ask("Pilih [1-5]: ")
        if choice is None or choice == "5":
            print("Sampai jumpa.")
            return 0
        if choice == "1":
            flow_video(ytdlp, has_ffmpeg, video_dir)
        elif choice == "2":
            flow_audio(ytdlp, has_ffmpeg, audio_dir)
        elif choice == "3":
            TextReporter().doctor(collect_doctor(ytdlp, None))
        elif choice == "4":
            run_update(TextReporter(), ytdlp)
            ytdlp = find_ytdlp() or ytdlp
        else:
            print("Pilihan tidak valid.")


# ---------------------------------------------------------------- CLI

def parse_args(argv):
    ap = argparse.ArgumentParser(prog="molina_dl.py", description="Molina DL - pengunduh video/audio (yt-dlp)")
    ap.add_argument("urls", nargs="*", metavar="URL", help="URL (tanpa URL = mode menu interaktif)")
    ap.add_argument("--url", action="append", default=[], help="URL tambahan (boleh berulang)")
    ap.add_argument("--json", action="store_true", help="keluaran NDJSON, tanpa prompt")
    ap.add_argument("--info", action="store_true", help="hanya metadata dan daftar resolusi")
    ap.add_argument("--mode", choices=("video", "audio"), default="video")
    ap.add_argument("--height", type=int, default=0, help="batas tinggi video (0 = terbaik)")
    ap.add_argument("--ext", choices=VIDEO_EXTS, default="mp4")
    ap.add_argument("--audio-format", choices=AUDIO_FORMATS, default="mp3", dest="afmt")
    ap.add_argument("--bitrate", choices=BITRATES, default="192k")
    ap.add_argument("--no-compat", action="store_true", dest="no_compat",
                    help="MP4 kualitas maksimum (boleh AV1/VP9), tanpa konversi H.264/AAC")
    ap.add_argument("--dir", help="folder simpan (menimpa folder bawaan)")
    ap.add_argument("--subs", default="", help="bahasa subtitle dipisah koma, mis. id,en (hanya mode video)")
    ap.add_argument("--subs-mode", choices=SUB_MODES, default="embed", dest="subs_mode",
                    help="embed = disematkan ke video (bawaan); file = .srt di samping video")
    ap.add_argument("--no-cover", action="store_true", dest="no_cover", help="audio tanpa sampul")
    ap.add_argument("--sidecar", action="store_true",
                    help="video: tulis poster (.jpg) dan metadata ringkas (.info.json) di samping video")
    ap.add_argument("--limit-rate", default="", dest="limit_rate", help="batas kecepatan, mis. 500K atau 2M")
    ap.add_argument("--per-channel", action="store_true", dest="per_channel",
                    help="simpan ke subfolder per kanal: <folder>/<Nama Kanal>/ (atau MOLINA_DL_PER_CHANNEL=1)")
    ap.add_argument("--allow-lan", action="store_true", dest="allow_lan",
                    help="izinkan unduhan dari alamat jaringan lokal/privat (loopback tetap ditolak)")
    ap.add_argument("--doctor", action="store_true", help="periksa lingkungan (yt-dlp, ffmpeg, folder, ruang)")
    ap.add_argument("--update-ytdlp", action="store_true", dest="update_ytdlp", help="perbarui atau pasang yt-dlp")
    ap.add_argument("--version", action="store_true")
    return ap.parse_args(argv)


def raise_interrupt(_sig, _frm):
    raise KeyboardInterrupt


def run_cli(args, ytdlp, has_ffmpeg, urls):
    rep = JsonReporter() if args.json else TextReporter()
    rep.hello()
    bad = [u for u in urls if not URL_RE.match(u)]
    if bad or not urls:
        rep.error(0, "bad_url", "URL tidak valid (harus http/https): " + (", ".join(bad) if bad else "kosong"))
        return 2
    if args.info:
        rc = 0
        for i, url in enumerate(urls, 1):
            info, err = fetch_info(ytdlp, url)
            if err:
                rep.error(i, err[0], err[1])
                rc = 1
            else:
                rep.info(i, info)
        return rc
    if args.mode == "audio" and not has_ffmpeg:
        rep.error(0, "no_ffmpeg", "Unduh audio butuh ffmpeg. Pasang: apt install -y ffmpeg")
        return 2
    subs, serr = parse_subs(args.subs)
    if serr:
        rep.error(0, "bad_subs", serr)
        return 2
    if args.limit_rate and not RATE_RE.match(args.limit_rate):
        rep.error(0, "bad_rate", "Batas kecepatan tidak valid (contoh: 500K, 2M).")
        return 2
    if subs and args.mode == "audio":
        rep.warn("subs_ignored", "Subtitle hanya untuk mode video; opsi subtitle diabaikan.")
        subs = []
    sidecar = bool(args.sidecar)
    if sidecar and args.mode == "audio":
        rep.warn("sidecar_ignored", "Poster/metadata hanya untuk mode video; opsi --sidecar diabaikan.")
        sidecar = False
    if not has_ffmpeg:
        rep.warn("no_ffmpeg", "ffmpeg tidak ada: hanya format gabungan, resolusi tinggi bisa tidak tersedia.")
    if guard_python(ytdlp) is None:
        rep.warn("netguard_off", "Penjaga koneksi tidak bisa dipasang pada yt-dlp ini (bukan skrip Python); pengalihan ke alamat lokal tidak diblokir.")
    if subs and not has_ffmpeg:
        rep.warn("subs_file_only", "Tanpa ffmpeg subtitle hanya disimpan sebagai berkas di samping video.")
    video_dir, audio_dir, warn = resolve_dirs(args.dir)
    if warn:
        rep.warn("dir_fallback", warn)
    outdir = audio_dir if args.mode == "audio" else video_dir
    spec = {"mode": args.mode, "height": args.height or None, "ext": args.ext,
            "afmt": args.afmt, "bitrate": args.bitrate, "compat": not args.no_compat,
            "subs": subs, "subs_mode": args.subs_mode if has_ffmpeg else "file",
            "cover": not args.no_cover, "limit": args.limit_rate or None, "sidecar": sidecar,
            "per_channel": bool(args.per_channel) or env_flag("MOLINA_DL_PER_CHANNEL")}
    if args.mode == "audio" and has_ffmpeg and spec["cover"] and not cover_supported(args.afmt):
        rep.warn("no_cover", f"Sampul {args.afmt.upper()} butuh mutagen (apt install -y python3-mutagen); audio diunduh tanpa sampul.")
    ok, failed, cancelled = run_queue(ytdlp, rep, [(u, None) for u in urls], spec, outdir, has_ffmpeg)
    return 130 if cancelled else (1 if failed else 0)


def main(argv=None):
    args = parse_args(sys.argv[1:] if argv is None else argv)
    if args.version:
        print(f"molina_dl {SCRIPT_VERSION} (protokol {PROTOCOL})")
        return 0
    signal.signal(signal.SIGTERM, raise_interrupt)
    if args.allow_lan:
        os.environ["MOLINA_NETGUARD_ALLOW_LAN"] = "1"
    apply_ca_env()
    urls = args.url + args.urls
    ytdlp = find_ytdlp()
    if args.doctor or args.update_ytdlp:
        try:
            return run_maintenance(args, ytdlp)
        except KeyboardInterrupt:
            return 130
    if not ytdlp:
        msg = "yt-dlp tidak ditemukan. Pasang dengan: apt update && apt install -y yt-dlp ffmpeg"
        if args.json:
            JsonReporter().error(0, "no_ytdlp", msg)
        else:
            print(msg)
        return 2
    has_ffmpeg = shutil.which("ffmpeg") is not None
    if urls or args.json or args.info:
        try:
            return run_cli(args, ytdlp, has_ffmpeg, urls)
        except KeyboardInterrupt:
            return 130
    video_dir, audio_dir, warn = resolve_dirs(args.dir)
    return menu(ytdlp, has_ffmpeg, video_dir, audio_dir, warn)


if __name__ == "__main__":
    sys.exit(main())

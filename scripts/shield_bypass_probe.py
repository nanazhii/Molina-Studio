#!/usr/bin/env python3
"""Probe bypass proxy Molina Shield di sisi FFmpeg (mpv memakai libavformat yang sama).

Pertanyaan: apakah segmen/kunci/redirect HLS bertema tcp:// bisa mengabaikan http_proxy, dan apakah no_proxy di
lingkungan membuat FFmpeg melewati proxy? Butuh `ffmpeg` di PATH (mis. di Debian PRoot: apt install ffmpeg).
Hanya memakai loopback; tidak ada lalu lintas keluar.

Keluar 0 = tidak ada bypass lewat tcp:// ; 1 = ADA bypass (perlu ditutup) ; 77 = ffmpeg tidak ada (dilewati).
Hasil acuan (FFmpeg 6.1.1): V1-V4 aman; V6 (no_proxy) MEMANG melewati proxy -> aplikasi harus menghapus no_proxy
(MolinaApp: Os.unsetenv, molina_dl.py: apply_shield_proxy).
"""
import shutil, socket, subprocess, sys, threading, time

if not shutil.which("ffmpeg"):
    print("ffmpeg tidak ditemukan; probe dilewati")
    sys.exit(77)

log = []
MODE = {"pl": b""}

def listener(name, port, reply=None):
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", port))
    s.listen(5)
    def run():
        while True:
            try:
                c, _ = s.accept()
            except OSError:
                return
            try:
                c.settimeout(2)
                data = c.recv(4096)
            except Exception:
                data = b""
            log.append((name, data[:60]))
            if reply:
                try:
                    c.sendall(reply(data))
                except Exception:
                    pass
            c.close()
    threading.Thread(target=run, daemon=True).start()

def proxy_reply(data):
    line = data.split(b"\r\n")[0]
    if line.startswith(b"GET ") and b"pl.m3u8" in line:
        pl = MODE["pl"]
        return b"HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % len(pl) + pl
    if line.startswith(b"GET ") and b"redir" in line:
        return b"HTTP/1.1 302 Found\r\nLocation: tcp://127.0.0.1:19999/x\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    return b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

listener("PROXY", 18888, proxy_reply)
listener("DIRECT", 19999)
HEAD = b"#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n"
WL = "http,https,tls,tcp,crypto,data,httpproxy"  # sama dengan REMOTE_PROTOCOLS di MpvLoad
PX = ["-http_proxy", "http://127.0.0.1:18888"]

def run(label, args, pl=b"", env=None):
    log.clear()
    MODE["pl"] = pl
    subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "quiet"] + args + ["-t", "1", "-f", "null", "-"],
                   capture_output=True, timeout=30, env=env)
    time.sleep(0.4)
    direct = any(n == "DIRECT" for n, _ in log)
    via = any(n == "PROXY" and d.startswith(b"GET") for n, d in log)
    print("%-55s langsung=%-5s lewat-proxy=%s" % (label, "YA" if direct else "tidak", "ya" if via else "tidak"))
    return direct, via

import os
seg = HEAD + b"#EXTINF:2.0,\ntcp://127.0.0.1:19999\n#EXT-X-ENDLIST\n"
ctl_direct, _ = run("KONTROL ffmpeg -i tcp://127.0.0.1:19999 tanpa proxy", ["-protocol_whitelist", WL, "-i", "tcp://127.0.0.1:19999"])
if not ctl_direct:
    print("Kontrol positif gagal: alat uji tidak valid"); sys.exit(2)
bad = []
for label, args, pl in (
    ("V1 segmen tcp://", ["-protocol_whitelist", WL] + PX + ["-i", "http://o.invalid/pl.m3u8"], seg),
    ("V2 EXT-X-KEY URI tcp://", ["-protocol_whitelist", WL] + PX + ["-i", "http://o.invalid/pl.m3u8"],
     HEAD + b'#EXT-X-KEY:METHOD=AES-128,URI="tcp://127.0.0.1:19999"\n#EXTINF:2.0,\nseg.ts\n#EXT-X-ENDLIST\n'),
    ("V3 segmen http://127.0.0.1:19999 (harus via proxy)", ["-protocol_whitelist", WL] + PX + ["-i", "http://o.invalid/pl.m3u8"],
     HEAD + b"#EXTINF:2.0,\nhttp://127.0.0.1:19999/s.ts\n#EXT-X-ENDLIST\n"),
    ("V4 redirect 302 ke tcp://", ["-protocol_whitelist", WL] + PX + ["-i", "http://o.invalid/redir"], b""),
):
    d, _ = run(label, args, pl)
    if d:
        bad.append(label)
# V6: no_proxy di lingkungan (informasional; kebijakan aplikasi: variabel ini dihapus)
env = dict(os.environ, no_proxy="o.invalid")
_, via = run("V6 no_proxy=o.invalid di env (proxy dilewati?)", ["-protocol_whitelist", WL] + PX + ["-i", "http://o.invalid/pl.m3u8"], seg, env)
print("INFO V6: no_proxy %s proxy -> %s" % ("MELEWATI" if not via else "tidak melewati", "aplikasi wajib menghapus no_proxy" if not via else "aman"))
if bad:
    print("BYPASS DITEMUKAN:", ", ".join(bad)); sys.exit(1)
print("Tidak ada bypass tcp:// pada FFmpeg ini"); sys.exit(0)

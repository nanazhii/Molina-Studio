#!/usr/bin/env python3
# Molina CLI: kirim perintah dari Debian ke aplikasi Molina lewat abstract Unix socket @molina.cmd.
# Dibuat otomatis oleh aplikasi (jangan diedit). Hanya pustaka standar Python 3.
# Kode keluar: 0 berhasil, 1 ditolak aplikasi, 2 salah pakai, 3 aplikasi tidak terjangkau.
import base64
import os
import socket
import struct
import sys

CMD_NAME = os.environ.get("MOLINA_CMD_SOCKET", "molina.cmd")
LOG_NAME = os.environ.get("MOLINA_LOG_SOCKET", "molina.log")

HELP = """molina - kendalikan aplikasi Molina dari Debian

Pemakaian:
  molina ping                    cek koneksi ke aplikasi
  molina version                 versi protokol
  molina status                  status pemutar (aktif/diam)
  molina play <path|URL|->       putar berkas, folder, atau URL di Molina Cinema
                                 ("-" = baca satu baris dari stdin)
  molina stop                    hentikan pemutar
  molina open <berkas>...        buka berkas (maks 10) di editor Code
  molina log [-l D|I|W|E] [-t tag] pesan...
                                 kirim baris ke Log Molina (sumber "cli")

Path harus di bawah /sdcard atau /storage/emulated/0 (terlihat dari Debian).
Contoh:
  molina play /sdcard/Molina/Media/Video/film.mkv
  yt-dlp -f b -g "https://..." | molina play -
"""

LEVELS = ("D", "I", "W", "E")


def own_uid():
    """UID asli proses dari kernel. PRoot -0 memalsukan getuid() jadi 0, tetapi /proc/self/status tidak. None bila tak terbaca."""
    try:
        with open("/proc/self/status", encoding="ascii", errors="replace") as f:
            for line in f:
                if line.startswith("Uid:"):
                    return int(line.split()[1])
    except (OSError, ValueError, IndexError):
        pass
    return None


def verify_peer(s, name, own=None):
    """[CLI-PEERCHECK] Tolak server palsu: aplikasi lain bisa lebih dulu memakai nama abstract socket (squatting)
    lalu menerima isi clipboard, teks notifikasi, dan share. UID server harus sama dengan UID proses ini.
    Dilewati (tidak menolak) bila UID sendiri tak terbaca atau 0 (dipalsukan), atau SO_PEERCRED tak tersedia,
    supaya CLI tidak patah."""
    own = own_uid() if own is None else own
    if own in (None, 0):
        return
    try:
        _pid, peer, _gid = struct.unpack("3i", s.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, struct.calcsize("3i")))
    except (OSError, AttributeError, struct.error):
        return
    if peer != own:
        raise PermissionError("server @%s bukan milik aplikasi Molina (uid %d, seharusnya %d)" % (name, peer, own))


def connect(name, timeout):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        s.settimeout(timeout)
        s.connect("\0" + name)
        verify_peer(s, name)
    except BaseException:
        s.close()
        raise
    return s


def call(verb, arg="", timeout=10.0):
    """Kirim satu perintah, kembalikan satu baris balasan (tanpa newline)."""
    payload = verb + ("\t" + arg if arg else "") + "\n"
    s = connect(CMD_NAME, timeout)
    try:
        s.sendall(payload.encode("utf-8"))
        data = b""
        while b"\n" not in data:
            chunk = s.recv(4096)
            if not chunk:
                break
            data += chunk
    finally:
        s.close()
    return data.decode("utf-8", "replace").split("\n", 1)[0]


def run(verb, arg="", on_ok=None):
    try:
        reply = call(verb, arg)
    except (ConnectionRefusedError, FileNotFoundError):
        sys.stderr.write("molina: aplikasi Molina tidak berjalan atau belum siap (buka aplikasinya dulu)\n")
        return 3
    except (socket.timeout, OSError) as e:
        sys.stderr.write("molina: gagal menghubungi aplikasi: %s\n" % e)
        return 3
    if reply == "OK" or reply.startswith("OK "):
        text = reply[3:]
        if on_ok is not None:
            return on_ok(text)
        if text:
            print(text)
        return 0
    if reply.startswith("ERR "):
        parts = reply.split(" ", 2)
        sys.stderr.write("molina: %s\n" % (parts[2] if len(parts) > 2 else parts[1]))
        return 1
    sys.stderr.write("molina: balasan tidak dikenal dari aplikasi\n")
    return 1


def clean(s):
    return s.replace("\t", " ").replace("\r", " ").replace("\n", " ")


def send_log(level, tag, message):
    line = "\t".join(["cli", level, clean(tag), clean(message)]) + "\n"
    try:
        s = connect(LOG_NAME, 5.0)
        try:
            s.sendall(line.encode("utf-8"))
        finally:
            s.close()
    except OSError:
        sys.stderr.write("molina: aplikasi Molina tidak berjalan atau belum siap\n")
        return 3
    return 0


def cmd_play(args):
    if len(args) != 1:
        sys.stderr.write("molina: play butuh tepat satu path, URL, atau -\n")
        return 2
    target = args[0]
    if target == "-":
        target = ""
        for line in sys.stdin:
            if line.strip():
                target = line.strip()
                break
        if not target:
            sys.stderr.write("molina: stdin kosong\n")
            return 2
    if "://" not in target:
        target = os.path.abspath(os.path.expanduser(target))
    return run("play", target)


def cmd_open(args):
    if not args:
        sys.stderr.write("molina: open butuh minimal satu berkas\n")
        return 2
    if len(args) > 10:
        sys.stderr.write("molina: open maksimal 10 berkas sekaligus\n")
        return 2
    paths = []
    for a in args:
        p = os.path.abspath(os.path.expanduser(a))
        if "\t" in p or "\n" in p or "\r" in p:
            sys.stderr.write("molina: nama berkas mengandung TAB atau baris baru (tidak didukung)\n")
            return 2
        paths.append(p)
    return run("open", "\t".join(paths))


def cmd_log(args):
    level, tag, words = "I", "molina", list(args)
    while words and words[0] in ("-l", "-t"):
        if len(words) < 2:
            sys.stderr.write("molina: opsi %s butuh nilai\n" % words[0])
            return 2
        if words[0] == "-l":
            level = words[1].upper()
            if level not in LEVELS:
                sys.stderr.write("molina: level harus D, I, W, atau E\n")
                return 2
        else:
            tag = words[1]
        words = words[2:]
    if not words:
        sys.stderr.write("molina: log butuh pesan\n")
        return 2
    return send_log(level, tag, " ".join(words))


MAX_CLIP = 65536


def b64(text):
    return base64.b64encode(text.encode("utf-8", "replace")).decode("ascii")


def read_stdin_text():
    data = sys.stdin.buffer.read(MAX_CLIP + 1)
    if len(data) > MAX_CLIP:
        sys.stderr.write("molina: masukan lebih dari %d byte\n" % MAX_CLIP)
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        sys.stderr.write("molina: masukan bukan UTF-8\n")
        return None


def too_big(text):
    if len(text.encode("utf-8", "replace")) > MAX_CLIP:
        sys.stderr.write("molina: teks lebih dari %d byte\n" % MAX_CLIP)
        return True
    return False


def clip_out(text):
    try:
        raw = base64.b64decode(text.encode("ascii"), validate=True) if text else b""
    except ValueError:
        sys.stderr.write("molina: balasan clipboard tidak sah\n")
        return 1
    sys.stdout.buffer.write(raw)
    sys.stdout.buffer.flush()
    return 0


def cmd_clip(args):
    if args == ["get"]:
        return run("clip", "get", clip_out)
    if args and args[0] == "set":
        rest = args[1:]
        if rest == ["-"]:
            text = read_stdin_text()
            if text is None:
                return 2
        elif rest:
            text = " ".join(rest)
        else:
            sys.stderr.write("molina: clip set butuh teks atau -\n")
            return 2
        if too_big(text):
            return 2
        return run("clip", "set\t" + b64(text))
    sys.stderr.write("molina: pakai: molina clip get | molina clip set <teks...|->\n")
    return 2


def cmd_notify(args):
    title, words = None, list(args)
    if words and words[0] == "-t":
        if len(words) < 2:
            sys.stderr.write("molina: opsi -t butuh nilai\n")
            return 2
        title, words = clean(words[1]).strip(), words[2:]
    text = clean(" ".join(words)).strip()
    if not text:
        sys.stderr.write("molina: notify butuh pesan\n")
        return 2
    return run("notify", (title + "\t" + text) if title else text)


def cmd_share(args):
    words = list(args)
    if words and words[0] == "-t":
        text = " ".join(words[1:])
    elif words == ["-"]:
        text = read_stdin_text()
        if text is None:
            return 2
    elif words:
        if len(words) > 10:
            sys.stderr.write("molina: share maksimal 10 berkas sekaligus\n")
            return 2
        paths = []
        for a in words:
            p = os.path.abspath(os.path.expanduser(a))
            if "\t" in p or "\n" in p or "\r" in p:
                sys.stderr.write("molina: nama berkas mengandung TAB atau baris baru (tidak didukung)\n")
                return 2
            paths.append(p)
        return run("share", "file\t" + "\t".join(paths))
    else:
        sys.stderr.write("molina: share butuh berkas, -t <teks>, atau -\n")
        return 2
    if not text.strip():
        sys.stderr.write("molina: teks kosong\n")
        return 2
    if too_big(text):
        return 2
    return run("share", "text\t" + b64(text))


def main(argv):
    if not argv or argv[0] in ("-h", "--help", "help"):
        sys.stdout.write(HELP)
        return 0 if argv else 2
    verb, rest = argv[0], argv[1:]
    if verb in ("ping", "version", "status", "stop"):
        if rest:
            sys.stderr.write("molina: %s tidak menerima argumen\n" % verb)
            return 2
        return run(verb)
    if verb == "play":
        return cmd_play(rest)
    if verb == "open":
        return cmd_open(rest)
    if verb == "log":
        return cmd_log(rest)
    if verb == "clip":
        return cmd_clip(rest)
    if verb == "notify":
        return cmd_notify(rest)
    if verb == "share":
        return cmd_share(rest)
    sys.stderr.write("molina: perintah tidak dikenal: %s (coba: molina help)\n" % verb)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

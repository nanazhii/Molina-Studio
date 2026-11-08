#!/usr/bin/env python3
"""Uji klien `molina` (molina_cli.py) terhadap server palsu di abstract socket. Hanya stdlib.
Jalankan: python3 scripts/app-tests/test_molina_cli.py"""
import os
import socket
import subprocess
import sys
import threading
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CLI = os.path.join(ROOT, "app/src/main/assets/molina/molina-cli.py")
SUF = str(os.getpid())
CMD = "molina.cmd.test" + SUF
LOG = "molina.log.test" + SUF


class FakeServer:
    """Terima satu koneksi per permintaan; catat baris; balas sesuai fungsi."""

    def __init__(self, name, reply):
        self.reply = reply
        self.lines = []
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.bind("\0" + name)
        self.sock.listen(4)
        self.sock.settimeout(0.2)
        self.stop = False
        self.t = threading.Thread(target=self.loop, daemon=True)
        self.t.start()

    def loop(self):
        while not self.stop:
            try:
                c, _ = self.sock.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            data = b""
            while b"\n" not in data:
                chunk = c.recv(4096)
                if not chunk:
                    break
                data += chunk
            line = data.decode("utf-8").split("\n", 1)[0]
            self.lines.append(line)
            r = self.reply(line)
            if r:
                c.sendall(r.encode("utf-8"))
            c.close()

    def close(self):
        self.stop = True
        self.t.join()
        self.sock.close()


def run_cli(*args, stdin=None):
    env = dict(os.environ, MOLINA_CMD_SOCKET=CMD, MOLINA_LOG_SOCKET=LOG)
    p = subprocess.run([sys.executable, CLI, *args], input=stdin, capture_output=True,
                       text=True, env=env, timeout=20)
    return p.returncode, p.stdout, p.stderr


def echo_reply(line):
    verb = line.split("\t", 1)[0]
    if verb == "ping":
        return "OK pong\n"
    if verb == "version":
        return "OK molina-cmd 1\n"
    if verb == "status":
        return "OK player=diam\n"
    if verb == "stop":
        return "OK\n"
    if verb == "open":
        return "OK membuka\n"
    if verb == "play":
        arg = line.split("\t", 1)[1]
        if arg.endswith("tidakada.mkv"):
            return "ERR missing berkas tidak ditemukan\n"
        return "OK memutar\n"
    return "ERR unknown perintah tidak dikenal\n"


class CliTests(unittest.TestCase):
    def setUp(self):
        self.srv = FakeServer(CMD, echo_reply)

    def tearDown(self):
        self.srv.close()

    def test_simple_verbs(self):
        self.assertEqual(run_cli("ping"), (0, "pong\n", ""))
        self.assertEqual(run_cli("version")[1], "molina-cmd 1\n")
        self.assertEqual(run_cli("status")[1], "player=diam\n")
        self.assertEqual(run_cli("stop")[0:2], (0, ""))
        self.assertEqual(self.srv.lines, ["ping", "version", "status", "stop"])

    def test_play_absolute_path_and_normalization(self):
        code, out, _ = run_cli("play", "/sdcard/Molina/../Molina/a b.mkv")
        self.assertEqual(code, 0)
        self.assertEqual(self.srv.lines[-1], "play\t/sdcard/Molina/a b.mkv")

    def test_play_relative_path_uses_cwd(self):
        env = dict(os.environ, MOLINA_CMD_SOCKET=CMD, MOLINA_LOG_SOCKET=LOG)
        p = subprocess.run([sys.executable, CLI, "play", "film.mp4"], cwd="/tmp",
                           capture_output=True, text=True, env=env, timeout=20)
        self.assertEqual(p.returncode, 0)
        self.assertEqual(self.srv.lines[-1], "play\t/tmp/film.mp4")

    def test_play_url_untouched(self):
        self.assertEqual(run_cli("play", "https://example.org/v.m3u8?a=1")[0], 0)
        self.assertEqual(self.srv.lines[-1], "play\thttps://example.org/v.m3u8?a=1")

    def test_play_stdin_first_nonblank_line(self):
        code, _, _ = run_cli("play", "-", stdin="\n  \nhttps://example.org/x.mp4\nhttps://lain\n")
        self.assertEqual(code, 0)
        self.assertEqual(self.srv.lines[-1], "play\thttps://example.org/x.mp4")

    def test_play_stdin_empty(self):
        code, _, err = run_cli("play", "-", stdin="\n\n")
        self.assertEqual(code, 2)
        self.assertIn("stdin kosong", err)

    def test_open_joins_paths_with_tab(self):
        code, out, _ = run_cli("open", "/sdcard/a.py", "/sdcard/b c.py")
        self.assertEqual(code, 0)
        self.assertEqual(self.srv.lines[-1], "open\t/sdcard/a.py\t/sdcard/b c.py")

    def test_open_usage(self):
        self.assertEqual(run_cli("open")[0], 2)
        self.assertEqual(run_cli("open", *["/sdcard/x"] * 11)[0], 2)
        self.assertEqual(run_cli("open", "/sdcard/a\tb")[0], 2)
        self.assertEqual(self.srv.lines, [])

    def test_server_error_exit_1(self):
        code, out, err = run_cli("play", "/sdcard/tidakada.mkv")
        self.assertEqual(code, 1)
        self.assertEqual(out, "")
        self.assertEqual(err, "molina: berkas tidak ditemukan\n")

    def test_usage_errors_exit_2(self):
        self.assertEqual(run_cli()[0], 2)
        self.assertEqual(run_cli("play")[0], 2)
        self.assertEqual(run_cli("play", "a", "b")[0], 2)
        self.assertEqual(run_cli("ping", "x")[0], 2)
        self.assertEqual(run_cli("joget")[0], 2)
        self.assertEqual(run_cli("log")[0], 2)
        self.assertEqual(run_cli("log", "-l", "X", "pesan")[0], 2)
        self.assertEqual(run_cli("log", "-t")[0], 2)
        self.assertEqual(self.srv.lines, [])  # tidak ada yang sampai ke server

    def test_help(self):
        code, out, _ = run_cli("help")
        self.assertEqual(code, 0)
        self.assertIn("molina play", out)

    def test_log_line_format(self):
        logsrv = FakeServer(LOG, lambda line: "")
        try:
            code, _, _ = run_cli("log", "-l", "w", "-t", "uji", "halo", "dunia\tx")
            self.assertEqual(code, 0)
            import time
            for _ in range(20):
                if logsrv.lines:
                    break
                time.sleep(0.05)
            self.assertEqual(logsrv.lines, ["cli\tW\tuji\thalo dunia x"])
        finally:
            logsrv.close()


class UnreachableTests(unittest.TestCase):
    def test_no_server_exit_3(self):
        code, out, err = run_cli("ping")
        self.assertEqual(code, 3)
        self.assertIn("tidak berjalan", err)
        self.assertEqual(run_cli("play", "/sdcard/a.mp4")[0], 3)
        self.assertEqual(run_cli("log", "halo")[0], 3)


class GarbageReplyTests(unittest.TestCase):
    def test_unknown_reply_exit_1(self):
        srv = FakeServer(CMD, lambda line: "AMBURADUL\n")
        try:
            code, _, err = run_cli("ping")
            self.assertEqual(code, 1)
            self.assertIn("balasan tidak dikenal", err)
        finally:
            srv.close()

    def test_close_without_reply_exit_1(self):
        srv = FakeServer(CMD, lambda line: "")
        try:
            self.assertEqual(run_cli("ping")[0], 1)
        finally:
            srv.close()


import base64


class BridgeTests(unittest.TestCase):
    CLIP = "isi clip \u2713\nbaris 2"

    def setUp(self):
        self.srv = FakeServer(CMD, self.reply)

    def tearDown(self):
        self.srv.close()

    def reply(self, line):
        if line == "clip\tget":
            return "OK " + base64.b64encode(self.CLIP.encode()).decode() + "\n"
        if line == "clip\tset\t" + base64.b64encode(b"deny").decode():
            return "ERR denied akses clipboard mati\n"
        return "OK ok\n"

    @staticmethod
    def b(s):
        return base64.b64encode(s.encode()).decode()

    def test_clip_get_decodes_utf8_without_extra_newline(self):
        self.assertEqual(run_cli("clip", "get"), (0, self.CLIP, ""))

    def test_clip_get_empty(self):
        self.srv.reply = lambda line: "OK\n"
        self.assertEqual(run_cli("clip", "get")[0:2], (0, ""))

    def test_clip_set_args_joined(self):
        self.assertEqual(run_cli("clip", "set", "halo", "dunia")[0], 0)
        self.assertEqual(self.srv.lines[-1], "clip\tset\t" + self.b("halo dunia"))

    def test_clip_set_stdin(self):
        self.assertEqual(run_cli("clip", "set", "-", stdin="a\nb\n")[0], 0)
        self.assertEqual(self.srv.lines[-1], "clip\tset\t" + self.b("a\nb\n"))

    def test_clip_set_stdin_too_big(self):
        code, _, err = run_cli("clip", "set", "-", stdin="x" * 70000)
        self.assertEqual(code, 2)
        self.assertEqual(self.srv.lines, [])

    def test_clip_usage(self):
        self.assertEqual(run_cli("clip")[0], 2)
        self.assertEqual(run_cli("clip", "get", "x")[0], 2)
        self.assertEqual(run_cli("clip", "set")[0], 2)
        self.assertEqual(self.srv.lines, [])

    def test_clip_denied_exit_1(self):
        code, out, err = run_cli("clip", "set", "deny")
        self.assertEqual((code, out), (1, ""))
        self.assertIn("akses clipboard mati", err)

    def test_notify_text(self):
        self.assertEqual(run_cli("notify", "Build", "selesai")[0], 0)
        self.assertEqual(self.srv.lines[-1], "notify\tBuild selesai")

    def test_notify_title(self):
        self.assertEqual(run_cli("notify", "-t", "Judul", "isi pesan")[0], 0)
        self.assertEqual(self.srv.lines[-1], "notify\tJudul\tisi pesan")

    def test_notify_tabs_cleaned(self):
        self.assertEqual(run_cli("notify", "a\tb")[0], 0)
        self.assertEqual(self.srv.lines[-1], "notify\ta b")

    def test_notify_usage(self):
        for args in ((), ("-t",), ("-t", "X"), ("  ",)):
            self.assertEqual(run_cli("notify", *args)[0], 2, args)
        self.assertEqual(self.srv.lines, [])

    def test_share_files(self):
        self.assertEqual(run_cli("share", "/sdcard/a.png", "/sdcard/b c.png")[0], 0)
        self.assertEqual(self.srv.lines[-1], "share\tfile\t/sdcard/a.png\t/sdcard/b c.png")

    def test_share_text(self):
        self.assertEqual(run_cli("share", "-t", "halo dunia")[0], 0)
        self.assertEqual(self.srv.lines[-1], "share\ttext\t" + self.b("halo dunia"))

    def test_share_stdin(self):
        self.assertEqual(run_cli("share", "-", stdin="baris1\nbaris2\n")[0], 0)
        self.assertEqual(self.srv.lines[-1], "share\ttext\t" + self.b("baris1\nbaris2\n"))

    def test_share_usage(self):
        self.assertEqual(run_cli("share")[0], 2)
        self.assertEqual(run_cli("share", *["/sdcard/x"] * 11)[0], 2)
        self.assertEqual(run_cli("share", "/sdcard/a\tb")[0], 2)
        self.assertEqual(run_cli("share", "-t", "  ")[0], 2)
        self.assertEqual(self.srv.lines, [])


if __name__ == "__main__":
    unittest.main(verbosity=2)

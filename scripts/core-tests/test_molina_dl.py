#!/usr/bin/env python3
# Uji molina_dl.py (v5). Jalankan: python3 -m unittest -v scripts/core-tests/test_molina_dl.py
# Unit murni selalu jalan. Uji integrasi memakai yt-dlp + ffmpeg asli terhadap server HTTP lokal
# (tanpa internet) dan dilewati otomatis bila alatnya tidak ada.
import functools
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.abspath(os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "molina", "molina_dl.py"))
_spec = importlib.util.spec_from_file_location("molina_dl", SCRIPT)
dl = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(dl)

HAVE_TOOLS = bool(dl.find_ytdlp()) and bool(shutil.which("ffmpeg")) and bool(shutil.which("ffprobe"))
# Server uji ada di 127.0.0.1; penjaga koneksi (loopback ditolak) dibuka hanya untuk uji integrasi.
os.environ.setdefault("MOLINA_NETGUARD_ALLOW_LOOPBACK", "1")


class Recorder:
    """Pelapor palsu: mengumpulkan event."""
    interactive = False

    def __init__(self):
        self.events = []

    def update(self, status, **f):
        self.events.append(("update", status, f))

    def doctor(self, data):
        self.events.append(("doctor", data))

    def warn(self, code, msg):
        self.events.append(("warn", code))

    def hello(self):
        pass

    def statuses(self):
        return [e[1] for e in self.events if e[0] == "update"]


class ParseTests(unittest.TestCase):
    def test_parse_subs(self):
        self.assertEqual(dl.parse_subs("id,en"), (["id", "en"], None))
        self.assertEqual(dl.parse_subs(" id , id ,pt-BR"), (["id", "pt-BR"], None))
        self.assertEqual(dl.parse_subs(""), ([], None))
        for bad in ("id;rm", "../x", "en.*", "a" * 30, "--x", "i"):
            self.assertTrue(dl.parse_subs(bad)[1], bad)
        self.assertTrue(dl.parse_subs("id,en,fr,de,es,ja")[1])

    def test_rate_regex(self):
        for ok in ("500K", "2M", "1.5M", "300", "2m"):
            self.assertTrue(dl.RATE_RE.match(ok), ok)
        for bad in ("0", "-1", "2MB", "abc", "1 M", ""):
            self.assertFalse(dl.RATE_RE.match(bad), bad)

    def test_stage_map(self):
        self.assertEqual(dl.PP_STAGES["EmbedSubtitle"], "subs")
        self.assertEqual(dl.PP_STAGES["EmbedThumbnail"], "cover")

    def test_build_cmd_subs_cover_rate(self):
        base = {"mode": "video", "ext": "mp4", "height": None}
        c = dl.build_cmd(["yt-dlp"], "http://x/1", "/o", base, True)
        for flag in ("--write-subs", "--embed-subs", "--limit-rate", "--embed-thumbnail"):
            self.assertNotIn(flag, c)
        c = dl.build_cmd(["yt-dlp"], "http://x/1", "/o", dict(base, subs=["id", "en"], limit="2M"), True)
        self.assertEqual(c[c.index("--sub-langs") + 1], "id,en")
        self.assertIn("--embed-subs", c)
        self.assertIn("no-keep-subs", c)  # supaya berkas subtitle tidak tersisa setelah disematkan
        self.assertEqual(c[c.index("--limit-rate") + 1], "2M")
        c = dl.build_cmd(["yt-dlp"], "http://x/1", "/o", dict(base, subs=["id"], subs_mode="file"), True)
        self.assertNotIn("--embed-subs", c)
        self.assertIn("--write-subs", c)
        c = dl.build_cmd(["yt-dlp"], "http://x/1", "/o", dict(base, subs=["id"]), False)  # tanpa ffmpeg
        self.assertNotIn("--embed-subs", c)
        self.assertNotIn("--convert-subs", c)
        self.assertIn("--write-subs", c)
        self.assertEqual(c[-1], "http://x/1")

    def test_build_cmd_ignore_config(self):
        base = {"mode": "video", "ext": "mp4", "height": None}
        c = dl.build_cmd(["yt-dlp"], "http://x/1", "/o", base, True)
        self.assertIn("--ignore-config", c)
        self.assertLess(c.index("--ignore-config"), c.index("-o"))

    def test_build_cmd_cover(self):
        a = {"mode": "audio", "afmt": "mp3", "bitrate": "192k"}
        self.assertIn("--embed-thumbnail", dl.build_cmd(["y"], "http://x/1", "/o", a, True))
        self.assertNotIn("--embed-thumbnail", dl.build_cmd(["y"], "http://x/1", "/o", dict(a, cover=False), True))
        self.assertNotIn("--embed-thumbnail", dl.build_cmd(["y"], "http://x/1", "/o", a, False))
        self.assertNotIn("--write-subs", dl.build_cmd(["y"], "http://x/1", "/o", dict(a, subs=["id"]), True))

    def test_cover_supported(self):
        self.assertTrue(dl.cover_supported("mp3"))
        orig_which, orig_mut = dl.shutil.which, dl.has_mutagen
        try:
            dl.shutil.which = lambda n: None
            dl.has_mutagen = lambda: False
            self.assertFalse(dl.cover_supported("m4a"))
            self.assertFalse(dl.cover_supported("flac"))
            dl.has_mutagen = lambda: True
            self.assertTrue(dl.cover_supported("flac"))
        finally:
            dl.shutil.which, dl.has_mutagen = orig_which, orig_mut

    def test_origin(self):
        self.assertIsNone(dl.ytdlp_origin(None))
        self.assertEqual(dl.ytdlp_origin(["python3", "-m", "yt_dlp"]), "pip")
        self.assertEqual(dl.ytdlp_origin(["/usr/local/bin/yt-dlp"]), "pip")


class SidecarUnitTests(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp(prefix="mdl-side-")

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    def put(self, name, text="x"):
        p = os.path.join(self.d, name)
        with open(p, "w", encoding="utf-8") as f:
            f.write(text)
        return p

    def test_build_cmd_sidecar_flags(self):
        base = {"mode": "video", "ext": "mp4", "height": None}
        c = dl.build_cmd(["y"], "http://x/1", "/o", base, True)
        for flag in ("--write-thumbnail", "--write-info-json"):
            self.assertNotIn(flag, c)  # bawaan mati: folder unduhan tetap bersih
        c = dl.build_cmd(["y"], "http://x/1", "/o", dict(base, sidecar=True), True)
        self.assertIn("--write-thumbnail", c)
        self.assertIn("--write-info-json", c)
        self.assertEqual(c[c.index("--convert-thumbnails") + 1], "jpg")
        c = dl.build_cmd(["y"], "http://x/1", "/o", dict(base, sidecar=True), False)  # tanpa ffmpeg
        self.assertIn("--write-thumbnail", c)
        self.assertNotIn("--convert-thumbnails", c)
        a = {"mode": "audio", "afmt": "mp3", "bitrate": "192k", "sidecar": True}
        c = dl.build_cmd(["y"], "http://x/1", "/o", a, True)
        self.assertNotIn("--write-info-json", c)
        self.assertNotIn("--write-thumbnail", c)

    def test_slim_info_json(self):
        big = {"id": "abc", "title": "Pelajaran – Bab 1", "uploader": "Kanal", "duration": 61, "upload_date": "20240131",
               "description": "d" * 5000, "tags": ["t%d" % i for i in range(50)] + [1],
               "formats": [{"url": "http://x/%d" % i, "format_id": str(i)} for i in range(2000)],
               "http_headers": {"User-Agent": "x"}, "view_count": 0, "like_count": None, "categories": []}
        p = self.put("a.info.json", json.dumps(big))
        before = os.path.getsize(p)
        self.assertTrue(dl.slim_info_json(p))
        with open(p, encoding="utf-8") as f:
            slim = json.load(f)
        self.assertLess(os.path.getsize(p), min(before // 10, 6000))
        for gone in ("formats", "http_headers", "like_count", "categories"):
            self.assertNotIn(gone, slim)
        self.assertEqual(slim["title"], "Pelajaran – Bab 1")
        self.assertEqual(len(slim["description"]), dl.INFO_DESC_MAX)
        self.assertEqual(len(slim["tags"]), dl.INFO_TAGS_MAX)
        self.assertEqual(slim["view_count"], 0)  # nol tetap disimpan
        self.assertEqual(slim["_molina_slim"], 1)
        with open(p, "rb") as f:
            first = f.read()
        self.assertIn("Pelajaran – Bab 1".encode("utf-8"), first)  # unicode tidak di-escape
        self.assertTrue(dl.slim_info_json(p))
        with open(p, "rb") as f:
            self.assertEqual(f.read(), first)  # idempoten
        self.assertFalse(os.path.exists(p + ".tmp"))

    def test_slim_info_json_bad_input(self):
        p = self.put("b.info.json", "{rusak")
        self.assertFalse(dl.slim_info_json(p))
        with open(p, encoding="utf-8") as f:
            self.assertEqual(f.read(), "{rusak")  # berkas tidak disentuh
        self.assertFalse(dl.slim_info_json(self.put("c.info.json", "[1]")))
        self.assertFalse(dl.slim_info_json(os.path.join(self.d, "tidak-ada.info.json")))

    def test_find_sidecars(self):
        v = self.put("Mr. Film [abc].mp4")
        self.assertEqual(dl.find_sidecars(v), [])
        self.put("Mr. Film [abc].webp")
        self.put("Mr. Film [abc].info.json", "{}")
        self.assertEqual([n for n, _p, _s in dl.find_sidecars(v)], ["Mr. Film [abc].webp", "Mr. Film [abc].info.json"])
        self.put("Mr. Film [abc].jpg")
        names = [n for n, _p, _s in dl.find_sidecars(v)]
        self.assertEqual(names, ["Mr. Film [abc].jpg", "Mr. Film [abc].info.json"])  # satu poster saja, jpg lebih dulu
        self.put("Lain [zzz].jpg")  # milik video lain tidak ikut
        self.assertEqual(len(dl.find_sidecars(v)), 2)

    def test_collect_sidecars_slims_and_skips_non_video(self):
        v = self.put("V [a].mp4")
        srt = self.put("V [a].id.srt")
        self.put("V [a].jpg")
        info = self.put("V [a].info.json", json.dumps({"id": "a", "title": "V", "formats": [{"x": "y" * 3000}]}))
        result = [(os.path.basename(p), p, 1) for p in (v, srt)]
        got = dl.collect_sidecars(result)
        self.assertEqual([n for n, _p, _s in got], ["V [a].jpg", "V [a].info.json"])
        self.assertEqual(got[1][2], os.path.getsize(info))  # ukuran yang dilaporkan = setelah diringkas
        self.assertLess(got[1][2], 300)

    def test_json_done_sidecars_only_when_present(self):
        import contextlib
        import io
        rep = dl.JsonReporter()
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rep.done(1, "/o", [("a.mp4", "/o/a.mp4", 5)], False)
            rep.done(2, "/o", [("a.mp4", "/o/a.mp4", 5)], False, [("a.jpg", "/o/a.jpg", 3)])
        first, second = [json.loads(ln) for ln in buf.getvalue().splitlines()]
        self.assertNotIn("sidecars", first)
        self.assertEqual(second["sidecars"], [{"name": "a.jpg", "path": "/o/a.jpg", "size": 3}])
        self.assertEqual(len(second["files"]), 1)


class UpdateTests(unittest.TestCase):
    def setUp(self):
        self.saved = (dl.run_capture, dl.pip_available, dl.find_ytdlp, dl.ytdlp_version, dl.ytdlp_origin)
        self.calls = []

    def tearDown(self):
        dl.run_capture, dl.pip_available, dl.find_ytdlp, dl.ytdlp_version, dl.ytdlp_origin = self.saved

    def stub(self, versions, outputs, pip=True):
        vs, outs = list(versions), list(outputs)
        dl.pip_available = lambda: pip
        dl.find_ytdlp = lambda: ["yt-dlp"]
        dl.ytdlp_version = lambda y: vs.pop(0)
        dl.ytdlp_origin = lambda y: "apt"

        def cap(cmd, timeout):
            self.calls.append(cmd)
            return outs.pop(0)
        dl.run_capture = cap

    def test_ok(self):
        self.stub(["2026.01.01", "2026.10.26"], [(0, "Successfully installed yt-dlp-2026.10.26")])
        rep = Recorder()
        self.assertEqual(dl.run_update(rep, ["yt-dlp"]), 0)
        self.assertEqual(rep.statuses(), ["start", "ok"])
        self.assertIn("--break-system-packages", self.calls[0])

    def test_latest(self):
        self.stub(["2026.10.26", "2026.10.26"], [(0, "Requirement already satisfied: yt-dlp")])
        rep = Recorder()
        self.assertEqual(dl.run_update(rep, ["yt-dlp"]), 0)
        self.assertEqual(rep.statuses(), ["start", "latest"])

    def test_shadowed_by_old_path(self):
        self.stub(["2026.01.01", "2026.01.01"], [(0, "Successfully installed yt-dlp-2026.10.26")])
        rep = Recorder()
        self.assertEqual(dl.run_update(rep, ["yt-dlp"]), 1)
        self.assertEqual(rep.statuses(), ["start", "failed"])

    def test_fail_uses_last_line(self):
        self.stub(["2026.01.01"], [(1, "x\nERROR: Could not find a version\n")])
        rep = Recorder()
        self.assertEqual(dl.run_update(rep, ["yt-dlp"]), 1)
        self.assertEqual(rep.events[-1][2]["msg"], "ERROR: Could not find a version")

    def test_old_pip_retries_without_flag(self):
        self.stub(["2026.01.01", "2026.10.26"], [(2, "no such option: --break-system-packages"), (0, "Successfully installed")])
        rep = Recorder()
        self.assertEqual(dl.run_update(rep, ["yt-dlp"]), 0)
        self.assertEqual(len(self.calls), 2)
        self.assertNotIn("--break-system-packages", self.calls[1])

    def test_no_pip(self):
        self.stub(["2026.01.01"], [], pip=False)
        rep = Recorder()
        self.assertEqual(dl.run_update(rep, ["yt-dlp"]), 1)
        self.assertEqual(rep.statuses(), ["failed"])
        self.assertEqual(self.calls, [])

    def test_maintenance_doctor_without_ytdlp_fails(self):
        class A:
            json = True
            update_ytdlp = False
            doctor = True
            dir = None
        saved_rep = dl.JsonReporter
        rec = Recorder()
        dl.JsonReporter = lambda: rec
        dl.find_ytdlp = lambda: None
        try:
            self.assertEqual(dl.run_maintenance(A, None), 1)
        finally:
            dl.JsonReporter = saved_rep
        data = [e for e in rec.events if e[0] == "doctor"][0][1]
        self.assertIn("no_ytdlp", data["issues"])
        self.assertIsNone(data["ytdlp"])


def run_script(*args, cwd=None, env=None):
    e = dict(os.environ, NO_COLOR="1")
    e.update(env or {})
    res = subprocess.run([sys.executable, SCRIPT, *args], capture_output=True, text=True, cwd=cwd, env=e, timeout=180)
    events = []
    for line in res.stdout.splitlines():
        if line.startswith("{"):
            events.append(json.loads(line))
    return res.returncode, events


def ev(events, name):
    return [e for e in events if e["ev"] == name]


class CliTests(unittest.TestCase):
    @unittest.skipUnless(dl.find_ytdlp(), "yt-dlp tidak ada")
    def test_bad_args_exit_2(self):
        rc, events = run_script("--json", "--subs", "id;rm", "http://127.0.0.1:1/x")
        self.assertEqual((rc, ev(events, "error")[0]["code"]), (2, "bad_subs"))
        rc, events = run_script("--json", "--limit-rate", "2MB", "http://127.0.0.1:1/x")
        self.assertEqual((rc, ev(events, "error")[0]["code"]), (2, "bad_rate"))

    @unittest.skipUnless(dl.find_ytdlp(), "yt-dlp tidak ada")
    def test_doctor_json(self):
        rc, events = run_script("--json", "--doctor")
        self.assertEqual(rc, 0)
        d = ev(events, "doctor")[0]
        for key in ("ytdlp", "ytdlp_method", "ffmpeg", "ffprobe", "mutagen", "python", "video_dir", "audio_dir",
                    "root_bound", "free_mb", "issues"):
            self.assertIn(key, d)
        self.assertEqual(events[0]["ev"], "hello")
        self.assertEqual(events[0]["version"], dl.SCRIPT_VERSION)

    def test_version(self):
        res = subprocess.run([sys.executable, SCRIPT, "--version"], capture_output=True, text=True)
        self.assertEqual(res.stdout.strip(), f"molina_dl {dl.SCRIPT_VERSION} (protokol 1)")


class SubsRateLimitFallbackTests(unittest.TestCase):
    """Subtitle kena HTTP 429 -> video tetap diunduh tanpa subtitle (yt-dlp palsu, tanpa jaringan)."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.fake = os.path.join(self.tmp, "fake_ytdlp.py")
        with open(self.fake, "w") as f:
            f.write("import sys, os\n"
                    "a = sys.argv[1:]\n"
                    "if '--write-subs' in a:\n"
                    "    print(\"ERROR: Unable to download video subtitles for 'en': HTTP Error 429: Too Many Requests\")\n"
                    "    sys.exit(1)\n"
                    "p = os.path.join(%r, 'v.mp4'); open(p, 'wb').write(b'x' * 10)\n"
                    "print('MDLFILE|' + p)\n" % self.tmp)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _events(self):
        class R(Recorder):
            def item(s, i, n, url): s.events.append(("item", i))
            def info(s, i, info): s.events.append(("info", i))
            def error(s, i, code, msg): s.events.append(("error", code))
            def done(s, i, outdir, files, existed, sidecars=None): s.events.append(("done", i))
            def progress(s, *a, **k): pass
            def stage(s, *a, **k): pass
            def note(s, *a, **k): pass
            def finish_line(s): pass
        return R()

    def test_classify_429(self):
        self.assertEqual(dl.classify_error("Unable to download video subtitles for 'en': HTTP Error 429: Too Many Requests"), "rate_limited")
        self.assertTrue(dl.is_subs_error("ERROR: Unable to download video subtitles for 'en': HTTP Error 429"))
        self.assertFalse(dl.is_subs_error("HTTP Error 429 saat mengambil video"))

    def test_fallback_without_subs(self):
        rep = self._events()
        spec = {"mode": "video", "ext": "mp4", "subs": ["en"], "subs_mode": "embed", "compat": False}
        out = dl.run_item([sys.executable, self.fake], rep, 1, 1, "http://x/1", spec, self.tmp, False, info={"id": "1"})
        self.assertEqual(out, "ok")
        self.assertIn(("warn", "subs_failed"), rep.events)
        self.assertEqual(len([e for e in rep.events if e[0] == "info"]), 1)
        self.assertEqual(len([e for e in rep.events if e[0] == "item"]), 1)
        self.assertNotIn("error", [e[0] for e in rep.events])
        self.assertIn(("done", 1), rep.events)


class ShieldProxyEnvTests(unittest.TestCase):
    PX = "http" + "://molina:abc123@127.0.0.1:"

    def setUp(self):
        self.saved = dict(os.environ)

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.saved)

    def test_tanpa_env_tidak_mengubah_apa_pun(self):
        os.environ.pop("MOLINA_SHIELD_PROXY", None)
        os.environ.pop("MOLINA_SHIELD_PORT", None)
        os.environ["no_proxy"] = "example.com"
        self.assertFalse(dl.apply_shield_proxy())
        self.assertEqual(os.environ["no_proxy"], "example.com")

    def test_env_valid_dipasang_dan_no_proxy_dibuang(self):
        os.environ["MOLINA_SHIELD_PORT"] = "4567"
        os.environ["MOLINA_SHIELD_PROXY"] = self.PX + "4567"
        os.environ["no_proxy"] = "internal.lan"
        os.environ["NO_PROXY"] = "internal.lan"
        self.assertTrue(dl.apply_shield_proxy())
        for k in ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY"):
            self.assertEqual(os.environ[k], self.PX + "4567")
        self.assertNotIn("no_proxy", os.environ)
        self.assertNotIn("NO_PROXY", os.environ)

    def test_env_tidak_konsisten_atau_bukan_loopback_ditolak(self):
        for px, port in ((self.PX + "4567", "9999"), ("http" + "://u:p@10.0.0.5:4567", "4567"),
                         ("http" + "://u:p@127.0.0.1.evil.com:4567", "4567"), (self.PX + "4567", "abc")):
            os.environ["MOLINA_SHIELD_PROXY"], os.environ["MOLINA_SHIELD_PORT"] = px, port
            os.environ.pop("http_proxy", None)
            self.assertFalse(dl.apply_shield_proxy(), px)
            self.assertNotIn("http_proxy", os.environ)

    def _connect(self, port, env):
        code = dl.GUARD_SRC + "\ninstall()\nimport socket\ns = socket.socket()\ns.settimeout(3)\ntry:\n    s.connect(('127.0.0.1', %d))\n    print('OK')\nexcept OSError as e:\n    print('ERR', e)\n" % port
        e = dict(os.environ)
        e.pop("MOLINA_NETGUARD_ALLOW_LOOPBACK", None)
        e.pop("MOLINA_SHIELD_PORT", None)
        e.update(env)
        return subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, env=e, timeout=30).stdout.strip()

    def test_penjaga_hanya_mengizinkan_port_shield(self):
        import socket
        a, b = socket.socket(), socket.socket()
        for s in (a, b):
            s.bind(("127.0.0.1", 0)); s.listen(5)
        pa, pb = a.getsockname()[1], b.getsockname()[1]
        try:
            self.assertEqual(self._connect(pa, {"MOLINA_SHIELD_PORT": str(pa)}), "OK")
            self.assertIn("MOLINA-NETGUARD", self._connect(pb, {"MOLINA_SHIELD_PORT": str(pa)}))
            self.assertIn("MOLINA-NETGUARD", self._connect(pa, {}))
        finally:
            a.close(); b.close()


class NetGuardTests(unittest.TestCase):
    def ns(self):
        ns = {}
        exec(dl.GUARD_SRC, ns)
        return ns

    def test_classify_sama_dengan_urlpolicy(self):
        c = self.ns()["classify"]
        for ip in ("127.0.0.1", "0.0.0.0", "169.254.169.254", "224.0.0.1", "255.255.255.255", "::1", "::",
                   "::ffff:127.0.0.1", "::ffff:7f00:1", "64:ff9b::7f00:1", "2002:7f00:1::1", "fe80::1", "ff02::1", "100::1", "not-an-ip"):
            self.assertEqual(c(ip), "loopback", ip)
        for ip in ("10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "100.64.0.1", "198.18.0.1", "192.0.0.8",
                   "fd00::1", "::ffff:192.168.1.5", "64:ff9b::a00:1"):
            self.assertEqual(c(ip), "private", ip)
            self.assertIsNone(c(ip, True), ip)
        for ip in ("8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1", "100.63.0.1", "2606:4700:4700::1111", "::ffff:8.8.8.8"):
            self.assertIsNone(c(ip), ip)
        self.assertEqual(c("127.0.0.1", True), "loopback")

    def _connect(self, host, port, env=None):
        code = dl.GUARD_SRC + "\ninstall()\nimport socket\ns = socket.socket()\ns.settimeout(3)\ntry:\n    s.connect((%r, %d))\n    print('OK')\nexcept OSError as e:\n    print('ERR', e)\n" % (host, port)
        e = dict(os.environ)
        e.pop("MOLINA_NETGUARD_ALLOW_LOOPBACK", None)
        e.update(env or {})
        return subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, env=e, timeout=30).stdout.strip()

    def test_connect_ke_loopback_ditolak_dan_bisa_dibuka_untuk_uji(self):
        import socket
        srv = socket.socket()
        srv.bind(("127.0.0.1", 0))
        srv.listen(5)
        port = srv.getsockname()[1]
        try:
            self.assertIn("MOLINA-NETGUARD", self._connect("127.0.0.1", port))
            self.assertIn("MOLINA-NETGUARD", self._connect("localhost", port))
            self.assertEqual(self._connect("127.0.0.1", port, {"MOLINA_NETGUARD_ALLOW_LOOPBACK": "1"}), "OK")
        finally:
            srv.close()

    def test_guarded_membungkus_skrip_dan_modul(self):
        fake = os.path.join(tempfile.mkdtemp(prefix="mdl-ng-"), "yt-dlp")
        with open(fake, "w") as f:
            f.write("#!%s\nimport sys\nprint('ARGS', sys.argv[1:])\n" % sys.executable)
        os.chmod(fake, 0o755)
        cmd = dl.guarded([fake])
        self.assertEqual(cmd[0], sys.executable)
        self.assertEqual(cmd[1], "-c")
        self.assertEqual(cmd[3], fake)
        out = subprocess.run(cmd + ["--x", "y"], capture_output=True, text=True, timeout=30).stdout
        self.assertIn("ARGS ['--x', 'y']", out)
        mod = dl.guarded([sys.executable, "-m", "yt_dlp", "--z"])
        self.assertEqual(mod[3], "-m:yt_dlp")
        self.assertEqual(mod[-1], "--z")
        self.assertEqual(dl.guarded(["/tidak/ada/yt-dlp"]), ["/tidak/ada/yt-dlp"])

    def test_classify_error_netguard(self):
        self.assertEqual(dl.classify_error("ERROR: [MOLINA-NETGUARD] koneksi ke alamat 127.0.0.1 ditolak (loopback)"), "blocked_local")


class SnapshotTests(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp(prefix="mdl-snap-")

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    def touch(self, rel):
        p = os.path.join(self.d, *rel.split("/"))
        os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, "wb").write(b"x")
        return p

    def test_depth_one_is_flat(self):
        self.touch("a.mp4")
        self.touch("sub/b.mp4")
        self.assertEqual(dl.snapshot(self.d, depth=1), {"a.mp4"})

    def test_default_sees_one_subfolder_level(self):
        for rel in ("a.mp4", "Kanal/b.mp4", "Kanal/deep/c.mp4", ".hidden/d.mp4"):
            self.touch(rel)
        self.assertEqual(dl.snapshot(self.d), {"a.mp4", "Kanal/b.mp4"})

    def test_missing_folder_is_empty(self):
        self.assertEqual(dl.snapshot(os.path.join(self.d, "tidak-ada")), set())

    def test_rel_name_matches_snapshot_keys(self):
        p = self.touch("Kanal/v.mp4")
        self.assertEqual(dl.rel_name(p, self.d), "Kanal/v.mp4")
        self.assertIn(dl.rel_name(p, self.d), dl.snapshot(self.d))


class ChannelFolderTests(unittest.TestCase):
    def test_sanitize(self):
        s = dl.sanitize_folder_name
        self.assertEqual(s("Molina TV"), "Molina TV")
        self.assertEqual(s("A/B\\C:D*E?"), "A_B_C_D_E_")
        self.assertEqual(s("  banyak   spasi  "), "banyak spasi")
        self.assertEqual(s("..tersembunyi."), "tersembunyi")
        self.assertEqual(s(".."), dl.NO_CHANNEL)
        self.assertEqual(s(""), dl.NO_CHANNEL)
        self.assertEqual(s(None), dl.NO_CHANNEL)
        self.assertEqual(s("???"), dl.NO_CHANNEL)
        self.assertEqual(s("CON"), "_CON")
        self.assertEqual(s("lpt1.txt"), "_lpt1.txt")
        self.assertEqual(s("a\u200bb\u202ec"), "abc")  # zero-width dan penanda RTL dibuang
        self.assertEqual(s("-opsi"), "-opsi")

    def test_sanitize_limits_bytes_without_breaking_characters(self):
        out = dl.sanitize_folder_name("é" * 200)
        self.assertLessEqual(len(out.encode("utf-8")), dl.FOLDER_NAME_MAX_BYTES)
        self.assertEqual(out, "é" * (dl.FOLDER_NAME_MAX_BYTES // 2))
        self.assertEqual(dl.sanitize_folder_name("x" * 300), "x" * dl.FOLDER_NAME_MAX_BYTES)

    def test_channel_dir_prefers_channel_then_uploader(self):
        self.assertEqual(dl.channel_dir("/o", {"channel": "C", "uploader": "U"}), os.path.join("/o", "C"))
        self.assertEqual(dl.channel_dir("/o", {"uploader": "U"}), os.path.join("/o", "U"))
        self.assertEqual(dl.channel_dir("/o", {}), os.path.join("/o", dl.NO_CHANNEL))
        self.assertEqual(dl.channel_dir("/o", None), os.path.join("/o", dl.NO_CHANNEL))
        # nama kanal berbahaya tidak boleh keluar dari folder simpan
        self.assertEqual(os.path.dirname(dl.channel_dir("/o", {"channel": "../../etc"})), "/o")

    def test_cli_flag_and_env(self):
        self.assertFalse(dl.parse_args(["http://x/1"]).per_channel)
        self.assertTrue(dl.parse_args(["--per-channel", "http://x/1"]).per_channel)
        old = os.environ.get("MOLINA_DL_PER_CHANNEL")
        try:
            for val, want in (("1", True), ("true", True), ("0", False), ("", False)):
                os.environ["MOLINA_DL_PER_CHANNEL"] = val
                self.assertEqual(dl.env_flag("MOLINA_DL_PER_CHANNEL"), want, val)
        finally:
            if old is None:
                os.environ.pop("MOLINA_DL_PER_CHANNEL", None)
            else:
                os.environ["MOLINA_DL_PER_CHANNEL"] = old


FAKE_YTDLP = """\
import os, sys
a = sys.argv[1:]
d = os.path.dirname(a[a.index('-o') + 1])
mode = os.environ.get('FAKE_MODE', 'print')
target = os.path.join(d, 'sub') if mode == 'silent_sub' else d
os.makedirs(target, exist_ok=True)
p = os.path.join(target, 'v [1].mp4')
open(p, 'wb').write(b'x' * 10)
if mode == 'print':
    print('MDLFILE|' + p)
"""


class PerChannelRunTests(unittest.TestCase):
    """run_item dengan yt-dlp palsu: folder per kanal dan deteksi berkas di subfolder (tanpa jaringan)."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="mdl-pc-")
        self.fake = os.path.join(self.tmp, "fake_ytdlp.py")
        with open(self.fake, "w") as f:
            f.write(FAKE_YTDLP)
        self.out = os.path.join(self.tmp, "out")
        os.makedirs(self.out)
        self._mode = os.environ.get("FAKE_MODE")
        self.addCleanup(self._restore_mode)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _restore_mode(self):
        if self._mode is None:
            os.environ.pop("FAKE_MODE", None)
        else:
            os.environ["FAKE_MODE"] = self._mode

    def run_one(self, spec_extra=None, info=None, mode="print"):
        os.environ["FAKE_MODE"] = mode

        class Cap(Recorder):
            def __init__(s):
                super().__init__()
                s.done_args = None
                s.warns = []

            def warn(s, code, msg):
                s.warns.append(code)

            def done(s, i, outdir, files, existed, sidecars=None):
                s.done_args = (outdir, files, existed)

            def item(s, *a): pass
            def info(s, *a): pass
            def error(s, i, code, msg): s.events.append(("error", code))
            def progress(s, *a, **k): pass
            def stage(s, *a, **k): pass
            def note(s, *a, **k): pass
            def finish_line(s): pass

        rep = Cap()
        spec = {"mode": "video", "ext": "mp4", "compat": False}
        spec.update(spec_extra or {})
        status = dl.run_item([sys.executable, self.fake], rep, 1, 1, "http://x/1", spec, self.out, False,
                             info=info if info is not None else {"id": "1", "channel": "Kanal: A/B"})
        return status, rep

    def test_per_channel_saves_in_channel_folder(self):
        status, rep = self.run_one({"per_channel": True})
        folder = os.path.join(self.out, "Kanal_ A_B")
        self.assertEqual(status, "ok")
        self.assertEqual(rep.done_args[0], folder)
        self.assertEqual([n for n, _p, _s in rep.done_args[1]], ["v [1].mp4"])
        self.assertFalse(rep.done_args[2])
        self.assertTrue(os.path.isfile(os.path.join(folder, "v [1].mp4")))
        self.assertFalse(os.path.exists(os.path.join(self.out, "v [1].mp4")))

    def test_flat_mode_unchanged(self):
        status, rep = self.run_one()
        self.assertEqual((status, rep.done_args[0]), ("ok", self.out))
        self.assertTrue(os.path.isfile(os.path.join(self.out, "v [1].mp4")))

    def test_per_channel_without_channel_uses_fallback_name(self):
        _s, rep = self.run_one({"per_channel": True}, info={"id": "1"})
        self.assertEqual(rep.done_args[0], os.path.join(self.out, dl.NO_CHANNEL))

    def test_existing_file_detected_in_channel_folder(self):
        folder = os.path.join(self.out, "Kanal_ A_B")
        os.makedirs(folder)
        open(os.path.join(folder, "v [1].mp4"), "wb").write(b"lama")
        _s, rep = self.run_one({"per_channel": True})
        self.assertTrue(rep.done_args[2])  # existed

    def test_fallback_finds_file_created_in_subfolder(self):
        # Regresi: dulu snapshot hanya satu folder -> hasil kosong dan dilaporkan "sudah ada".
        status, rep = self.run_one(mode="silent_sub")
        self.assertEqual(status, "ok")
        self.assertEqual([n for n, _p, _s in rep.done_args[1]], ["v [1].mp4"])
        self.assertFalse(rep.done_args[2])

    def test_fallback_ignores_preexisting_files_in_subfolder(self):
        old = os.path.join(self.out, "sub")
        os.makedirs(old)
        open(os.path.join(old, "lama.mp4"), "wb").write(b"x")
        _s, rep = self.run_one(mode="silent_sub")
        self.assertEqual([n for n, _p, _s in rep.done_args[1]], ["v [1].mp4"])

    def test_unwritable_channel_folder_falls_back_with_warning(self):
        saved = dl.ensure_dir
        dl.ensure_dir = lambda p: False
        try:
            status, rep = self.run_one({"per_channel": True})
        finally:
            dl.ensure_dir = saved
        self.assertEqual(status, "ok")
        self.assertIn("channel_dir_failed", rep.warns)
        self.assertEqual(rep.done_args[0], self.out)

    # ---- dedup berdasarkan id: mode per kanal tidak mengunduh ulang video yang sama dari folder lain
    def put_old(self, rel, data=b"lama"):
        p = os.path.join(self.out, *rel.split("/"))
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "wb") as f:
            f.write(data)
        return p

    def test_find_existing_cases(self):
        spec = {"mode": "video", "ext": "mp4"}
        info = {"id": "abc"}
        self.assertIsNone(dl.find_existing(self.out, info, spec))
        flat = self.put_old("Judul [abc].mp4")
        self.assertEqual(dl.find_existing(self.out, info, spec)[1], flat)
        self.put_old("Kanal X/Lain [zzz].mp4")
        self.assertIsNone(dl.find_existing(self.out, {"id": "qqq"}, spec))              # id lain
        self.assertIsNone(dl.find_existing(self.out, info, {"mode": "video", "ext": "mkv"}))  # ekstensi lain
        self.assertIsNone(dl.find_existing(self.out, info, {"mode": "audio", "afmt": "mp3"}))  # mode lain
        self.assertIsNone(dl.find_existing(self.out, None, spec))
        self.assertIsNone(dl.find_existing(self.out, {"id": ""}, spec))
        self.put_old("Judul [abc].mp4.part")
        self.assertEqual(dl.find_existing(self.out, info, spec)[1], flat)                # .part tidak dihitung
        self.assertEqual(dl.find_existing(self.out, {"id": "ABC"}, spec)[1], flat)       # id tidak peka huruf besar-kecil

    def test_find_existing_prefers_newest_copy(self):
        old = self.put_old("A/V [abc].mp4")
        new = self.put_old("B/V [abc].mp4")
        os.utime(old, (1_000_000_000, 1_000_000_000))
        os.utime(new, (1_700_000_000, 1_700_000_000))
        self.assertEqual(dl.find_existing(self.out, {"id": "abc"}, {"mode": "video", "ext": "mp4"})[1], new)

    def test_per_channel_skips_download_when_in_flat_folder(self):
        flat = self.put_old("Judul [1].mp4")
        status, rep = self.run_one({"per_channel": True})
        self.assertEqual(status, "ok")
        outdir, files, existed = rep.done_args
        self.assertTrue(existed)
        self.assertEqual((outdir, [p for _n, p, _s in files]), (self.out, [flat]))
        self.assertFalse(os.path.exists(os.path.join(self.out, "Kanal_ A_B")))   # folder kanal kosong tidak dibuat
        self.assertEqual(os.listdir(self.out), ["Judul [1].mp4"])               # yt-dlp palsu tidak dijalankan

    def test_per_channel_skips_download_when_in_other_channel_folder(self):
        old = self.put_old("Nama Kanal Lama/Judul [1].mp4")
        _s, rep = self.run_one({"per_channel": True})
        self.assertEqual((rep.done_args[0], rep.done_args[2]), (os.path.dirname(old), True))
        self.assertFalse(os.path.exists(os.path.join(self.out, "Kanal_ A_B")))

    def test_flat_mode_behaviour_unchanged(self):
        self.put_old("Sub/Judul [1].mp4")
        _s, rep = self.run_one()   # tanpa per_channel: yt-dlp tetap jalan
        self.assertFalse(rep.done_args[2])
        self.assertTrue(os.path.isfile(os.path.join(self.out, "v [1].mp4")))

    def test_different_extension_still_downloads(self):
        self.put_old("Judul [1].mkv")
        _s, rep = self.run_one({"per_channel": True})
        self.assertFalse(rep.done_args[2])
        self.assertTrue(os.path.isfile(os.path.join(self.out, "Kanal_ A_B", "v [1].mp4")))


class LyricsFromDescriptionTests(unittest.TestCase):
    PLAIN = ("Maafkan Aku - Enda Ungu\n\nStream di semua platform: https://x.test/a\n\nLirik:\n"
             "Baris satu lagu ini\nBaris dua lagu ini\nBaris tiga lagu ini\nBaris empat lagu ini\n\n"
             "[Chorus]\nAku akan follow you sampai akhir\nBaris enam lagu ini\nBaris tujuh lagu ini\nBaris delapan lagu ini\n\n"
             "Follow us on Instagram @x\nSubscribe!\n")

    def test_plain_after_marker(self):
        t = dl.lyrics_from_description(self.PLAIN)
        self.assertIsNotNone(t)
        real = [x for x in t.splitlines() if x]
        self.assertEqual(len(real), 8)
        self.assertIn("Aku akan follow you sampai akhir", t)  # kata 'follow' dalam lirik tidak memutus blok
        for bad in ("Chorus", "Instagram", "https", "Subscribe", "Stream di"):
            self.assertNotIn(bad, t)

    def test_timed_description_becomes_lrc(self):
        d = "Lirik\n" + "\n".join(f"{i * 5 // 60:02d}:{i * 5 % 60:02d} baris {i}" for i in range(2, 14))
        t = dl.lyrics_from_description(d)
        self.assertTrue(t.startswith("[00:10.00]baris 2\n"))
        self.assertEqual(len(t.splitlines()), 12)

    def test_chapters_are_not_lyrics(self):
        d = "\n".join(f"{i * 3:02d}:{(i * 17) % 60:02d} Lagu nomor {i}" for i in range(12))
        self.assertIsNone(dl.lyrics_from_description(d))

    def test_rejects_short_credits_prose_and_empty(self):
        self.assertIsNone(dl.lyrics_from_description(None))
        self.assertIsNone(dl.lyrics_from_description("Lirik:\nsatu\ndua\ntiga"))
        credits = "\n".join(f"Peran {i}: Nama Orang {i}" for i in range(12))
        self.assertIsNone(dl.lyrics_from_description("Lirik\n" + credits))
        prose = "\n".join(f"Ini kalimat deskripsi biasa nomor {i}." for i in range(14))
        self.assertIsNone(dl.lyrics_from_description(prose))

    def test_bare_block_needs_more_lines(self):
        bare8 = "\n".join(f"Baris lirik ke {i} lagu" for i in range(8))
        bare12 = "\n".join(f"Baris lirik ke {i} lagu" for i in range(12))
        self.assertIsNone(dl.lyrics_from_description("Judul\n\n" + bare8))
        self.assertIsNotNone(dl.lyrics_from_description("Judul\n\n" + bare12))

    def test_save_lyrics_writes_once_and_never_overwrites(self):
        d = tempfile.mkdtemp()
        try:
            media = os.path.join(d, "a [id].mp3")
            open(media, "wb").write(b"x")
            rep = Recorder()
            out = dl.save_lyrics(rep, {"description": self.PLAIN}, [("a [id].mp3", media, 1)])
            self.assertEqual(out, os.path.join(d, "a [id].lrc"))
            self.assertIn("Baris satu lagu ini", open(out, encoding="utf-8").read())
            open(out, "w", encoding="utf-8").write("suntingan saya")
            self.assertIsNone(dl.save_lyrics(rep, {"description": self.PLAIN}, [("a [id].mp3", media, 1)]))
            self.assertEqual(open(out, encoding="utf-8").read(), "suntingan saya")
            self.assertFalse([e for e in rep.events if e[0] == "warn"])
        finally:
            shutil.rmtree(d, ignore_errors=True)

    def test_save_lyrics_warns_when_absent_and_skips_non_media(self):
        d = tempfile.mkdtemp()
        try:
            media = os.path.join(d, "b.mp4")
            open(media, "wb").write(b"x")
            rep = Recorder()
            self.assertIsNone(dl.save_lyrics(rep, {"description": "tanpa lirik"}, [("b.mp4", media, 1)]))
            self.assertEqual([e[1] for e in rep.events if e[0] == "warn"], ["no_lyrics"])
            self.assertFalse(os.path.exists(os.path.join(d, "b.lrc")))
            sub_only = os.path.join(d, "b.srt")
            open(sub_only, "w").write("x")
            self.assertIsNone(dl.save_lyrics(Recorder(), {"description": self.PLAIN}, [("b.srt", sub_only, 1)]))
        finally:
            shutil.rmtree(d, ignore_errors=True)

    def test_cli_flag_parsed(self):
        self.assertTrue(dl.parse_args(["--json", "--lyrics", "http://x/1"]).lyrics)
        self.assertFalse(dl.parse_args(["--json", "http://x/1"]).lyrics)


@unittest.skipUnless(HAVE_TOOLS, "butuh yt-dlp, ffmpeg, ffprobe")
class IntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.www = tempfile.mkdtemp(prefix="mdl-www-")
        cls.out = tempfile.mkdtemp(prefix="mdl-out-")

        def ff(*a):
            subprocess.run(["ffmpeg", "-y", "-v", "error", *a], check=True)
        w = cls.www
        ff("-f", "lavfi", "-i", "testsrc=size=640x360:rate=10", "-f", "lavfi", "-i", "sine=frequency=440", "-t", "2",
           "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", os.path.join(w, "clip.mp4"))
        ff("-f", "lavfi", "-i", "testsrc=size=640x360:rate=10", "-f", "lavfi", "-i", "sine=frequency=440", "-t", "2",
           "-c:v", "mpeg4", "-c:a", "mp3", os.path.join(w, "clip_old.mp4"))
        ff("-f", "lavfi", "-i", "testsrc=size=800x450", "-frames:v", "1", os.path.join(w, "thumb.jpg"))
        for lang, text in (("id", "Halo dunia"), ("en", "Hello world")):
            with open(os.path.join(w, f"sub_{lang}.vtt"), "w") as f:
                f.write(f"WEBVTT\n\n00:00:00.000 --> 00:00:01.500\n{text}\n")
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(SimpleHTTPRequestHandler, directory=w))
        cls.server.RequestHandlerClass.log_message = lambda *a, **k: None
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

        def page(name, clip, subs=True):
            tracks = "".join(f'<track kind="subtitles" srclang="{l}" src="{cls.base}/sub_{l}.vtt">' for l in ("id", "en")) if subs else ""
            with open(os.path.join(w, name), "w") as f:
                f.write(f'<html><head><title>Uji</title><meta property="og:image" content="{cls.base}/thumb.jpg"></head>'
                        f'<body><video controls><source src="{cls.base}/{clip}" type="video/mp4">{tracks}</video></body></html>')
        page("subs.html", "clip.mp4")
        page("old.html", "clip_old.mp4")
        page("nosubs.html", "clip.mp4", subs=False)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        shutil.rmtree(cls.www, ignore_errors=True)
        shutil.rmtree(cls.out, ignore_errors=True)

    def outdir(self, name):
        d = os.path.join(self.out, name)
        shutil.rmtree(d, ignore_errors=True)
        return d

    def streams(self, path, kind=None):
        sel = ["-select_streams", kind] if kind else []
        res = subprocess.run(["ffprobe", "-v", "error", *sel, "-show_entries", "stream=codec_name,codec_type:stream_tags=language",
                              "-of", "json", path], capture_output=True, text=True)
        return json.loads(res.stdout)["streams"]

    def test_embed_subs_mp4(self):
        d = self.outdir("embed")
        rc, events = run_script("--json", "--subs", "id,en", "--dir", d, f"{self.base}/subs.html")
        self.assertEqual(rc, 0)
        self.assertIn("subs", [e["stage"] for e in ev(events, "stage")])
        path = ev(events, "done")[0]["files"][0]["path"]
        subs = self.streams(path, "s")
        self.assertEqual([s["codec_name"] for s in subs], ["mov_text", "mov_text"])
        self.assertEqual(os.listdir(d), [os.path.basename(path)])  # tanpa sisa berkas subtitle

    def test_netguard_blokir_loopback(self):
        d = self.outdir("netguard")
        rc, events = run_script("--json", "--dir", d, f"{self.base}/subs.html", env={"MOLINA_NETGUARD_ALLOW_LOOPBACK": "0"})
        self.assertNotEqual(rc, 0)
        errs = ev(events, "error")
        self.assertTrue(errs and errs[0].get("code") == "blocked_local", errs)
        self.assertFalse(os.path.isdir(d) and os.listdir(d))

    def test_file_mode_lists_sidecar(self):
        d = self.outdir("file")
        rc, events = run_script("--json", "--subs", "id", "--subs-mode", "file", "--dir", d, f"{self.base}/subs.html")
        self.assertEqual(rc, 0)
        names = [f["name"] for f in ev(events, "done")[0]["files"]]
        self.assertTrue(any(n.endswith(".mp4") for n in names))
        self.assertTrue(any(n.endswith(".id.srt") for n in names), names)
        self.assertEqual(self.streams(os.path.join(d, [n for n in names if n.endswith(".mp4")][0]), "s"), [])

    def test_recode_keeps_subtitles(self):
        d = self.outdir("recode")
        rc, events = run_script("--json", "--subs", "en", "--dir", d, f"{self.base}/old.html")
        self.assertEqual(rc, 0)
        self.assertIn("recode", [e["stage"] for e in ev(events, "stage")])
        path = ev(events, "done")[0]["files"][0]["path"]
        self.assertEqual([s["codec_name"] for s in self.streams(path)], ["h264", "aac", "mov_text"])

    def test_missing_subtitles_warn(self):
        d = self.outdir("nosubs")
        rc, events = run_script("--json", "--subs", "id", "--dir", d, f"{self.base}/nosubs.html")
        self.assertEqual(rc, 0)
        self.assertIn("no_subs", [w["code"] for w in ev(events, "warn")])

    def test_audio_mp3_has_cover_and_title(self):
        d = self.outdir("mp3")
        rc, events = run_script("--json", "--mode", "audio", "--audio-format", "mp3", "--dir", d, f"{self.base}/subs.html")
        self.assertEqual(rc, 0)
        self.assertIn("cover", [e["stage"] for e in ev(events, "stage")])
        path = ev(events, "done")[0]["files"][0]["path"]
        kinds = [(s["codec_type"], s["codec_name"]) for s in self.streams(path)]
        self.assertIn(("video", "mjpeg"), kinds)  # sampul
        tags = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format_tags=title", "-of", "csv=p=0", path],
                              capture_output=True, text=True).stdout.strip()
        self.assertTrue(tags.startswith("Uji"), tags)

    def test_no_cover_flag(self):
        d = self.outdir("nocover")
        rc, events = run_script("--json", "--mode", "audio", "--no-cover", "--dir", d, f"{self.base}/subs.html")
        self.assertEqual(rc, 0)
        self.assertNotIn("cover", [e["stage"] for e in ev(events, "stage")])
        path = ev(events, "done")[0]["files"][0]["path"]
        self.assertEqual([s["codec_type"] for s in self.streams(path)], ["audio"])

    def test_m4a_without_mutagen_warns_but_downloads(self):
        if dl.has_mutagen() or shutil.which("AtomicParsley"):
            self.skipTest("mutagen/AtomicParsley tersedia")
        d = self.outdir("m4a")
        rc, events = run_script("--json", "--mode", "audio", "--audio-format", "m4a", "--dir", d, f"{self.base}/subs.html")
        self.assertEqual(rc, 0)
        self.assertIn("no_cover", [w["code"] for w in ev(events, "warn")])
        self.assertEqual(len(ev(events, "done")[0]["files"]), 1)

    def test_limit_rate_accepted(self):
        d = self.outdir("rate")
        rc, events = run_script("--json", "--limit-rate", "5M", "--dir", d, f"{self.base}/nosubs.html")
        self.assertEqual(rc, 0)
        self.assertEqual(ev(events, "summary")[0]["ok"], 1)

    def test_subs_ignored_for_audio(self):
        d = self.outdir("audiosubs")
        rc, events = run_script("--json", "--mode", "audio", "--subs", "id", "--dir", d, f"{self.base}/subs.html")
        self.assertEqual(rc, 0)
        self.assertIn("subs_ignored", [w["code"] for w in ev(events, "warn")])

    def test_default_has_no_sidecar_files(self):
        d = self.outdir("nosidecar")
        rc, events = run_script("--json", "--dir", d, f"{self.base}/nosubs.html")
        self.assertEqual(rc, 0)
        done = ev(events, "done")[0]
        self.assertNotIn("sidecars", done)
        self.assertEqual(os.listdir(d), [done["files"][0]["name"]])  # kontrak lama: folder tanpa sisa berkas

    def test_sidecar_writes_poster_and_slim_info(self):
        d = self.outdir("sidecar")
        rc, events = run_script("--json", "--sidecar", "--dir", d, f"{self.base}/nosubs.html")
        self.assertEqual(rc, 0)
        done = ev(events, "done")[0]
        self.assertEqual(len(done["files"]), 1)  # poster/metadata bukan hasil unduhan
        mp4 = done["files"][0]["name"]
        base = os.path.splitext(mp4)[0]
        self.assertEqual(sorted(s["name"] for s in done["sidecars"]), sorted([base + ".jpg", base + ".info.json"]))
        self.assertEqual(sorted(os.listdir(d)), sorted([mp4, base + ".jpg", base + ".info.json"]))
        with open(os.path.join(d, base + ".info.json"), encoding="utf-8") as f:
            info = json.load(f)
        self.assertEqual(info["_molina_slim"], 1)
        self.assertNotIn("formats", info)
        sizes = {s["name"]: s["size"] for s in done["sidecars"]}
        self.assertEqual(sizes[base + ".info.json"], os.path.getsize(os.path.join(d, base + ".info.json")))
        self.assertNotIn("no_poster", [w["code"] for w in ev(events, "warn")])

    def test_sidecar_survives_recode(self):
        d = self.outdir("sidecarrecode")
        rc, events = run_script("--json", "--sidecar", "--dir", d, f"{self.base}/old.html")
        self.assertEqual(rc, 0)
        done = ev(events, "done")[0]
        self.assertTrue(os.path.isfile(done["files"][0]["path"]))
        self.assertEqual(len(done["sidecars"]), 2)
        for s in done["sidecars"]:
            self.assertTrue(os.path.isfile(s["path"]), s)

    def test_sidecar_ignored_for_audio(self):
        d = self.outdir("audiosidecar")
        rc, events = run_script("--json", "--mode", "audio", "--sidecar", "--dir", d, f"{self.base}/nosubs.html")
        self.assertEqual(rc, 0)
        self.assertIn("sidecar_ignored", [w["code"] for w in ev(events, "warn")])
        self.assertNotIn("sidecars", ev(events, "done")[0])
        self.assertFalse([n for n in os.listdir(d) if n.endswith(".info.json")])


class SslRepairTests(unittest.TestCase):
    SSL_ERR = ("ERROR: [youtube] x: Unable to download API page: [SSL: CERTIFICATE_VERIFY_FAILED] certificate verify failed: "
               "unable to get local issuer certificate (_ssl.c:1029)")

    class R:
        def __init__(self, rc, out, err):
            self.returncode, self.stdout, self.stderr = rc, out, err

    def run_fetch(self, results, repair):
        calls = []
        real_run, real_repair = dl.subprocess.run, dl.repair_ca
        dl.subprocess.run = lambda *a, **k: (calls.append(1), results.pop(0))[1]
        dl.repair_ca = lambda: repair
        try:
            return dl.fetch_info(["yt-dlp"], "https://x.test/v"), len(calls)
        finally:
            dl.subprocess.run, dl.repair_ca = real_run, real_repair

    def test_detect(self):
        self.assertTrue(dl.is_ssl_error(self.SSL_ERR))
        self.assertFalse(dl.is_ssl_error("ERROR: HTTP Error 404: Not Found"))

    def test_classify(self):
        self.assertEqual(dl.classify_error(self.SSL_ERR), "ssl_cert")
        self.assertEqual(dl.classify_error("Connection reset by peer"), "network")

    def test_fetch_info_repairs_then_retries(self):
        (info, err), n = self.run_fetch([self.R(1, "", self.SSL_ERR), self.R(0, '{"id": "a", "title": "t"}', "")], True)
        self.assertIsNone(err)
        self.assertEqual(info["id"], "a")
        self.assertEqual(n, 2)

    def test_doctor_flags_missing_ca(self):
        real = (dl.ca_bundle_ok, dl.ytdlp_version, dl.ytdlp_origin)
        d = tempfile.mkdtemp()
        dl.ytdlp_version = lambda y: None
        dl.ytdlp_origin = lambda y: None
        try:
            dl.ca_bundle_ok = lambda: False
            self.assertIn("no_ca", dl.collect_doctor(["yt-dlp"], d)["issues"])
            dl.ca_bundle_ok = lambda: True
            self.assertNotIn("no_ca", dl.collect_doctor(["yt-dlp"], d)["issues"])
        finally:
            dl.ca_bundle_ok, dl.ytdlp_version, dl.ytdlp_origin = real
            shutil.rmtree(d, ignore_errors=True)

    def test_fetch_info_reports_ssl_code_when_repair_fails(self):
        (info, err), n = self.run_fetch([self.R(1, "", self.SSL_ERR)], False)
        self.assertIsNone(info)
        self.assertEqual(err[0], "ssl_cert")
        self.assertEqual(n, 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)

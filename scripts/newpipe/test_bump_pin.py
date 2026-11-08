#!/usr/bin/env python3
"""Uji bump_pin.py (NewPipe) pada SALINAN repo; repo asli tidak disentuh [NEWPIPE-BUMP-TEST].
Jalankan: python3 scripts/newpipe/test_bump_pin.py. Hanya Python, tanpa jaringan/Gradle (memakai --resolved tiruan)."""
import os, re, shutil, subprocess, sys, tempfile, unittest

SRC = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
GRADLE = "engine/newpipe/build.gradle.kts"
INFO = "engine/newpipe/src/main/kotlin/com/molina/engine/newpipe/NewPipeInfo.kt"
PINS = "scripts/newpipe/pins.sha256"
UI = "app/src/main/java/com/molina/studio/settings/BundleUi.kt"
TRACKED = (GRADLE, INFO, PINS, "progres.md", UI)
SKIP = {".git", "build", ".gradle", "__pycache__", "docs", "terminal-emulator", "terminal-view"}
MAIN_GROUP = "com.github.TeamNewPipe.NewPipeExtractor"


def spit(path, text, mode="w"):
    with open(path, mode, encoding="utf-8") as f: f.write(text)


def slurp(path):
    with open(path, encoding="utf-8") as f: return f.read()


def _ignore(d, names):
    skip = [n for n in names if n in SKIP]
    if os.path.normpath(d).endswith(os.path.join("feature", "code", "src", "main")): skip += [n for n in names if n == "assets"]
    return skip


def current_version():
    return re.search(r'val newpipeVersion = "([^"]+)"', slurp(os.path.join(SRC, GRADLE))).group(1)


def bumped(v):
    a, b, c = (int(x) for x in v[1:].split("."))
    return "v%d.%d.%d" % (a, b, c + 1)


class T(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = tempfile.mkdtemp(prefix="np_")
        cls.repo = os.path.join(cls.work, "repo")
        shutil.copytree(SRC, cls.repo, ignore=_ignore)
        cls.script = os.path.join(cls.repo, "scripts/newpipe/bump_pin.py")
        cls.old = current_version(); cls.new = bumped(cls.old)

    @classmethod
    def tearDownClass(cls): shutil.rmtree(cls.work, ignore_errors=True)

    def f(self, rel): return slurp(os.path.join(self.repo, rel))

    def reset(self):
        for rel in TRACKED: shutil.copy(os.path.join(SRC, rel), os.path.join(self.repo, rel))

    def resolved(self, ver=None, mutate=None):
        """Bangun resolved.txt tiruan dari pin saat ini: jar utama diganti ke versi target dengan hash baru."""
        ver = ver or self.new; lines = {}
        for l in slurp(os.path.join(SRC, PINS)).splitlines():
            l = l.strip()
            if not l or l.startswith("#"): continue
            h, n = l.split(None, 1); lines[n.strip()] = h
        out = {}
        for n, h in lines.items():
            if n == "NewPipeExtractor-%s.jar" % self.old: out["NewPipeExtractor-%s.jar" % ver] = ("a" * 64, MAIN_GROUP, ver)
            else: out[n] = (h, "org.contoh", "1.0")
        if mutate: mutate(out)
        text = "".join("RESOLVED %s:%s:%s  %s  %s\n" % (g, n[:-4], v, h, n) for n, (h, g, v) in out.items())
        path = os.path.join(self.work, "resolved.txt")
        spit(path, text)
        return path

    def bump(self, ver, resolved, *extra):
        return subprocess.run([sys.executable, self.script, ver, "--resolved", resolved] + list(extra), capture_output=True, text=True)

    def snapshot(self): return {rel: self.f(rel) for rel in TRACKED}

    def test_1_apply_tiga_tempat_dan_idempotent(self):
        self.reset()
        r = self.bump(self.new, self.resolved())
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn('val newpipeVersion = "%s"' % self.new, self.f(GRADLE))
        self.assertIn('VERSION = "%s"' % self.new, self.f(INFO))
        self.assertIn("a" * 64 + "  NewPipeExtractor-%s.jar" % self.new, self.f(PINS))
        self.assertNotIn("NewPipeExtractor-%s.jar" % self.old, self.f(PINS))
        snap = self.snapshot()
        r2 = self.bump(self.new, self.resolved())
        self.assertEqual(r2.returncode, 0, r2.stdout + r2.stderr); self.assertIn("idempotent", r2.stdout)
        self.assertEqual(self.snapshot(), snap)
        self.assertEqual(self.f("progres.md").count("[NEWPIPE-PIN %s]" % self.new), 1)

    def test_2_dry_run_tidak_menulis(self):
        self.reset(); before = self.snapshot()
        r = self.bump(self.new, self.resolved(), "--dry-run")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr); self.assertIn("dry-run", r.stdout)
        self.assertEqual(self.snapshot(), before)

    def test_3_ditolak_tanpa_menulis(self):
        self.reset(); before = self.snapshot()
        main = "NewPipeExtractor-%s.jar" % self.new
        cases = {
            "format versi": ("0.26", self.resolved(), None),
            "versi lebih lama": ("v0.0.1", self.resolved("v0.0.1"), None),
            "jar utama tidak ada": (self.new, self.resolved(mutate=lambda o: o.pop(main)), None),
            "grup jar utama janggal": (self.new, self.resolved(mutate=lambda o: o.__setitem__(main, ("a" * 64, "org.jahat", self.new))), None),
        }
        for name, (ver, res, _) in cases.items():
            r = self.bump(ver, res)
            self.assertNotEqual(r.returncode, 0, name); self.assertIn("GAGAL", r.stderr, name)
            self.assertEqual(self.snapshot(), before, name)
        kosong = os.path.join(self.work, "kosong.txt"); spit(kosong, "tidak ada baris di sini\n")
        r = self.bump(self.new, kosong); self.assertNotEqual(r.returncode, 0); self.assertEqual(self.snapshot(), before)
        rusak = os.path.join(self.work, "rusak.txt"); spit(rusak, "RESOLVED a:b:1 nothex x.jar\n")
        r = self.bump(self.new, rusak); self.assertNotEqual(r.returncode, 0); self.assertEqual(self.snapshot(), before)
        ganda = self.resolved(); spit(ganda, slurp(ganda).splitlines()[0] + "\n", "a")
        r = self.bump(self.new, ganda); self.assertNotEqual(r.returncode, 0); self.assertIn("ganda", r.stderr)
        self.assertEqual(self.snapshot(), before)

    def test_4_kondisi_awal_tidak_konsisten_ditolak(self):
        self.reset()
        p = os.path.join(self.repo, INFO)
        spit(p, self.f(INFO).replace('VERSION = "%s"' % self.old, 'VERSION = "v9.9.9"'))
        before = self.snapshot()
        r = self.bump(self.new, self.resolved())
        self.assertNotEqual(r.returncode, 0); self.assertIn("tidak konsisten", r.stderr)
        self.assertEqual(self.snapshot(), before)

    def test_5_downgrade_hanya_dengan_flag(self):
        self.reset(); low = "v0.0.1"
        r = self.bump(low, self.resolved(low)); self.assertNotEqual(r.returncode, 0)
        r = self.bump(low, self.resolved(low), "--allow-downgrade"); self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn('VERSION = "%s"' % low, self.f(INFO))

    def test_6_diff_menandai_dependensi_baru_dan_hash_beda(self):
        self.reset()
        def mut(o):
            o["dep-baru-2.0.jar"] = ("b" * 64, "org.contoh", "2.0")
            k = next(n for n in o if n.startswith("jsoup-")); h, g, v = o[k]; o[k] = ("c" * 64, g, v)
        r = self.bump(self.new, self.resolved(mutate=mut), "--dry-run")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("BARU", r.stdout); self.assertIn("HASH BEDA", r.stdout)

    def test_7_rollback_saat_gerbang_gagal(self):
        self.reset()
        spit(os.path.join(self.repo, UI), "\n// ProcessBuilder\n", "a")
        before = self.snapshot()
        r = self.bump(self.new, self.resolved())
        self.assertNotEqual(r.returncode, 0); self.assertIn("dikembalikan", r.stderr)
        self.assertEqual(self.snapshot(), before)


if __name__ == "__main__":
    unittest.main(verbosity=2)

#!/usr/bin/env python3
"""Uji bump_ffmpeg.py pada SALINAN repo (tidak menyentuh repo asli). Jalankan: python3 scripts/native/test_bump_ffmpeg.py"""
import hashlib, io, os, shutil, subprocess, sys, tarfile, tempfile, unittest

SRC = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ENV = "native/ffmpeg-cli.env"
KT = "app/src/main/java/com/molina/studio/packages/ToolStore.kt"
ASSET = "ffmpeg-molina-arm64.tar.xz"
SKIP = {".git", "build", ".gradle", "__pycache__", "docs", "terminal-emulator", "terminal-view"}


def slurp(path, mode="r"):
    with open(path, mode, **({} if "b" in mode else {"encoding": "utf-8"})) as f: return f.read()


def _ignore(d, names):
    skip = [n for n in names if n in SKIP]
    if os.path.normpath(d).endswith(os.path.join("feature", "code", "src", "main")): skip += [n for n in names if n == "assets"]
    return skip


ELF_ARM64 = b"\x7fELF\x02\x01\x01" + b"\x00" * 9 + b"\x02\x00\xb7\x00" + b"\x01\x00\x00\x00" + b"ffmpeg-fake"
ELF_X86 = b"\x7fELF\x02\x01\x01" + b"\x00" * 9 + b"\x02\x00\x3e\x00" + b"\x01\x00\x00\x00" + b"x86"
GOOD = [("ffmpeg", ELF_ARM64), ("ffprobe", ELF_ARM64 + b"-probe")]


def make_txz(path, members):
    """tar.xz deterministik: mtime anggota 0 dan header xz tanpa waktu."""
    with tarfile.open(path, "w:xz") as tf:
        for name, data in members:
            ti = tarfile.TarInfo(name)
            if isinstance(data, tuple): ti.type = tarfile.SYMTYPE; ti.linkname = data[0]; tf.addfile(ti)
            elif data is None: ti.type = tarfile.DIRTYPE; ti.mode = 0o755; tf.addfile(ti)
            else: ti.size = len(data); ti.mode = 0o755; tf.addfile(ti, io.BytesIO(data))
    return hashlib.sha256(slurp(path, "rb")).hexdigest()


class T(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = tempfile.mkdtemp(prefix="nf_")
        cls.repo = os.path.join(cls.work, "repo")
        shutil.copytree(SRC, cls.repo, ignore=_ignore)
        cls.script = os.path.join(cls.repo, "scripts/native/bump_ffmpeg.py")
        cls.orig = {k: slurp(os.path.join(SRC, k)) for k in (ENV, KT)}

    @classmethod
    def tearDownClass(cls): shutil.rmtree(cls.work, ignore_errors=True)

    def f(self, rel): return os.path.join(self.repo, rel)
    def env(self): return slurp(self.f(ENV))
    def kt(self): return slurp(self.f(KT))

    def reset(self):
        for k, v in self.orig.items():
            with open(self.f(k), "w", encoding="utf-8", newline="\n") as fh: fh.write(v)
        with open(self.f("progres.md"), "w", encoding="utf-8") as fh: fh.write("# Progres uji\n")

    def snapshot(self): return (self.env(), self.kt(), slurp(self.f("progres.md")))

    def run_bump(self, tag, members, sums_override=None, extra=()):
        arc = os.path.join(self.work, ASSET); sha = make_txz(arc, members)
        sp = os.path.join(self.work, "SHA256SUMS")
        with open(sp, "w") as fh: fh.write(sums_override if sums_override is not None else "%s  %s\n" % (sha, ASSET))
        r = subprocess.run([sys.executable, self.script, tag, "--tarxz", arc, "--sums", sp] + list(extra), capture_output=True, text=True)
        return r, sha

    def test_0_arsip_deterministik(self):
        a = os.path.join(self.work, "a.txz"); b = os.path.join(self.work, "b.txz")
        self.assertEqual(make_txz(a, GOOD), make_txz(b, GOOD))

    def test_1_apply_tiga_berkas_sinkron_idempotent(self):
        self.reset()
        r, sha = self.run_bump("ffmpeg-cli-bbbbbbb-run9", GOOD)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("FFMPEG_CLI_TAG=ffmpeg-cli-bbbbbbb-run9", self.env())
        self.assertIn("FFMPEG_CLI_SHA256=" + sha, self.env())
        self.assertIn('FFMPEG_LITE_TAG = "ffmpeg-cli-bbbbbbb-run9"', self.kt())
        self.assertIn('FFMPEG_LITE_SHA256 = "%s"' % sha, self.kt())
        self.assertIn("# Pin biner ffmpeg CLI", self.env())  # komentar dipertahankan
        r2, _ = self.run_bump("ffmpeg-cli-bbbbbbb-run9", GOOD)
        self.assertEqual(r2.returncode, 0); self.assertIn("idempotent", r2.stdout)
        self.assertEqual(slurp(self.f("progres.md")).count("[FFMPEG-PIN ffmpeg-cli-bbbbbbb-run9]"), 1)

    def test_2_dry_run_tidak_menulis(self):
        self.reset(); before = self.snapshot()
        r, _ = self.run_bump("ffmpeg-cli-ccccccc-run10", GOOD, extra=["--dry-run"])
        self.assertEqual(r.returncode, 0); self.assertEqual(self.snapshot(), before)

    def test_3_ditolak(self):
        self.reset(); before = self.snapshot()
        cases = {
            "tag salah": ("bukan-tag", GOOD, None),
            "tag lebih lama": ("ffmpeg-cli-aaaaaaa-run0", GOOD, None),
            "ffprobe hilang": ("ffmpeg-cli-ddddddd-run11", GOOD[:1], None),
            "berkas tambahan": ("ffmpeg-cli-ddddddd-run11", GOOD + [("evil.sh", b"x")], None),
            "path traversal": ("ffmpeg-cli-ddddddd-run11", GOOD + [("../ffmpeg", ELF_ARM64)], None),
            "folder": ("ffmpeg-cli-ddddddd-run11", [("ffmpeg", None), ("ffprobe", ELF_ARM64)], None),
            "symlink": ("ffmpeg-cli-ddddddd-run11", [("ffmpeg", ("/bin/sh",)), ("ffprobe", ELF_ARM64)], None),
            "bukan ELF": ("ffmpeg-cli-ddddddd-run11", [("ffmpeg", b"#!/bin/sh\n" + b"x" * 30), ("ffprobe", ELF_ARM64)], None),
            "ELF x86-64": ("ffmpeg-cli-ddddddd-run11", [("ffmpeg", ELF_X86), ("ffprobe", ELF_ARM64)], None),
            "SHA256SUMS beda": ("ffmpeg-cli-ddddddd-run11", GOOD, "%s  %s\n" % ("0" * 64, ASSET)),
            "SHA256SUMS tanpa arsip": ("ffmpeg-cli-ddddddd-run11", GOOD, "%s  lain.tar.xz\n" % ("0" * 64)),
        }
        for name, (tag, members, sums) in cases.items():
            r, _ = self.run_bump(tag, members, sums_override=sums)
            self.assertNotEqual(r.returncode, 0, name); self.assertEqual(self.snapshot(), before, name)

    def test_4_rollback_saat_gerbang_gagal(self):
        self.reset(); before = self.snapshot()
        ui = self.f("app/src/main/java/com/molina/studio/settings/BundleUi.kt")
        keep = slurp(ui)
        try:
            with open(ui, "a") as fh: fh.write("\n// ProcessBuilder\n")
            r, _ = self.run_bump("ffmpeg-cli-eeeeeee-run12", GOOD)
            self.assertNotEqual(r.returncode, 0); self.assertIn("dikembalikan", r.stderr)
            self.assertEqual(self.snapshot(), before)
        finally:
            with open(ui, "w") as fh: fh.write(keep)

    def test_5_tag_sama_hash_beda_ditolak(self):
        self.reset(); before = self.snapshot()
        cur = [l for l in self.env().splitlines() if l.startswith("FFMPEG_CLI_TAG=")][0].split("=", 1)[1]
        r, _ = self.run_bump(cur, GOOD)  # arsip palsu: hash pasti beda dari pin saat ini
        self.assertNotEqual(r.returncode, 0); self.assertIn("HASH BEDA", r.stderr)
        self.assertEqual(self.snapshot(), before)

    def test_6_progres_dibuat_bila_tidak_ada(self):
        self.reset(); os.remove(self.f("progres.md"))
        r, _ = self.run_bump("ffmpeg-cli-fffffff-run13", GOOD)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("[FFMPEG-PIN ffmpeg-cli-fffffff-run13]", slurp(self.f("progres.md")))

    def test_7_pin_kt_beda_disamakan(self):
        self.reset()
        import re
        with open(self.f(KT), "w", encoding="utf-8") as fh:
            fh.write(re.sub(r'(FFMPEG_LITE_TAG = )"[^"]*"', r'\1"ffmpeg-cli-0000000-run1"', self.orig[KT]))
        r, sha = self.run_bump("ffmpeg-cli-9999999-run14", GOOD)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("PERINGATAN", r.stdout)
        self.assertIn('FFMPEG_LITE_TAG = "ffmpeg-cli-9999999-run14"', self.kt())
        self.assertIn('FFMPEG_LITE_SHA256 = "%s"' % sha, self.kt())


if __name__ == "__main__":
    unittest.main(verbosity=2)

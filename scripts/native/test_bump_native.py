#!/usr/bin/env python3
"""Uji bump_native.py pada SALINAN repo (tidak menyentuh repo asli). Jalankan: python3 scripts/native/test_bump_native.py"""
import gzip, hashlib, io, os, shutil, subprocess, sys, tarfile, tempfile, time, unittest

SRC = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def slurp(path, mode="r"):
    with open(path, mode, **({} if "b" in mode else {"encoding": "utf-8"})) as f: return f.read()


def make_tgz(path, members):
    """tgz DETERMINISTIK: header gzip memuat waktu pembuatan (mtime); tanpa mtime=0, dua pembuatan di detik
    berbeda menghasilkan byte (dan SHA-256) berbeda, sehingga uji idempotensi gagal di perangkat lambat."""
    with open(path, "wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as gz:
            with tarfile.open(fileobj=gz, mode="w") as tf:
                for name, data in members:
                    if data is None:
                        ti = tarfile.TarInfo(name); ti.type = tarfile.DIRTYPE; ti.mode = 0o755; tf.addfile(ti)
                    elif isinstance(data, tuple):  # symlink
                        ti = tarfile.TarInfo(name); ti.type = tarfile.SYMTYPE; ti.linkname = data[0]; tf.addfile(ti)
                    else:
                        ti = tarfile.TarInfo(name); ti.size = len(data); tf.addfile(ti, io.BytesIO(data))
    return hashlib.sha256(slurp(path, "rb")).hexdigest()


SKIP = {".git", "build", ".gradle", "__pycache__", "docs", "terminal-emulator", "terminal-view"}


def _ignore(d, names):
    """Lewati folder yang tidak dibaca gerbang bump agar salinan cepat (penting di /sdcard yang lambat).
    Konsekuensi: telemetry_gate di salinan memindai lebih sedikit berkas; ini hanya menguji alur tulis/rollback."""
    skip = [n for n in names if n in SKIP]
    if os.path.normpath(d).endswith(os.path.join("feature", "code", "src", "main")): skip += [n for n in names if n == "assets"]
    return skip


GOOD = [("jniLibs", None), ("jniLibs/arm64-v8a", None), ("jniLibs/arm64-v8a/libmpv.so", b"mpv"),
        ("jniLibs/arm64-v8a/libplayer.so", b"pl"), ("jniLibs/arm64-v8a/libc++_shared.so", b"cxx")]


class T(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = tempfile.mkdtemp(prefix="nt_")
        cls.repo = os.path.join(cls.work, "repo")
        shutil.copytree(SRC, cls.repo, ignore=_ignore)
        cls.script = os.path.join(cls.repo, "scripts/native/bump_native.py")

    @classmethod
    def tearDownClass(cls): shutil.rmtree(cls.work, ignore_errors=True)

    def env(self): return slurp(os.path.join(self.repo, "native/prebuilt.env"))

    def reset(self):
        for f in ("native/prebuilt.env", "progres.md", "app/src/main/java/com/molina/studio/settings/BundleUi.kt"):
            shutil.copy(os.path.join(SRC, f), os.path.join(self.repo, f))

    def run_bump(self, tag, members, sums=True, extra=(), sums_override=None):
        tgz = os.path.join(self.work, "m.tgz"); sha = make_tgz(tgz, members)
        sp = os.path.join(self.work, "SHA256SUMS")
        with open(sp, "w") as f:
            f.write(sums_override if sums_override is not None else "%s  molina-native-arm64.tgz\n" % sha)
        cmd = [sys.executable, self.script, tag, "--tgz", tgz, "--sums", sp] + list(extra)
        r = subprocess.run(cmd, capture_output=True, text=True)
        return r, sha

    def test_0_tgz_deterministik_lintas_detik(self):
        a = os.path.join(self.work, "a.tgz"); b = os.path.join(self.work, "b.tgz")
        sa = make_tgz(a, GOOD); time.sleep(1.2); sb = make_tgz(b, GOOD)
        self.assertEqual(sa, sb)

    def test_1_apply_idempotent_and_progres(self):
        self.reset()
        r, sha = self.run_bump("native-mpv-bbbbbbb-run9", GOOD)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        e = self.env()
        self.assertIn("NATIVE_TAG=native-mpv-bbbbbbb-run9", e); self.assertIn("NATIVE_SHA256=" + sha, e)
        self.assertIn("# Pin pustaka native", e)  # komentar dipertahankan
        r2, _ = self.run_bump("native-mpv-bbbbbbb-run9", GOOD)
        self.assertEqual(r2.returncode, 0); self.assertIn("idempotent", r2.stdout)
        prog = slurp(os.path.join(self.repo, "progres.md"))
        self.assertEqual(prog.count("[NATIVE-PIN native-mpv-bbbbbbb-run9]"), 1)

    def test_2_dry_run_tidak_menulis(self):
        self.reset(); before = self.env()
        r, _ = self.run_bump("native-mpv-ccccccc-run10", GOOD, extra=["--dry-run"])
        self.assertEqual(r.returncode, 0); self.assertEqual(self.env(), before)

    def test_3_ditolak(self):
        self.reset(); before = self.env()
        cases = {
            "tag salah": ("bukan-tag", GOOD, None),
            "tag lebih lama": ("native-mpv-aaaaaaa-run1", GOOD, None),
            "so wajib hilang": ("native-mpv-ddddddd-run11", GOOD[:-1], None),
            "isi di luar jniLibs": ("native-mpv-ddddddd-run11", GOOD + [("evil.sh", b"x")], None),
            "path traversal": ("native-mpv-ddddddd-run11", GOOD + [("jniLibs/../x.so", b"x")], None),
            "symlink": ("native-mpv-ddddddd-run11", GOOD + [("jniLibs/arm64-v8a/ln.so", ("/etc/passwd",))], None),
            "SHA256SUMS beda": ("native-mpv-ddddddd-run11", GOOD, "%s  molina-native-arm64.tgz\n" % ("0" * 64)),
            "SHA256SUMS tanpa tgz": ("native-mpv-ddddddd-run11", GOOD, "%s  lain.tgz\n" % ("0" * 64)),
        }
        for name, (tag, members, sums) in cases.items():
            r, _ = self.run_bump(tag, members, sums_override=sums)
            self.assertNotEqual(r.returncode, 0, name); self.assertEqual(self.env(), before, name)

    def test_4_rollback_saat_gerbang_gagal(self):
        self.reset(); before = self.env()
        ui = os.path.join(self.repo, "app/src/main/java/com/molina/studio/settings/BundleUi.kt")
        with open(ui, "a") as f: f.write("\n// ProcessBuilder\n")
        r, _ = self.run_bump("native-mpv-eeeeeee-run12", GOOD)
        self.assertNotEqual(r.returncode, 0); self.assertIn("dikembalikan", r.stderr)
        self.assertEqual(self.env(), before)

    def test_5_tag_sama_hash_beda_ditolak(self):
        self.reset()
        cur = [l for l in self.env().splitlines() if l.startswith("NATIVE_TAG=")][0].split("=", 1)[1]
        r, _ = self.run_bump(cur, GOOD + [("jniLibs/arm64-v8a/extra.so", b"1")])
        self.assertNotEqual(r.returncode, 0); self.assertIn("HASH BEDA", r.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)

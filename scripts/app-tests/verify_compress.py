#!/usr/bin/env python3
"""Verifikasi silang keluaran ArchiveEngine.compress dengan pustaka LAIN (zipfile, tarfile, py7zr, pyzipper).
Pemakaian: verify_compress.py <folder-keluaran-uji> <folder-fixture>  (fixture berisi src/)."""
import io, os, shutil, subprocess, sys, tarfile, tempfile, zipfile
import py7zr

out, fx = sys.argv[1], sys.argv[2]
src = os.path.join(fx, "src")
want = {}
for root, _, fs in os.walk(src):
    for f in fs:
        p = os.path.join(root, f)
        want["src/" + os.path.relpath(p, src).replace(os.sep, "/")] = open(p, "rb").read()

fails = 0
checked = 0
def fail(msg):
    global fails
    fails += 1
    print("GAGAL:", msg)

def compare(tag, got):
    global checked
    checked += 1
    for k, v in want.items():
        if k not in got: fail(f"{tag}: entri {k} hilang")
        elif got[k] != v: fail(f"{tag}: isi {k} berbeda")

try:
    import pyzipper
except ImportError:
    pyzipper = None

for d in sorted(os.listdir(out)):
    dp = os.path.join(out, d)
    if not os.path.isdir(dp) or not d.split("-")[0] in ("ZIP", "7Z", "TAR", "TAR.GZ", "TAR.BZ2", "TAR.XZ") and not d.startswith("ZIP-AES"):
        continue
    for f in os.listdir(dp):
        if not f.startswith("src"): continue
        fp = os.path.join(dp, f)
        tag = d + "/" + f
        try:
            if d.startswith("ZIP-AES"):
                if pyzipper is None:
                    print("(lewati", tag, ": pyzipper tidak terpasang)"); continue
                with pyzipper.AESZipFile(fp) as z:
                    z.setpassword(b"rahasia")
                    got = {n: z.read(n) for n in z.namelist() if not n.endswith("/")}
                    # pastikan benar-benar AES
                    if not any(i.flag_bits & 1 for i in z.infolist()): fail(f"{tag}: tidak terenkripsi")
                compare(tag, got)
            elif f.endswith(".zip"):
                with zipfile.ZipFile(fp) as z:
                    if z.testzip() is not None: fail(f"{tag}: testzip gagal")
                    compare(tag, {n: z.read(n) for n in z.namelist() if not n.endswith("/")})
            elif f.endswith(".7z"):
                with py7zr.SevenZipFile(fp) as z:
                    if z.testzip() is not None: fail(f"{tag}: testzip 7z gagal")
                with tempfile.TemporaryDirectory() as td:
                    with py7zr.SevenZipFile(fp) as z:
                        z.extractall(path=td)
                    got = {}
                    for root, _, fs in os.walk(td):
                        for fn in fs:
                            full = os.path.join(root, fn)
                            got[os.path.relpath(full, td).replace(os.sep, "/")] = open(full, "rb").read()
                compare(tag, got)
            else:
                with tarfile.open(fp) as t:
                    got = {}
                    for m in t.getmembers():
                        if m.isfile(): got[m.name] = t.extractfile(m).read()
                        elif not m.isdir(): fail(f"{tag}: tipe entri tak terduga {m.name}")
                compare(tag, got)
        except Exception as e:
            fail(f"{tag}: {type(e).__name__}: {e}")

# pembaca referensi: 7-Zip ASLI (p7zip-full) untuk 7z, zip (tanpa AES), dan tar*: uji integritas + isi identik
SEVENZ = shutil.which("7z") or shutil.which("7zz") or shutil.which("7za")
if SEVENZ:
    for d in sorted(os.listdir(out)):
        dp = os.path.join(out, d)
        if not os.path.isdir(dp) or not (d.split("-")[0] in ("ZIP", "7Z") or d.startswith("TAR")): continue
        if d.startswith("ZIP-AES"): continue
        for f in os.listdir(dp):
            if not f.startswith("src"): continue
            fp = os.path.join(dp, f); tag = "7z ASLI " + d + "/" + f; checked += 1
            try:
                with tempfile.TemporaryDirectory() as td:
                    t = subprocess.run([SEVENZ, "t", fp], capture_output=True, text=True)
                    if t.returncode != 0: fail(f"{tag}: 7z t gagal: {t.stdout[-200:]}")
                    x = subprocess.run([SEVENZ, "x", "-y", "-o" + td, fp], capture_output=True, text=True)
                    if x.returncode != 0: fail(f"{tag}: 7z x gagal: {x.stdout[-200:]}")
                    inner = td
                    if f.endswith((".tar.gz", ".tar.bz2", ".tar.xz")):  # 7z membuka lapis kompresi dulu, menyisakan .tar
                        tars = [n for n in os.listdir(td) if n.endswith(".tar")]
                        if tars:
                            inner = tempfile.mkdtemp(dir=td)
                            subprocess.run([SEVENZ, "x", "-y", "-o" + inner, os.path.join(td, tars[0])], capture_output=True)
                    got = {}
                    for root, _, fs in os.walk(inner):
                        for fn in fs:
                            full = os.path.join(root, fn)
                            if full.endswith(".tar") and root == td: continue
                            got[os.path.relpath(full, inner).replace(os.sep, "/")] = open(full, "rb").read()
                    compare(tag, got)
            except Exception as e:
                fail(f"{tag}: {type(e).__name__}: {e}")
else:
    print("(lewati 7-Zip asli: biner 7z tidak ada)")

# folder sym-*: arsip proyek (unicode/panjang) harus terbaca pula
for d in sorted(os.listdir(out)):
    if not d.startswith("sym-"): continue
    for f in os.listdir(os.path.join(out, d)):
        fp = os.path.join(out, d, f); checked += 1
        try:
            if f.endswith(".zip"):
                names = zipfile.ZipFile(fp).namelist()
            elif f.endswith(".7z"):
                names = py7zr.SevenZipFile(fp).getnames()
            else:
                names = tarfile.open(fp).getnames()
            if not any("日本語" in n for n in names): fail(f"{d}/{f}: nama unicode hilang")
            if not any(len(n.split("/")[-1]) >= 180 for n in names): fail(f"{d}/{f}: nama panjang hilang")
        except Exception as e:
            fail(f"{d}/{f}: {type(e).__name__}: {e}")

print(f"verifikasi silang: {checked} arsip diperiksa, {fails} gagal")
sys.exit(1 if fails else 0)

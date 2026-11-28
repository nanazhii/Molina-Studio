#!/usr/bin/env python3
"""Buat arsip uji untuk ArchiveEngineTests (butuh: pip install py7zr). Pemakaian: make_archive_fixtures.py <folder-keluaran>"""
import bz2, gzip, io, lzma, os, sys, tarfile, zipfile, random
import py7zr

out = sys.argv[1]
src = os.path.join(out, "src")
os.makedirs(os.path.join(src, "sub", "deep"), exist_ok=True)
os.makedirs(os.path.join(src, "emptydir"), exist_ok=True)
rnd = random.Random(7)
files = {
    "a.txt": b"halo molina\n",
    "sub/b.txt": ("baris %d\n" % 1).encode() * 5000,
    "sub/deep/c.bin": bytes(rnd.getrandbits(8) for _ in range(300_000)),
    "empty.txt": b"",
}
for rel, data in files.items():
    with open(os.path.join(src, rel), "wb") as f:
        f.write(data)

def mk_tar(path, mode):
    with tarfile.open(path, mode) as t:
        t.add(src, arcname=".", recursive=True)  # awalan "./" seperti tar GNU
mk_tar(os.path.join(out, "t.tar"), "w")
mk_tar(os.path.join(out, "t.tar.gz"), "w:gz")
mk_tar(os.path.join(out, "t.tar.bz2"), "w:bz2")
mk_tar(os.path.join(out, "t.tar.xz"), "w:xz")
mk_tar(os.path.join(out, "t.tgz"), "w:gz")

def mk_7z(path, **kw):
    with py7zr.SevenZipFile(path, "w", **kw) as z:
        z.writeall(src, arcname="")
mk_7z(os.path.join(out, "t.7z"))
mk_7z(os.path.join(out, "enc_header.7z"), password="rahasia", header_encryption=True)
mk_7z(os.path.join(out, "enc_content.7z"), password="rahasia")

with zipfile.ZipFile(os.path.join(out, "z.zip"), "w", zipfile.ZIP_DEFLATED) as z:
    for rel, data in files.items():
        z.writestr(rel, data)
    z.writestr("emptydir/", b"")

note = b"isi catatan\n" * 2000
for name, mod in (("notes.txt.gz", gzip), ("notes.txt.bz2", bz2), ("notes.txt.xz", lzma)):
    with open(os.path.join(out, name), "wb") as f:
        f.write(mod.compress(note))
with open(os.path.join(out, "notes.txt"), "wb") as f:
    f.write(note)

# arsip jahat
with tarfile.open(os.path.join(out, "evil_dotdot.tar"), "w") as t:
    ti = tarfile.TarInfo("ok.txt"); ti.size = 2; t.addfile(ti, io.BytesIO(b"ok"))
    ti = tarfile.TarInfo("../evil.txt"); ti.size = 4; t.addfile(ti, io.BytesIO(b"evil"))
with tarfile.open(os.path.join(out, "evil_symlink.tar"), "w") as t:
    ti = tarfile.TarInfo("link"); ti.type = tarfile.SYMTYPE; ti.linkname = "/tmp"; t.addfile(ti)
    ti = tarfile.TarInfo("link/pwn.txt"); ti.size = 3; t.addfile(ti, io.BytesIO(b"pwn"))
    ti = tarfile.TarInfo("fine.txt"); ti.size = 4; t.addfile(ti, io.BytesIO(b"fine"))
with zipfile.ZipFile(os.path.join(out, "evil.zip"), "w") as z:
    z.writestr("../zzevil.txt", b"evil")
    z.writestr("ok.txt", b"ok")

# rusak: tar.gz terpotong, 7z terpotong
data = open(os.path.join(out, "t.tar.gz"), "rb").read()
open(os.path.join(out, "broken.tar.gz"), "wb").write(data[: len(data) // 3])
data = open(os.path.join(out, "t.7z"), "rb").read()
open(os.path.join(out, "broken.7z"), "wb").write(data[: len(data) // 2])
print("fixture siap di", out)

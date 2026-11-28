#!/usr/bin/env python3
"""[ICON-PALETTE] Gerbang palet ikon berkas: dua ekstensi berlabel (bukan arsip) tidak boleh memakai warna yang sama,
supaya jenis berkas terbaca dari warna pita. Sumber: core/ui/.../W7FileIcon.kt (W7Glyph). Exit 1 bila ada tabrakan."""
import collections, pathlib, re, sys

SRC = pathlib.Path(__file__).resolve().parent.parent / "core/ui/src/main/kotlin/com/molina/core/ui/W7FileIcon.kt"
rows = re.findall(r'put\("([^"]+)", W7Glyph\(([^,]+), ("[^"]*"|null)(?:, W7Shape\.(\w+))?\)\)', SRC.read_text(encoding="utf-8"))
if not rows:
    sys.exit("GAGAL: tidak ada entri W7Glyph yang terbaca (format berubah?)")
by = collections.defaultdict(list)
for exts, color, label, shape in rows:
    if label == "null" or shape == "ARCHIVE":
        continue  # media dibedakan bentuk, arsip dibedakan label (warna merek bersama diizinkan)
    by[color.strip()].append(exts.split()[0] + ":" + label.strip('"'))
bad = {c: v for c, v in by.items() if len(v) > 1}
if bad:
    for c, v in bad.items():
        print("TABRAKAN warna", c, v)
    sys.exit(1)
print("ICON PALETTE GATE OK (%d warna berlabel, semua unik)" % len(by))

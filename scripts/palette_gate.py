#!/usr/bin/env python3
"""[PALETTE-SSOT] Warna yang sudah jadi token pusat (MolinaTheme) tidak boleh ditulis ulang sebagai literal.
Tambah heks ke TOKEN saat sebuah warna dipusatkan. Exit 1 bila ada literal di luar berkas sumbernya."""
import pathlib, re, sys
TOKEN = {"8FC4EE": "MolinaTheme.AeroBlueTop", "2D6DB5": "MolinaTheme.AeroBlueBottom"}
SOURCE = pathlib.Path("core/ui/src/main/kotlin/com/molina/studio/ui/MolinaTheme.kt")
bad = []
for f in pathlib.Path(".").rglob("*.kt"):
    if "/build/" in str(f) or f == SOURCE: continue
    s = f.read_text(encoding="utf-8", errors="ignore")
    for hx, tok in TOKEN.items():
        for m in re.finditer(r"0xFF" + hx + r"\b", s, re.I):
            bad.append("%s:%d memakai literal #%s, pakai %s" % (f, s[:m.start()].count("\n") + 1, hx, tok))
for b in bad: print("LITERAL:", b)
print("PALETTE GATE %s (%d token dijaga)" % ("GAGAL" if bad else "OK", len(TOKEN)))
sys.exit(1 if bad else 0)

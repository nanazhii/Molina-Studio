#!/usr/bin/env python3
"""Laporan ketergantungan antar-paket di app (alat bantu ekstraksi modul).
Pakai: python3 scripts/pkg_graph.py [--max-cycles N]  (exit 1 bila siklus > N)"""
import re, sys, pathlib, collections

ROOT = pathlib.Path("app/src/main/java/com/molina/studio")
if not ROOT.exists():
    sys.exit("jalankan dari root repo")
files = list(ROOT.rglob("*.kt"))
top = lambda f: f.relative_to(ROOT).parts[0] if len(f.relative_to(ROOT).parts) > 1 else "(root)"
pkgs = {top(f) for f in files}
edges = collections.defaultdict(collections.Counter)
for f in files:
    for m in re.finditer(r"^import com\.molina\.studio\.(\w+)", f.read_text(errors="ignore"), re.M):
        s, d = top(f), m.group(1)
        if d in pkgs and d != s:
            edges[s][d] += 1
cyc = sorted({tuple(sorted((a, b))) for a in edges for b in edges[a] if a in edges.get(b, {})})
for s in sorted(pkgs):
    print(f"{s:11}-> " + (", ".join(f"{d}:{n}" for d, n in edges[s].most_common()) or "-"))
print(f"\nSiklus dua arah: {len(cyc)}")
for a, b in cyc:
    print(f"  {a} <-> {b}")
lim = int(sys.argv[sys.argv.index("--max-cycles") + 1]) if "--max-cycles" in sys.argv else None
sys.exit(1 if lim is not None and len(cyc) > lim else 0)

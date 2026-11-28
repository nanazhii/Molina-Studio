#!/usr/bin/env python3
"""Gerbang duplikasi Molina-Studio: penegak prinsip satu sumber kebenaran (SSOT).

Mendeteksi, lintas "unit" (modul Gradle; untuk :app, paket tingkat atas):
  1. BLOK  : urutan >= --window baris kode identik (setelah normalisasi) di dua unit berbeda.
  2. LITERAL: string literal "bermakna" (path/kunci/nama, >= 8 karakter) yang muncul di >= 2 unit.
Pakai:
  python3 scripts/dup_gate.py                 laporan saja
  python3 scripts/dup_gate.py --max-blocks N --max-literals M    exit 1 bila melebihi (ratchet)
  python3 scripts/dup_gate.py --top 20        jumlah baris laporan per bagian"""
import collections, hashlib, pathlib, re, sys

ROOT = pathlib.Path(".")
APP = pathlib.Path("app/src/main/java/com/molina/studio")
SKIP = ("/build/", "/.git/", "-tests/", "/test/", "/androidTest/")


def arg(name, default):
    return int(sys.argv[sys.argv.index(name) + 1]) if name in sys.argv else default


WINDOW, TOP = arg("--window", 8), arg("--top", 15)
MAXB = arg("--max-blocks", None) if "--max-blocks" in sys.argv else None
MAXL = arg("--max-literals", None) if "--max-literals" in sys.argv else None
if not (ROOT / "settings.gradle.kts").exists():
    sys.exit("jalankan dari root repo")


def unit(f):
    s = f.as_posix()
    if s.startswith(APP.as_posix() + "/"):
        parts = f.relative_to(APP).parts
        return "app:" + (parts[0] if len(parts) > 1 else "(root)")
    parts = f.parts
    if parts[0] in ("core", "feature", "engine") and len(parts) > 1:
        return f"{parts[0]}:{parts[1]}"
    return parts[0]


TRIVIAL = re.compile(r"^(\}|\{|\)|\]|else|else \{|\} else \{|return|break|continue|try \{|\} catch.*|\} finally \{)$")
files = [f for f in ROOT.rglob("*.kt") if not any(k in f.as_posix() for k in SKIP)]


def code_lines(text):
    """(nomor_baris, teks_normal) tanpa komentar, impor, paket, anotasi, dan baris sepele."""
    out, in_block = [], False
    for i, raw in enumerate(text.splitlines(), 1):
        l = raw.strip()
        if in_block:
            if "*/" in l:
                in_block = False
            continue
        if l.startswith("/*"):
            in_block = "*/" not in l
            continue
        if not l or l.startswith(("//", "import ", "package ", "@")) or TRIVIAL.match(l):
            continue
        out.append((i, re.sub(r"\s+", " ", l)))
    return out


# ---- 1. blok kode identik lintas unit
shingles = collections.defaultdict(set)  # hash -> {(unit, file, baris)}
for f in files:
    ls = code_lines(f.read_text(errors="ignore"))
    for k in range(len(ls) - WINDOW + 1):
        win = ls[k:k + WINDOW]
        if sum(len(t) for _, t in win) < WINDOW * 18:  # abaikan blok yang terlalu generik
            continue
        shingles[hashlib.md5("\n".join(t for _, t in win).encode()).hexdigest()].add((unit(f), f.as_posix(), win[0][0]))
pairs = collections.defaultdict(list)  # (unitA, unitB) -> [(fileA, lineA, fileB, lineB)]
for hits in shingles.values():
    by_unit = collections.defaultdict(list)
    for u, fp, ln in hits:
        by_unit[u].append((fp, ln))
    if len(by_unit) < 2:
        continue
    us = sorted(by_unit)
    for a in range(len(us)):
        for b in range(a + 1, len(us)):
            for fa, la in sorted(by_unit[us[a]]):
                for fb, lb in sorted(by_unit[us[b]]):
                    pairs[(us[a], us[b])].append((fa, la, fb, lb))
# satukan jendela yang bertumpuk: hitung jumlah (berkas A, berkas B) berbeda
blocks = collections.Counter()
detail = {}
for (ua, ub), items in pairs.items():
    for fa, la, fb, lb in items:
        blocks[(ua, ub, fa, fb)] += 1
        detail[(ua, ub, fa, fb)] = min(detail.get((ua, ub, fa, fb), (la, lb)), (la, lb))
nblocks = len(blocks)

# ---- 2. literal string bermakna di >= 2 unit
NOISE = {"https://", "http://", "/storage"}  # terlalu generik untuk dianggap SSOT
LIT = re.compile(r'"([A-Za-z0-9_./:\-]{8,})"')
lit_units = collections.defaultdict(set)
for f in files:
    for l in code_lines(f.read_text(errors="ignore")):
        if "log(" in l[1] or "Log." in l[1]:
            continue
        for m in LIT.finditer(l[1]):
            v = m.group(1)
            if v in NOISE:
                continue
            if re.search(r"[/._:\-]", v) or re.fullmatch(r"[a-z]+[A-Z][A-Za-z]+", v):
                lit_units[v].add((unit(f), f.as_posix()))
lits = {v: s for v, s in lit_units.items() if len({u for u, _ in s}) >= 2}

print(f"== BLOK identik lintas unit (jendela {WINDOW} baris): {nblocks} pasangan berkas")
for (ua, ub, fa, fb), n in blocks.most_common(TOP):
    la, lb = detail[(ua, ub, fa, fb)]
    print(f"  {n:3} jendela  {fa}:{la}  <->  {fb}:{lb}   [{ua} | {ub}]")
print(f"\n== LITERAL dipakai di >= 2 unit: {len(lits)}")
for v, s in sorted(lits.items(), key=lambda kv: -len({u for u, _ in kv[1]}))[:TOP]:
    print(f"  \"{v}\"  -> " + ", ".join(sorted({u for u, _ in s})))
print(f"\nRINGKASAN: blok={nblocks} literal={len(lits)}")

bad = []
if MAXB is not None and nblocks > MAXB:
    bad.append(f"blok {nblocks} > batas {MAXB}")
if MAXL is not None and len(lits) > MAXL:
    bad.append(f"literal {len(lits)} > batas {MAXL}")
if bad:
    print("DUP GATE GAGAL: " + "; ".join(bad))
    sys.exit(1)
print("DUP GATE OK")

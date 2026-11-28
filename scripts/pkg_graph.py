#!/usr/bin/env python3
"""Laporan dan penegak ketergantungan antar-paket di app (graf paket harus makin lurus, tidak makin kusut).
Tepi = impor DAN rujukan nama lengkap (com.molina.studio.<paket>.) di luar komentar; berkas tingkat akar (MainActivity dkk) dikecualikan.
Pakai: python3 scripts/pkg_graph.py [--max-cycles N] [--max-cyclic N] [--layers]
  --max-cycles N : siklus dua arah (A<->B) tidak boleh lebih dari N
  --max-cyclic N : jumlah paket di dalam komponen siklik (termasuk siklus berantai A->B->C->A) tidak boleh lebih dari N
  --layers       : paket dasar (LOW) tidak boleh bergantung ke paket app mana pun
  --mermaid      : cetak HANYA diagram Mermaid dari graf ini (dasar docs/ARSITEKTUR.md), lalu selesai"""
import re, sys, pathlib, collections

ROOT = pathlib.Path("app/src/main/java/com/molina/studio")
LOW = {"common"}  # lapisan dasar: boleh dipakai semua, tidak boleh memakai paket app lain
if not ROOT.exists():
    sys.exit("jalankan dari root repo")

files = list(ROOT.rglob("*.kt"))
top = lambda f: f.relative_to(ROOT).parts[0] if len(f.relative_to(ROOT).parts) > 1 else "(root)"
pkgs = {top(f) for f in files} - {"(root)"}


def clean(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//.*", "", text)
    return re.sub(r"^package .*$", "", text, flags=re.M)


edges = collections.defaultdict(collections.Counter)
for f in files:
    src = top(f)
    if src == "(root)":
        continue
    for m in re.finditer(r"\bcom\.molina\.studio\.(\w+)\.", clean(f.read_text(errors="ignore"))):
        d = m.group(1)
        if d in pkgs and d != src:
            edges[src][d] += 1

pairs = sorted({tuple(sorted((a, b))) for a in edges for b in edges[a] if a in edges.get(b, {})})


def components():
    idx, low, st, on, out, c = {}, {}, [], set(), [], [0]

    def sc(v):
        idx[v] = low[v] = c[0]; c[0] += 1; st.append(v); on.add(v)
        for w in edges.get(v, {}):
            if w not in idx:
                sc(w); low[v] = min(low[v], low[w])
            elif w in on:
                low[v] = min(low[v], idx[w])
        if low[v] == idx[v]:
            comp = []
            while True:
                w = st.pop(); on.discard(w); comp.append(w)
                if w == v:
                    break
            if len(comp) > 1:
                out.append(sorted(comp))

    for v in sorted(pkgs):
        if v not in idx:
            sc(v)
    return out


def shortest_cycle(comp):
    best = None
    for s in comp:
        q, seen = collections.deque([(s, [s])]), {s}
        while q:
            v, p = q.popleft()
            for w in edges.get(v, {}):
                if w == s and len(p) > 1 and (best is None or len(p) < len(best)):
                    best = p
                if w in comp and w not in seen:
                    seen.add(w); q.append((w, p + [w]))
    return best


comps = components()
cyclic = sum(len(c) for c in comps)
low_bad = sorted((s, d, n) for s in LOW for d, n in edges.get(s, {}).items())

if "--mermaid" in sys.argv:
    inside = {p for c in comps for p in c}
    out = ["flowchart TD", '    APP["app: MainActivity, MolinaApp (perakit)"]']
    for p in sorted(pkgs):
        out.append(f'    {p}["{p}"]')
    for s in sorted(edges):
        for d, n in sorted(edges[s].items()):
            out.append(f"    {s} {'-.->' if s in inside and d in inside else '-->'} {d}")
    out += [f"    APP --> {p}" for p in sorted(pkgs - LOW) if not any(p in edges[o] for o in edges if o != p)]
    out += ["    classDef base fill:#2D6DB5,stroke:#8FC4EE,color:#fff",
            "    classDef knot stroke:#D33,stroke-width:3px",
            "    class " + ",".join(sorted(LOW)) + " base"]
    if inside:
        out.append("    class " + ",".join(sorted(inside)) + " knot")
    print("\n".join(out))
    sys.exit(0)

for s in sorted(pkgs):
    print(f"{s:11}-> " + (", ".join(f"{d}:{n}" for d, n in edges[s].most_common()) or "-"))
print(f"\nSiklus dua arah: {len(pairs)}")
for a, b in pairs:
    print(f"  {a} <-> {b}")
print(f"Paket dalam komponen siklik: {cyclic}")
for comp in comps:
    p = shortest_cycle(comp)
    print("  " + ", ".join(comp) + ("   (contoh siklus: " + " -> ".join(p + [p[0]]) + ")" if p else ""))
print(f"Pelanggaran lapisan (dasar {', '.join(sorted(LOW))} memakai paket app): {len(low_bad)}")
for s, d, n in low_bad:
    print(f"  {s} -> {d} ({n})")


def opt(name):
    return int(sys.argv[sys.argv.index(name) + 1]) if name in sys.argv else None


fail = []
if opt("--max-cycles") is not None and len(pairs) > opt("--max-cycles"):
    fail.append(f"siklus dua arah {len(pairs)} > batas {opt('--max-cycles')}")
if opt("--max-cyclic") is not None and cyclic > opt("--max-cyclic"):
    fail.append(f"paket dalam komponen siklik {cyclic} > batas {opt('--max-cyclic')}")
if "--layers" in sys.argv and low_bad:
    fail.append(f"{len(low_bad)} pelanggaran lapisan")
if fail:
    print("PKG GRAPH GAGAL: " + "; ".join(fail))
    sys.exit(1)
if any(a in sys.argv for a in ("--max-cycles", "--max-cyclic", "--layers")):
    print("PKG GRAPH OK")

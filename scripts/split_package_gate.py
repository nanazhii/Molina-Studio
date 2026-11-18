#!/usr/bin/env python3
"""Gerbang split package Molina-Studio: satu paket Kotlin hanya boleh dimiliki SATU modul Gradle.
Paket yang terbelah membuat kepemilikan kode kabur dan menyembunyikan dependensi (impor tak perlu,
akses lintas modul tanpa terlihat di build.gradle). Utang lama dicatat di KNOWN_SPLITS (ratchet)."""
import collections, pathlib, re, sys

# paket -> modul pemilik yang diizinkan bersama (utang lama; hapus saat lunas)
KNOWN_SPLITS = {
    "com.molina.code.lsp": {"core/exec", "feature/code"},
    "com.molina.code.preview": {"core/exec", "feature/code"},
    "com.molina.code.run": {"core/exec", "feature/code"},
    "com.molina.studio.cinema": {"app", "core/ui"},
    "com.molina.studio.settings": {"app", "core/ui"},
}

def module_of(p):
    parts = p.parts
    for i, x in enumerate(parts):
        if x == "src" and i > 0:
            return "/".join(parts[:i])
    return None

owners = collections.defaultdict(lambda: collections.defaultdict(list))
for f in pathlib.Path(".").rglob("*.kt"):
    if ".git" in f.parts or "build" in f.parts or f.parts[0] == "scripts":
        continue
    m = module_of(f)
    if not m:
        continue
    t = f.read_text(errors="ignore")
    pk = re.search(r"^package\s+([\w.]+)", t, re.M)
    if pk:
        owners[pk.group(1)][m].append(f.as_posix())

errors, stale = [], []
for pkg, mods in sorted(owners.items()):
    if len(mods) < 2:
        continue
    known = KNOWN_SPLITS.get(pkg)
    if known is None or set(mods) - set(known):
        errors.append(f"{pkg}: terbelah di {sorted(mods)}")
for pkg in KNOWN_SPLITS:
    if len(owners.get(pkg, {})) < 2:
        stale.append(pkg)

n = sum(1 for m in owners.values() if len(m) >= 2)
for e in errors:
    print("SPLIT PACKAGE:", e)
for s in stale:
    print("PERINGATAN: utang sudah lunas, hapus dari KNOWN_SPLITS:", s)
if errors:
    print(f"SPLIT PACKAGE GATE GAGAL ({len(errors)} paket baru/melebar)")
    sys.exit(1)
print(f"SPLIT PACKAGE GATE OK ({len(owners)} paket, {n} terbelah dan dikenal)")

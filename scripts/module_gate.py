#!/usr/bin/env python3
"""Gerbang lapisan modul Molina-Studio. Exit 1 bila ada dependensi terlarang.

Lapisan: app > feature > engine > core. Aturan:
- core    : hanya core lain.
- engine  : core, ditambah tautan engine yang terdaftar (ENGINE_LINKS).
- feature : hanya core. Tidak ada feature -> feature maupun feature -> engine
            (engine disuntik lewat app).
- app     : bebas.
Pelanggaran lama dicatat di KNOWN_DEBT agar tidak bertambah. Utang yang sudah lunas
dilaporkan sebagai peringatan supaya dihapus dari daftar."""
import pathlib, re, sys

ENGINE_EXTRA = {":terminal-emulator", ":terminal-view", ":vault"}
ENGINE_LINKS = {(":terminal-view", ":terminal-emulator")}
KNOWN_DEBT = {}
DEP = re.compile(r'\b(?:api|implementation|compileOnly|runtimeOnly)\(\s*project\(\s*"(:[\w:-]+)"\s*\)\s*\)')


def layer(n):
    if n == ":app":
        return "app"
    if n.startswith(":core:"):
        return "core"
    if n.startswith(":feature:"):
        return "feature"
    if n.startswith(":engine:") or n in ENGINE_EXTRA:
        return "engine"
    return None


def modules():
    text = pathlib.Path("settings.gradle.kts").read_text()
    names = []
    for m in re.finditer(r"include\(([^)]*)\)", text):
        names += re.findall(r'"(:[\w:-]+)"', m.group(1))
    mapped = dict(re.findall(r'project\("(:[\w:-]+)"\)\.projectDir\s*=\s*file\("([^"]+)"\)', text))
    return {n: pathlib.Path(mapped.get(n, n.lstrip(":").replace(":", "/"))) for n in names}


def allowed(src, dst):
    if layer(src) == "app":
        return True
    if layer(dst) == "core":
        return dst != src
    return layer(src) == "engine" and (src, dst) in ENGINE_LINKS


mods = modules()
if not mods:
    print("MODULE GATE GAGAL: tidak ada modul terbaca dari settings.gradle.kts")
    sys.exit(1)
errors, used, deps = [], set(), 0
for src, d in sorted(mods.items()):
    if layer(src) is None:
        errors.append(f"{src}: belum diklasifikasikan (awalan :core:/:feature:/:engine: atau ENGINE_EXTRA)")
        continue
    f = d / "build.gradle.kts"
    if not f.exists():
        errors.append(f"{src}: {f} tidak ditemukan")
        continue
    for dst in DEP.findall(f.read_text()):
        deps += 1
        if dst not in mods:
            errors.append(f"{src}: dependensi ke {dst} yang tidak terdaftar di settings")
        elif layer(dst) is None:
            errors.append(f"{dst}: belum diklasifikasikan (dipakai {src})")
        elif allowed(src, dst):
            continue
        elif (src, dst) in KNOWN_DEBT:
            used.add((src, dst))
        else:
            errors.append(f"{src} ({layer(src)}) -> {dst} ({layer(dst)}) dilarang")
for a, b in sorted(set(KNOWN_DEBT) - used):
    print(f"PERINGATAN: utang sudah lunas, hapus dari KNOWN_DEBT: {a} -> {b}")
if errors:
    print("MODULE GATE GAGAL:")
    for e in errors:
        print(" -", e)
    sys.exit(1)
print(f"MODULE GATE OK ({len(mods)} modul, {deps} dependensi, {len(used)} utang dikenal)")

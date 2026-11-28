#!/usr/bin/env python3
"""[SHIELD-HTTP] Gerbang HTTP Shield: koneksi HTTP(S) di kode produksi harus lewat ShieldHttp.
Gagal bila: (1) `.openConnection(`/`.openStream(` muncul di berkas yang tidak ada di scripts/shield_http.baseline,
(2) jumlahnya melebihi baseline (ratchet: hanya boleh turun), atau (3) ShieldHttp kehilangan pengaman inti
(fail-closed, pengalihan manual, pemeriksaan kebijakan). Dukungan: --write-baseline untuk membuat ulang baseline."""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASE = ROOT / "scripts" / "shield_http.baseline"
SKIP = ("/build/", "/.git/", "/scripts/", "-tests/", "/test/", "/androidTest/")
HOME = "core/net/src/main/kotlin/com/molina/core/net/ShieldHttp.kt"
PAT = re.compile(r"\.(openConnection|openStream)\s*\(")
MUST = ["ShieldProxy.failClosed()", "instanceFollowRedirects = false", "check(current)"]


def scan():
    found = {}
    for p in ROOT.rglob("*.kt"):
        rel = p.relative_to(ROOT).as_posix()
        if any(x in "/" + rel for x in SKIP) or rel == HOME:
            continue
        n = 0
        for line in p.read_text(encoding="utf-8", errors="replace").splitlines():
            t = line.strip()
            if t.startswith(("//", "*", "/*")):
                continue
            n += len(PAT.findall(line))
        if n:
            found[rel] = n
    return found


# [SHIELD-REASON] Tiap koneksi langsung di baseline wajib punya alasan: "berkas jumlah # alasan".
NOREASON = "BELUM ADA ALASAN"
HEADER = "# [SHIELD-HTTP] berkas jumlah # alasan. Koneksi langsung yang masih diizinkan (ratchet, hanya boleh turun); alasan wajib.\n"


def parse_base():
    base, why = {}, {}
    if BASE.exists():
        for line in BASE.read_text(encoding="utf-8").splitlines():
            if not line.strip() or line.startswith("#"):
                continue
            left, _, reason = line.partition(" # ")
            k, v = left.rsplit(" ", 1)
            base[k] = int(v); why[k] = reason.strip()
    return base, why


def main():
    found = scan()
    base, why = parse_base()
    if "--write-baseline" in sys.argv:
        BASE.write_text(HEADER + "".join(f"{k} {v} # {why.get(k) or NOREASON}\n" for k, v in sorted(found.items())), encoding="utf-8")
        print(f"baseline ditulis: {len(found)} berkas"); return 0
    bad = []
    for k, v in found.items():
        if k not in base: bad.append(f"BARU  {k}: {v} koneksi langsung; pakai ShieldHttp")
        elif v > base[k]: bad.append(f"NAIK  {k}: {v} > baseline {base[k]}")
        elif not why.get(k) or why[k].startswith(NOREASON): bad.append(f"TANPA ALASAN  {k}: tulis alasan setelah ' # ' di baseline")
    for k, v in base.items():
        if found.get(k, 0) < v: print(f"info  {k}: turun ke {found.get(k, 0)}; perbarui baseline (--write-baseline)")
    src = (ROOT / HOME).read_text(encoding="utf-8") if (ROOT / HOME).exists() else ""
    for m in MUST:
        if m not in src: bad.append(f"ShieldHttp kehilangan pengaman: {m}")
    for b in bad: print(b)
    print(f"shield_http_gate: {'GAGAL' if bad else 'LOLOS'} ({sum(found.values())} koneksi langsung di baseline, {len(found)} berkas)")
    return 1 if bad else 0


sys.exit(main())

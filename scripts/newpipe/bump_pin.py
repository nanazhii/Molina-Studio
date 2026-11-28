#!/usr/bin/env python3
"""Naikkan pin NewPipeExtractor sekali jalan [NEWPIPE-BUMP].

Memperbarui TIGA tempat sekaligus agar tidak pernah beda:
  1. engine/newpipe/build.gradle.kts            (val newpipeVersion)
  2. NewPipeInfo.kt                             (VERSION)
  3. scripts/newpipe/pins.sha256                (hash semua jar hasil resolve)
lalu mencatat progres.md dan menjalankan telemetry_gate + bundle_gate. Bila gerbang gagal,
semua berkas dikembalikan seperti semula.

Hash TIDAK dihitung dari angan-angan: skrip memakai keluaran spike (scripts/spike-newpipe/run.sh,
baris "RESOLVED group:name:ver  sha256  berkas.jar") yang di-resolve Gradle. Skrip ini hanya
menulis ulang pin; tinjau diff-nya sebelum merge (inilah titik pemeriksaan manusia).

Pemakaian:
  python3 scripts/newpipe/bump_pin.py v0.26.6 --resolved _spike_out/resolved.txt [--dry-run]
  python3 scripts/newpipe/bump_pin.py v0.26.6            # menjalankan spike sendiri (butuh gradle + JDK 17)
Aman dijalankan berulang (idempotent): bila sudah sama, tidak ada yang ditulis.
"""
import argparse, os, re, shutil, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
GRADLE = "engine/newpipe/build.gradle.kts"
INFO = "engine/newpipe/src/main/kotlin/com/molina/engine/newpipe/NewPipeInfo.kt"
PINS = "scripts/newpipe/pins.sha256"
PROGRES = "progres.md"
SPIKE = "scripts/spike-newpipe/run.sh"

VER_RX = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")
HEX_RX = re.compile(r"^[0-9a-f]{64}$")
RES_RX = re.compile(r"^RESOLVED\s+(\S+):(\S+):(\S+)\s+([0-9a-f]{64})\s+(\S+\.jar)\s*$")


def p(rel): return os.path.join(ROOT, rel)
def rd(rel):
    with open(p(rel), encoding="utf-8") as f: return f.read()
def wr(rel, text):
    with open(p(rel), "w", encoding="utf-8", newline="\n") as f: f.write(text)
def die(msg):
    print("GAGAL: " + msg, file=sys.stderr); sys.exit(1)
def vkey(v): return tuple(int(x) for x in VER_RX.match(v).groups())


def parse_resolved(text, ver):
    """-> {nama_berkas: sha256}. Menolak hasil yang janggal."""
    out, ids = {}, {}
    for line in text.splitlines():
        if not line.startswith("RESOLVED"): continue
        m = RES_RX.match(line)
        if not m: die("baris RESOLVED tidak valid: %r" % line[:80])
        group, name, version, sha, fname = m.groups()
        if fname in out: die("nama berkas ganda di hasil resolve: " + fname)
        out[fname], ids[fname] = sha, "%s:%s:%s" % (group, name, version)
    if not out: die("tidak ada baris RESOLVED; hasil spike kosong")
    target = "NewPipeExtractor-%s.jar" % ver
    if target not in out:
        die("%s tidak ada di hasil resolve (koordinat fallback menghasilkan nama lain?). Ditemukan: %s"
            % (target, ", ".join(sorted(out))))
    if not ids[target].startswith("com.github.TeamNewPipe"):
        die("jar utama berasal dari grup tak terduga: " + ids[target])
    return out, ids


def current_pins():
    pins = {}
    for line in rd(PINS).splitlines():
        line = line.strip()
        if not line or line.startswith("#"): continue
        h, n = line.split(None, 1)
        pins[n.strip()] = h
    return pins


def stem_ver(fname):
    """'jsoup-1.22.2.jar' -> ('jsoup', '1.22.2'); tanpa versi -> (stem, '')."""
    s = fname[:-4]
    m = re.match(r"^(.*?)-(v?\d[\w.\-]*)$", s)
    return (m.group(1), m.group(2)) if m else (s, "")


def diff(old, new):
    on = {stem_ver(n)[0]: n for n in old}
    nn = {stem_ver(n)[0]: n for n in new}
    lines = []
    for stem in sorted(set(on) | set(nn)):
        o, n = on.get(stem), nn.get(stem)
        if o and not n: lines.append("  - DIHAPUS   %s" % o)
        elif n and not o: lines.append("  + BARU      %s   <-- dependensi transitif baru, TINJAU" % n)
        elif o == n and old[o] == new[n]: lines.append("    sama      %s" % n)
        elif o == n: lines.append("  ! HASH BEDA %s   <-- versi sama, isi beda, SANGAT MENCURIGAKAN" % n)
        else: lines.append("  ~ NAIK      %s -> %s" % (o, n))
    return lines


def render_pins(new):
    head = [l for l in rd(PINS).splitlines() if l.strip().startswith("#")]
    body = ["%s  %s" % (new[n], n) for n in sorted(new)]
    return "\n".join(head + body) + "\n"


def run_spike(ver):
    if not shutil.which("gradle"): die("gradle tidak ada di PATH. Pakai workflow CI bump-newpipe, atau berikan --resolved.")
    out = tempfile.mkdtemp(prefix="bump_newpipe_")
    r = subprocess.run(["bash", p(SPIKE), ver, out])
    if r.returncode != 0: die("spike gagal (lihat keluaran di atas)")
    with open(os.path.join(out, "resolved.txt"), encoding="utf-8") as f: return f.read()


def progres_entry(ver, old_ver, jars):
    tag = "[NEWPIPE-PIN %s]" % ver
    return tag, (
        "\n## 2026-10-31 - Pin NewPipeExtractor dinaikkan ke %s %s\n"
        "- [x] chore(newpipe): %s -> %s lewat scripts/newpipe/bump_pin.py; build.gradle.kts, NewPipeInfo.kt, dan pins.sha256 diperbarui bersamaan (%d jar dipin)\n"
        "- [x] Hash berasal dari hasil resolve Gradle (spike), bukan dihitung manual; diff dependensi harus ditinjau sebelum merge\n"
        "- [x] Dampak: Gradle (1 konstanta versi), AndroidManifest dan JNI mpv TIDAK disentuh\n"
        "- [ ] Compose/Kotlin belum dikompilasi: tunggu CI (verifyNewPipePins akan gagal bila classpath :engine:newpipe beda dari hasil spike)\n"
        % (ver, tag, old_ver, ver, jars))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("version", help="tag NewPipeExtractor, contoh v0.26.6")
    ap.add_argument("--resolved", help="berkas resolved.txt dari spike (tanpa ini, spike dijalankan lokal)")
    ap.add_argument("--dry-run", action="store_true", help="hanya tampilkan diff, tidak menulis apa pun")
    ap.add_argument("--allow-downgrade", action="store_true")
    ap.add_argument("--skip-gates", action="store_true", help="hanya untuk uji; CI tetap menjalankan gerbang")
    a = ap.parse_args()

    ver = a.version
    if not VER_RX.match(ver): die("format versi salah: %r (harap vX.Y.Z)" % ver)

    gm = re.search(r'val newpipeVersion = "([^"]+)"', rd(GRADLE))
    im = re.search(r'VERSION = "([^"]+)"', rd(INFO))
    if not gm or not im: die("pola versi tidak ditemukan di build.gradle.kts / NewPipeInfo.kt (format berubah?)")
    if gm.group(1) != im.group(1): die("kondisi awal tidak konsisten: Gradle %s != NewPipeInfo %s" % (gm.group(1), im.group(1)))
    old_ver = gm.group(1)
    if vkey(ver) < vkey(old_ver) and not a.allow_downgrade:
        die("%s lebih lama dari %s; tambahkan --allow-downgrade bila memang disengaja" % (ver, old_ver))

    if a.resolved:
        with open(a.resolved, encoding="utf-8") as f: text = f.read()
    else:
        text = run_spike(ver)
    new, _ids = parse_resolved(text, ver)
    old = current_pins()

    print("NewPipeExtractor: %s -> %s" % (old_ver, ver))
    for l in diff(old, new): print(l)

    new_gradle = re.sub(r'(val newpipeVersion = ")[^"]+(")', r"\g<1>%s\g<2>" % ver, rd(GRADLE), count=1)
    new_info = re.sub(r'(VERSION = ")[^"]+(")', r"\g<1>%s\g<2>" % ver, rd(INFO), count=1)
    new_pins = render_pins(new)
    tag, entry = progres_entry(ver, old_ver, len(new))
    prog = rd(PROGRES)
    new_prog = prog if tag in prog else (prog if prog.endswith("\n") else prog + "\n") + entry

    changes = {GRADLE: new_gradle, INFO: new_info, PINS: new_pins, PROGRES: new_prog}
    todo = {k: v for k, v in changes.items() if rd(k) != v}
    if not todo:
        print("Tidak ada perubahan: pin sudah di %s (idempotent)." % ver); return
    if a.dry_run:
        print("[dry-run] akan menulis: " + ", ".join(sorted(todo))); return

    backup = {k: rd(k) for k in todo}
    for k, v in todo.items(): wr(k, v)
    if not a.skip_gates:
        for g in ("scripts/telemetry_gate.py", "scripts/bundle_gate.py"):
            r = subprocess.run([sys.executable, p(g)], cwd=ROOT)
            if r.returncode != 0:
                for k, v in backup.items(): wr(k, v)
                die("%s gagal; semua berkas dikembalikan." % g)
    print("SELESAI: %s ditulis. Tinjau 'git diff', lalu commit." % ", ".join(sorted(todo)))
    print("Pesan commit: chore(newpipe): naikkan pin ke %s" % ver)


if __name__ == "__main__":
    main()

"""Pustaka bersama alat bump pin Molina [PINLIB]: tulis berkas atomik-logis, jalankan gerbang,
kembalikan semua berkas bila gerbang gagal, dan catat progres.md tanpa duplikat."""
import os, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GATES = ("scripts/telemetry_gate.py", "scripts/bundle_gate.py")


def p(rel): return os.path.join(ROOT, rel)
def rd(rel):
    with open(p(rel), encoding="utf-8") as f: return f.read()
def wr(rel, text):
    with open(p(rel), "w", encoding="utf-8", newline="\n") as f: f.write(text)
def die(msg):
    print("GAGAL: " + msg, file=sys.stderr); sys.exit(1)


def with_entry(progres_text, tag, entry):
    """Tambahkan entri sekali saja (idempotent berdasarkan tag)."""
    if tag in progres_text: return progres_text
    return (progres_text if progres_text.endswith("\n") else progres_text + "\n") + entry


def apply_changes(changes, skip_gates=False):
    """changes: {path_relatif: isi_baru}. Menulis yang berubah, jalankan gerbang, rollback bila gagal.
    Mengembalikan daftar berkas yang ditulis (kosong = tidak ada perubahan)."""
    todo = {k: v for k, v in changes.items() if rd(k) != v}
    if not todo: return []
    backup = {k: rd(k) for k in todo}
    for k, v in todo.items(): wr(k, v)
    if not skip_gates:
        for g in GATES:
            if subprocess.run([sys.executable, p(g)], cwd=ROOT).returncode != 0:
                for k, v in backup.items(): wr(k, v)
                die("%s gagal; semua berkas dikembalikan." % g)
    return sorted(todo)

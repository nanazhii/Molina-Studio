#!/usr/bin/env python3
"""Uji mandiri [CATALOG-SELFTEST] untuk scripts/catalog_gate.py.
Menyalin berkas yang relevan ke direktori sementara, memasukkan satu pelanggaran per skenario, lalu memastikan gate
GAGAL dengan pesan yang diharapkan. Skenario dasar (tanpa pelanggaran) harus LOLOS. Mencegah gate dilonggarkan
diam-diam: bila sebuah aturan berhenti bekerja, CI gagal di sini."""
import pathlib, re, shutil, subprocess, sys, tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SKIP = {".git", ".gradle", "build", "node_modules", ".idea"}
DERIVED = "core/policy/src/main/kotlin/com/molina/core/policy/CatalogVersions.kt"
SMOKE_S = "scripts/smoke-newpipe/settings.gradle.kts"
SMOKE_B = "scripts/smoke-newpipe/build.gradle.kts"
SPIKE_S = "scripts/spike-newpipe/settings.gradle.kts"


def snapshot(dst):
    keep = ["scripts/catalog_gate.py", "scripts/gen_catalog_versions.py", "gradle/libs.versions.toml", DERIVED]
    for base, dirs, names in __import__("os").walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP]
        for n in names:
            if n in ("build.gradle.kts", "settings.gradle.kts"):
                keep.append((pathlib.Path(base) / n).relative_to(ROOT).as_posix())
    for rel in keep:
        t = dst / rel
        t.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / rel, t)


def sub(rel, pattern, repl, flags=0):
    def f(root):
        p = root / rel
        s = p.read_text(encoding="utf-8")
        n, c = re.subn(pattern, repl, s, flags=flags)
        if c == 0:
            raise RuntimeError("fixture berubah: pola tidak ditemukan di %s: %s" % (rel, pattern))
        p.write_text(n, encoding="utf-8")
    return f


def append(rel, text):
    def f(root):
        p = root / rel
        p.write_text(p.read_text(encoding="utf-8") + text, encoding="utf-8")
    return f


# (nama, mutasi, kode keluar yang diharapkan, potongan pesan yang diharapkan)
SCENARIOS = [
    ("dasar tanpa pelanggaran", None, 0, "CATALOG GATE OK"),
    ("koordinat literal", append(SMOKE_B, '\ndependencies { implementation("com.example:foo:1.0") }\n'), 1, "koordinat literal"),
    ("alias tidak ada di catalog", append(SMOKE_B, "\ndependencies { implementation(libs.alias.tidak.ada) }\n"), 1, "tidak ada di catalog"),
    ("catalog tidak sinkron dengan CatalogVersions.kt", sub(DERIVED, r'(const val SORA = ")[^"]+', r"\g<1>0.0.0"), 1, "GAGAL"),
    ("mandiri tanpa import katalog", sub(SMOKE_S, r"\n\s*versionCatalogs \{.*?\n    \}", "", re.S), 1, "tidak meng-import katalog root"),
    ("path katalog salah", sub(SMOKE_S, r"\.\./\.\./gradle", "../gradle"), 1, "tidak menunjuk"),
    ("tanpa repositoriesMode", sub(SPIKE_S, r"\n\s*repositoriesMode\.set\([^\n]*", ""), 1, "repositoriesMode"),
    ("JitPack tanpa filter grup", sub(SPIKE_S, r"\n\s*filter \{[^\n]*\}", ""), 1, "JitPack hanya boleh"),
]


def main():
    gagal = 0
    for nama, mutasi, kode, pesan in SCENARIOS:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            snapshot(root)
            if mutasi:
                mutasi(root)
            r = subprocess.run([sys.executable, str(root / "scripts" / "catalog_gate.py")], capture_output=True, text=True)
            out = r.stdout + r.stderr
            if r.returncode == kode and pesan in out:
                print("OK    %s" % nama)
            else:
                gagal += 1
                print("GAGAL %s: harapan kode=%d '%s', hasil kode=%d" % (nama, kode, pesan, r.returncode))
                print("      " + out.strip().replace("\n", "\n      ")[:600])
    if gagal:
        print("CATALOG SELFTEST GAGAL (%d dari %d skenario)" % (gagal, len(SCENARIOS)))
        return 1
    print("CATALOG SELFTEST OK (%d skenario)" % len(SCENARIOS))
    return 0


if __name__ == "__main__":
    sys.exit(main())

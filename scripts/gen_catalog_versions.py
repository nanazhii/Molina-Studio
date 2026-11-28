#!/usr/bin/env python3
"""Turunkan CatalogVersions.kt dari gradle/libs.versions.toml (satu sumber versi) [CATALOG].
Tanpa argumen: tulis berkas. Dengan --check: gagal bila berkas turunan tidak sinkron dengan catalog."""
import pathlib, sys, tomllib

ROOT = pathlib.Path(__file__).resolve().parent.parent
TOML = ROOT / "gradle" / "libs.versions.toml"
OUT = ROOT / "core/policy/src/main/kotlin/com/molina/core/policy/CatalogVersions.kt"
# Konstanta Kotlin <- kunci [versions]. Hanya versi yang ditampilkan di layar Komponen bawaan.
KEYS = (("SORA", "sora"), ("COMMONS_COMPRESS", "commons-compress"), ("ZIP4J", "zip4j"), ("TUKAANI_XZ", "tukaani-xz"))


def render():
    ver = tomllib.loads(TOML.read_text(encoding="utf-8")).get("versions", {})
    missing = [k for _, k in KEYS if k not in ver]
    if missing:
        sys.exit("GAGAL: kunci versi tidak ada di gradle/libs.versions.toml: " + ", ".join(missing))
    body = "".join('    const val %s = "%s"\n' % (c, ver[k]) for c, k in KEYS)
    return (
        "package com.molina.core.policy\n\n"
        "// DITURUNKAN dari gradle/libs.versions.toml oleh scripts/gen_catalog_versions.py. Jangan diedit manual:\n"
        "// ubah catalog, lalu jalankan skrip itu (scripts/catalog_gate.py menggagalkan CI bila tidak sinkron).\n"
        "/** Versi dependensi yang tampil di Komponen bawaan; satu sumber: gradle/libs.versions.toml. */\n"
        "object CatalogVersions {\n" + body + "}\n"
    )


if __name__ == "__main__":
    want = render()
    if "--check" in sys.argv:
        have = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if have != want:
            sys.exit("GAGAL: CatalogVersions.kt tidak sinkron dengan catalog; jalankan: python3 scripts/gen_catalog_versions.py")
        print("OK: CatalogVersions.kt sinkron dengan catalog")
    elif OUT.exists() and OUT.read_text(encoding="utf-8") == want:
        print("SKIP: CatalogVersions.kt sudah sinkron")
    else:
        OUT.write_text(want, encoding="utf-8"); print("OK: CatalogVersions.kt ditulis")

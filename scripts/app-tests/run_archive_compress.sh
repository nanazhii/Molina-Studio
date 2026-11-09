#!/usr/bin/env bash
# Uji JVM kompres ArchiveEngine (semua format x level, AES, batal, tautan, unicode, banyak berkas) + verifikasi silang
# dengan pustaka lain (python zipfile/tarfile/py7zr/pyzipper). Butuh sama seperti run_archive_engine.sh.
set -euo pipefail
export LC_ALL=C.UTF-8 # nama berkas non-ASCII: tanpa ini JVM memakai ASCII dan mengubahnya jadi '?'
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
: "${ARCHIVE_DEPS:?isi ARCHIVE_DEPS dengan daftar JAR dependensi (dipisah titik dua)}"
OUT="$(mktemp -d)"; FX="$(mktemp -d)"; KEEP="$(mktemp -d)"
trap 'rm -rf "$OUT" "$FX" "$KEEP"' EXIT
python3 scripts/app-tests/make_archive_fixtures.py "$FX"
C=app/src/main/java/com/molina/studio/cinema
"$KC" -nowarn -cp "$ARCHIVE_DEPS" -d "$OUT" $C/FileOps.kt $C/ArchiveNames.kt $C/ArchiveEngine.kt scripts/app-tests/ArchiveCompressTests.kt
java -cp "$OUT:$ARCHIVE_DEPS:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ArchiveCompressTestsKt "$FX" "$KEEP"
python3 scripts/app-tests/verify_compress.py "$KEEP" "$FX"

#!/usr/bin/env bash
# Uji JVM ArchiveEngine (list/ekstrak tar*, 7z, zip, gz/bz2/xz, sandi, zip-slip, batal, rusak) dengan arsip nyata.
# Butuh: kotlinc (>= 2.1), python3 + `pip install py7zr`, dan JAR dependensi di ARCHIVE_DEPS (dipisah ':'):
#   commons-compress 1.27.1, commons-io, commons-lang3, commons-codec, org.tukaani:xz 1.10, zip4j 2.11.5
# Contoh: ARCHIVE_DEPS=$(ls ~/jars/*.jar | tr '\n' ':') KOTLINC=/opt/kotlinc/bin/kotlinc bash scripts/app-tests/run_archive_engine.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
: "${ARCHIVE_DEPS:?isi ARCHIVE_DEPS dengan daftar JAR dependensi (dipisah titik dua)}"
OUT="$(mktemp -d)"
FX="$(mktemp -d)"
trap 'rm -rf "$OUT" "$FX"' EXIT
python3 scripts/app-tests/make_archive_fixtures.py "$FX"
C=app/src/main/java/com/molina/studio/cinema
"$KC" -nowarn -cp "$ARCHIVE_DEPS" -d "$OUT" $C/FileOps.kt $C/ArchiveNames.kt $C/ArchiveEngine.kt scripts/app-tests/ArchiveEngineTests.kt
java -cp "$OUT:$ARCHIVE_DEPS:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ArchiveEngineTestsKt "$FX"

#!/usr/bin/env bash
# Uji JVM murni untuk ArchiveNames (deteksi format, anti zip-slip, seleksi entri) di :app.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -d "$OUT" core/fileops/src/main/kotlin/com/molina/core/fileops/ArchiveNames.kt scripts/app-tests/ArchiveNamesTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ArchiveNamesTestsKt

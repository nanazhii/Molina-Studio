#!/usr/bin/env bash
# [ARCHIVE-DEST-2] Uji JVM murni untuk ExtractDest (tujuan ekstrak) di :feature:explorer.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -d "$OUT" core/fileops/src/main/kotlin/com/molina/core/fileops/ArchiveNames.kt \
  feature/explorer/src/main/kotlin/com/molina/explorer/ArchiveExtractDest.kt scripts/app-tests/ExtractDestTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ExtractDestTestsKt

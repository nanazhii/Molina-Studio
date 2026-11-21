#!/usr/bin/env bash
# Uji JVM murni untuk DownloadPreset (preset cepat form Unduhan) di :core:diag.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
DIAG=core/diag/src/main/kotlin/com/molina/core/diag
"$KC" -nowarn -d "$OUT" \
  $DIAG/LogRedact.kt core/download/src/main/kotlin/com/molina/core/download/DownloadCommand.kt core/download/src/main/kotlin/com/molina/core/download/DownloadUi.kt core/download/src/main/kotlin/com/molina/core/download/DownloadPreset.kt \
  scripts/core-tests/DownloadPresetTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" DownloadPresetTestsKt

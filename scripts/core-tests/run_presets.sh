#!/usr/bin/env bash
# Uji JVM murni untuk DownloadPreset (preset cepat form Unduhan) di :core:common.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
C=core/common/src/main/kotlin/com/molina/core/common
"$KC" -nowarn -d "$OUT" \
  $C/LogRedact.kt $C/DownloadCommand.kt $C/DownloadUi.kt $C/DownloadPreset.kt \
  scripts/core-tests/DownloadPresetTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" DownloadPresetTestsKt

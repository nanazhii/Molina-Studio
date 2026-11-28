#!/usr/bin/env bash
# Uji JVM murni untuk DownloadChoices (opsi subtitle/sampul/batas kecepatan) + riwayat di :core:diag.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
DIAG=core/diag/src/main/kotlin/com/molina/core/diag
"$KC" -nowarn -d "$OUT" \
  $DIAG/LogRedact.kt core/json/src/main/kotlin/com/molina/core/json/MiniJson.kt core/json/src/main/kotlin/com/molina/core/json/JsonCursor.kt core/download/src/main/kotlin/com/molina/core/download/DownloadEvent.kt core/download/src/main/kotlin/com/molina/core/download/DownloadSession.kt core/download/src/main/kotlin/com/molina/core/download/DownloadErrorText.kt \
  core/download/src/main/kotlin/com/molina/core/download/DownloadCommand.kt core/download/src/main/kotlin/com/molina/core/download/DownloadUi.kt core/download/src/main/kotlin/com/molina/core/download/DownloadHistory.kt \
  scripts/core-tests/DownloadChoicesTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" DownloadChoicesTestsKt

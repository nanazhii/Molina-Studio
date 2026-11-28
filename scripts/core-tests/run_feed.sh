#!/usr/bin/env bash
# Uji JVM murni untuk DownloadCommand + DownloadFeed (:core:diag). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
DIAG=core/diag/src/main/kotlin/com/molina/core/diag
"$KC" -nowarn -d "$OUT" \
  core/json/src/main/kotlin/com/molina/core/json/MiniJson.kt core/json/src/main/kotlin/com/molina/core/json/JsonCursor.kt $DIAG/LogRedact.kt core/download/src/main/kotlin/com/molina/core/download/DownloadEvent.kt core/download/src/main/kotlin/com/molina/core/download/DownloadSession.kt core/download/src/main/kotlin/com/molina/core/download/DownloadErrorText.kt \
  core/download/src/main/kotlin/com/molina/core/download/DownloadCommand.kt core/download/src/main/kotlin/com/molina/core/download/DownloadFeed.kt \
  scripts/core-tests/DownloadFeedTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" DownloadFeedTestsKt

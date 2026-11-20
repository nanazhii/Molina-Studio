#!/usr/bin/env bash
# Uji JVM murni untuk DownloadHistory (susun entri, Ulangi, serialisasi JSON, ketahanan data rusak) di :core:common.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
C=core/common/src/main/kotlin/com/molina/core/common
"$KC" -nowarn -d "$OUT" \
  $C/LogRedact.kt core/json/src/main/kotlin/com/molina/core/json/MiniJson.kt core/json/src/main/kotlin/com/molina/core/json/JsonCursor.kt core/download/src/main/kotlin/com/molina/core/download/DownloadEvent.kt core/download/src/main/kotlin/com/molina/core/download/DownloadSession.kt core/download/src/main/kotlin/com/molina/core/download/DownloadErrorText.kt \
  core/download/src/main/kotlin/com/molina/core/download/DownloadCommand.kt core/download/src/main/kotlin/com/molina/core/download/DownloadUi.kt core/download/src/main/kotlin/com/molina/core/download/DownloadHistory.kt \
  scripts/core-tests/DownloadHistoryTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" DownloadHistoryTestsKt

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
  $C/LogRedact.kt $C/MiniJson.kt $C/JsonCursor.kt $C/DownloadEvent.kt $C/DownloadSession.kt $C/DownloadErrorText.kt \
  $C/DownloadCommand.kt $C/DownloadUi.kt $C/DownloadHistory.kt \
  scripts/core-tests/DownloadHistoryTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" DownloadHistoryTestsKt

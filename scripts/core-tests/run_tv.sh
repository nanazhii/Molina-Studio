#!/usr/bin/env bash
# Uji JVM murni untuk TvGuide (model Mode TV penjelajah berkas Molina Cinema, :core:library).
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
LIB=core/library/src/main/kotlin/com/molina/core/library
"$KC" -nowarn -d "$OUT" \
  core/json/src/main/kotlin/com/molina/core/json/MiniJson.kt core/json/src/main/kotlin/com/molina/core/json/JsonCursor.kt $LIB/MediaSidecar.kt core/json/src/main/kotlin/com/molina/core/json/InfoJsonKeys.kt $LIB/ShelfCard.kt $LIB/ResumeMark.kt $LIB/ShelfScan.kt $LIB/TrashBin.kt $LIB/StorageGuard.kt $LIB/TvGuide.kt \
  scripts/core-tests/TvGuideTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" TvGuideTestsKt

#!/usr/bin/env bash
# Uji JVM murni untuk TvGuide (model Mode TV penjelajah berkas Molina Cinema, :core:common).
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
C=core/common/src/main/kotlin/com/molina/core/common
"$KC" -nowarn -d "$OUT" \
  core/json/src/main/kotlin/com/molina/core/json/MiniJson.kt core/json/src/main/kotlin/com/molina/core/json/JsonCursor.kt $C/MediaSidecar.kt core/json/src/main/kotlin/com/molina/core/json/InfoJsonKeys.kt $C/ShelfCard.kt $C/ResumeMark.kt $C/ShelfScan.kt $C/TrashBin.kt $C/TvGuide.kt \
  scripts/core-tests/TvGuideTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" TvGuideTestsKt

#!/usr/bin/env bash
# Uji JVM murni untuk ShelfCard (model kartu rak film Molina Cinema, :core:common).
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
C=core/common/src/main/kotlin/com/molina/core/common
"$KC" -nowarn -d "$OUT" \
  $C/MiniJson.kt $C/JsonCursor.kt $C/MediaSidecar.kt $C/ResumeMark.kt $C/ShelfScan.kt $C/SidecarRows.kt $C/ShelfCard.kt \
  scripts/core-tests/ShelfCardTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ShelfCardTestsKt

#!/usr/bin/env bash
# Uji JVM murni untuk MediaSidecar (poster + metadata ringkas), event done.sidecars, dan argumen --sidecar (:core:library, :core:diag).
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
LIB=core/library/src/main/kotlin/com/molina/core/library
DIAG=core/diag/src/main/kotlin/com/molina/core/diag
"$KC" -nowarn -d "$OUT" \
  core/json/src/main/kotlin/com/molina/core/json/MiniJson.kt core/json/src/main/kotlin/com/molina/core/json/JsonCursor.kt $DIAG/LogRedact.kt core/download/src/main/kotlin/com/molina/core/download/DownloadEvent.kt core/download/src/main/kotlin/com/molina/core/download/DownloadCommand.kt $LIB/MediaSidecar.kt core/json/src/main/kotlin/com/molina/core/json/InfoJsonKeys.kt $LIB/ThumbMath.kt \
  scripts/core-tests/MediaSidecarTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" MediaSidecarTestsKt

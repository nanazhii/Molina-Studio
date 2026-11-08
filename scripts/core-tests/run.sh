#!/usr/bin/env bash
# Uji JVM murni untuk parser event unduhan, reducer sesi, dan kebijakan skrip bawaan (:core:common).
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
C=core/common/src/main/kotlin/com/molina/core/common
"$KC" -nowarn -d "$OUT" \
  $C/MiniJson.kt $C/JsonCursor.kt $C/DownloadEvent.kt $C/DownloadSession.kt $C/DownloadErrorText.kt $C/BundledScriptPolicy.kt \
  scripts/core-tests/DownloadTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" DownloadTestsKt

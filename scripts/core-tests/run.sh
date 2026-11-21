#!/usr/bin/env bash
# Uji JVM murni untuk parser event unduhan, reducer sesi, dan kebijakan skrip bawaan (:core:policy).
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
POL=core/policy/src/main/kotlin/com/molina/core/policy
"$KC" -nowarn -d "$OUT" \
  core/json/src/main/kotlin/com/molina/core/json/MiniJson.kt core/json/src/main/kotlin/com/molina/core/json/JsonCursor.kt core/download/src/main/kotlin/com/molina/core/download/DownloadEvent.kt core/download/src/main/kotlin/com/molina/core/download/DownloadSession.kt core/download/src/main/kotlin/com/molina/core/download/DownloadErrorText.kt $POL/BundledScriptPolicy.kt \
  scripts/core-tests/DownloadTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" DownloadTestsKt

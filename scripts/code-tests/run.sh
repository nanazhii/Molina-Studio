#!/usr/bin/env bash
# Uji JVM murni untuk pratinjau/aksi Code. Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -d "$OUT" \
  feature/code/src/main/kotlin/com/molina/code/preview/*.kt \
  scripts/code-tests/PreviewTests.kt
java -cp "$OUT:$("$KC" -version >/dev/null 2>&1; dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" PreviewTestsKt

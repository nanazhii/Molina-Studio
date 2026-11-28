#!/usr/bin/env bash
# Uji JVM murni untuk VideoKey dan SeenMarks (penanda diunduh/pernah diputar) di :core:library.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
LIB=core/library/src/main/kotlin/com/molina/core/library
"$KC" -nowarn -d "$OUT" $LIB/SeenMarks.kt scripts/core-tests/SeenMarksTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" SeenMarksTestsKt

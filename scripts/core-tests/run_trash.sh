#!/usr/bin/env bash
# Uji JVM murni untuk TrashBin (Recycle Bin: pindah, pulihkan, auto-purge, anti-tamper) di :core:library.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
LIB=core/library/src/main/kotlin/com/molina/core/library
"$KC" -nowarn -d "$OUT" $LIB/TrashBin.kt scripts/core-tests/TrashBinTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" TrashBinTestsKt

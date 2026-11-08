#!/usr/bin/env bash
# Uji JVM murni untuk SearchOrder (urutan waktu, saring, kanal terkait) di :core:common.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
C=core/common/src/main/kotlin/com/molina/core/common
"$KC" -nowarn -d "$OUT" $C/SearchOrder.kt $C/RelativeTime.kt $C/StreamResolver.kt scripts/core-tests/SearchOrderTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" SearchOrderTestsKt

#!/usr/bin/env bash
# Uji JVM murni untuk SearchOrder (urutan waktu, saring, kanal terkait) di modul core.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
# (tidak memakai berkas core lama)
"$KC" -nowarn -d "$OUT" core/stream/src/main/kotlin/com/molina/core/stream/SearchOrder.kt core/stream/src/main/kotlin/com/molina/core/stream/RelativeTime.kt core/stream/src/main/kotlin/com/molina/core/stream/StreamResolver.kt scripts/core-tests/SearchOrderTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" SearchOrderTestsKt

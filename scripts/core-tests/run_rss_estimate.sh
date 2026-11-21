#!/usr/bin/env bash
# Uji JVM murni untuk RssEstimate (perkiraan RAM komponen dalam-proses (mpv)) di :core:system.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
SYS=core/system/src/main/kotlin/com/molina/core/system
"$KC" -nowarn -d "$OUT" $SYS/RssEstimate.kt scripts/core-tests/RssEstimateTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" RssEstimateTestsKt

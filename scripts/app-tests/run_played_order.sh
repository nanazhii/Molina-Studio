#!/usr/bin/env bash
# Uji JVM murni untuk PlayedOrder (urutan "terakhir diputar dulu" Penjelajah). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=...
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
E=feature/explorer/src/main/kotlin/com/molina/explorer
"$KC" -nowarn -d "$OUT" $E/W7Model.kt $E/PlayedOrder.kt scripts/app-tests/PlayedOrderTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" com.molina.explorer.PlayedOrderTestsKt

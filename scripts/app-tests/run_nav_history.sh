#!/usr/bin/env bash
# Uji JVM murni untuk NavHistory (riwayat Mundur/Maju Penjelajah). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=...
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -d "$OUT" feature/explorer/src/main/kotlin/com/molina/explorer/NavHistory.kt scripts/app-tests/NavHistoryTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" com.molina.explorer.NavHistoryTestsKt

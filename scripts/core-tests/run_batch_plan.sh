#!/usr/bin/env bash
# Uji JVM murni untuk BatchPlan (rencana kirim banyak URL ke Unduhan) di modul core.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
# (tidak memakai berkas core lama)
"$KC" -nowarn -d "$OUT" core/download/src/main/kotlin/com/molina/core/download/BatchPlan.kt scripts/core-tests/BatchPlanTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" BatchPlanTestsKt

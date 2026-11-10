#!/usr/bin/env bash
# [EXT-DIFF3] Uji JVM murni rencana pemulihan (RestorePlan). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
F=feature/code/src/main/kotlin/com/molina/code/history
"$KC" -nowarn -d "$OUT" $F/HistoryStore.kt $F/VersionCompare.kt $F/RestorePlan.kt scripts/code-tests/RestorePlanTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" RestorePlanTestsKt

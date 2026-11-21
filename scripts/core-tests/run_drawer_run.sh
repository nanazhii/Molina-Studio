#!/usr/bin/env bash
# Uji JVM murni untuk DrawerRun (:core:exec). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
E=core/exec/src/main/kotlin/com/molina
"$KC" -nowarn -d "$OUT" $E/core/exec/DrawerRun.kt $E/code/preview/RunPlan.kt $E/code/preview/ScriptLaunch.kt scripts/core-tests/DrawerRunTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" DrawerRunTestsKt

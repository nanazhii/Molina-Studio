#!/usr/bin/env bash
# Uji JVM murni untuk ScriptFolder (daftar Script Execution mengikuti folder Molina/Scripts). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -d "$OUT" \
  core/exec/src/main/kotlin/com/molina/exec/plan/RunPlan.kt core/exec/src/main/kotlin/com/molina/exec/plan/ScriptLaunch.kt core/exec/src/main/kotlin/com/molina/exec/run/ScriptFolder.kt \
  scripts/script-tests/ScriptFolderTests.kt
java -cp "$OUT:$(dirname "$(readlink -f "$(command -v "$KC")")")/../lib/kotlin-stdlib.jar" ScriptFolderTestsKt

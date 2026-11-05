#!/usr/bin/env bash
# Uji JVM murni untuk ScriptFolder (daftar Script Execution mengikuti folder Molina/Scripts). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
P=feature/code/src/main/kotlin/com/molina/code
"$KC" -nowarn -d "$OUT" \
  $P/preview/RunPlan.kt $P/preview/ScriptLaunch.kt $P/run/ScriptFolder.kt \
  scripts/code-tests/ScriptFolderTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ScriptFolderTestsKt

#!/usr/bin/env bash
# Uji JVM murni untuk PythonTerminalRun (heredoc, nama .py otomatis, perintah berkas). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -d "$OUT" \
  core/exec/src/main/kotlin/com/molina/code/preview/ScriptLaunch.kt \
  core/exec/src/main/kotlin/com/molina/code/preview/RunPlan.kt \
  core/exec/src/main/kotlin/com/molina/code/preview/CommandBlockSpec.kt \
  feature/code/src/main/kotlin/com/molina/code/preview/PythonTerminalRun.kt \
  scripts/code-tests/PythonRunTests.kt
java -cp "$OUT:$(dirname "$(readlink -f "$(command -v "$KC")")")/../lib/kotlin-stdlib.jar" PythonRunTestsKt

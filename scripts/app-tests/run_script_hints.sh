#!/usr/bin/env bash
# [SCRIPT-HINT] Uji ScriptHintParser (parser keluaran molina-run --scan dan tanda tangan folder, JVM murni).
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -d "$OUT/cls" app/src/main/java/com/molina/studio/code/ScriptHintParser.kt scripts/app-tests/ScriptHintParserTests.kt
java -cp "$OUT/cls:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ScriptHintParserTestsKt

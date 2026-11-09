#!/usr/bin/env bash
# Uji JVM murni untuk ScriptCommand (argumen proot + validasi args/env). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
C=core/common/src/main/kotlin/com/molina/core/common
"$KC" -nowarn -d "$OUT" \
  $C/YtdlpCommand.kt $C/YtdlpParse.kt $C/StreamResolver.kt $C/LogRedact.kt $C/MiniJson.kt $C/JsonCursor.kt $C/StreamHeaders.kt \
  app/src/main/java/com/molina/studio/code/ScriptCommand.kt \
  scripts/code-tests/ScriptCommandTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ScriptCommandTestsKt

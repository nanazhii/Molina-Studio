#!/usr/bin/env bash
# Uji JVM murni untuk ScriptCommand (argumen proot + validasi args/env). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
C=core/common/src/main/kotlin/com/molina/core/common
NET_D=core/net/src/main/kotlin/com/molina/core/net
"$KC" -nowarn -d "$OUT" \
  core/download/src/main/kotlin/com/molina/core/download/YtdlpCommand.kt core/stream/src/main/kotlin/com/molina/core/stream/YtdlpFlags.kt core/stream/src/main/kotlin/com/molina/core/stream/YtdlpParse.kt core/json/src/main/kotlin/com/molina/core/json/InfoJsonKeys.kt core/stream/src/main/kotlin/com/molina/core/stream/StreamResolver.kt $C/LogRedact.kt core/json/src/main/kotlin/com/molina/core/json/MiniJson.kt core/json/src/main/kotlin/com/molina/core/json/JsonCursor.kt $NET_D/StreamHeaders.kt \
  app/src/main/java/com/molina/studio/code/ScriptCommand.kt \
  scripts/code-tests/ScriptCommandTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ScriptCommandTestsKt

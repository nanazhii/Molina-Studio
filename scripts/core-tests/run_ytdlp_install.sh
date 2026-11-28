#!/usr/bin/env bash
# Uji JVM murni untuk YtdlpInstall (allowlist host unduhan, cek format zipapp, parse SHA2-256SUMS) di :core:download.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
DIAG=core/diag/src/main/kotlin/com/molina/core/diag
"$KC" -nowarn -d "$OUT" \
  core/download/src/main/kotlin/com/molina/core/download/YtdlpCommand.kt core/stream/src/main/kotlin/com/molina/core/stream/YtdlpFlags.kt core/stream/src/main/kotlin/com/molina/core/stream/YtdlpParse.kt core/json/src/main/kotlin/com/molina/core/json/InfoJsonKeys.kt core/stream/src/main/kotlin/com/molina/core/stream/StreamResolver.kt $DIAG/LogRedact.kt core/json/src/main/kotlin/com/molina/core/json/MiniJson.kt core/json/src/main/kotlin/com/molina/core/json/JsonCursor.kt core/net/src/main/kotlin/com/molina/core/net/StreamHeaders.kt \
  scripts/core-tests/YtdlpInstallTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" YtdlpInstallTestsKt

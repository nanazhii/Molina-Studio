#!/usr/bin/env bash
# [ERR-PRUNE] Uji JVM murni untuk M3uPrune dan IptvLocal.prune di :core:net. Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
NET_D=core/net/src/main/kotlin/com/molina/core/net
"$KC" -nowarn -d "$OUT" core/library/src/main/kotlin/com/molina/core/library/*.kt core/system/src/main/kotlin/com/molina/core/system/*.kt \
  core/policy/src/main/kotlin/com/molina/core/policy/*.kt core/diag/src/main/kotlin/com/molina/core/diag/*.kt \
  core/json/src/main/kotlin/com/molina/core/json/*.kt core/stream/src/main/kotlin/com/molina/core/stream/*.kt \
  core/download/src/main/kotlin/com/molina/core/download/*.kt $NET_D/*.kt core/api/src/main/kotlin/com/molina/core/api/LogContract.kt \
  scripts/core-tests/stubs/FlowStub.kt scripts/core-tests/M3uPruneTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" M3uPruneTestsKt

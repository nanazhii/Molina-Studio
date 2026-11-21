#!/usr/bin/env bash
# Uji JVM murni untuk Molina Shield: ShieldPolicy (IPv6-mapped/NAT64/6to4/CGNAT), TrackerList, StreamPin (pinning IP),
# YtdlpCommand (proxy env), MpvLoad (fail-closed, opsi proxy) dan ShieldProxy hidup di loopback (auth, kebijakan,
# batas waktu readHead ~17 dtk). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
LIB=core/library/src/main/kotlin/com/molina/core/library
SYS=core/system/src/main/kotlin/com/molina/core/system
POL=core/policy/src/main/kotlin/com/molina/core/policy
DIAG=core/diag/src/main/kotlin/com/molina/core/diag
NET_D=core/net/src/main/kotlin/com/molina/core/net
API_D=core/api/src/main/kotlin/com/molina/core/api
"$KC" -nowarn -d "$OUT" $LIB/*.kt $SYS/*.kt $POL/*.kt $DIAG/*.kt core/json/src/main/kotlin/com/molina/core/json/*.kt core/stream/src/main/kotlin/com/molina/core/stream/*.kt core/download/src/main/kotlin/com/molina/core/download/*.kt $NET_D/*.kt $API_D/LogContract.kt scripts/core-tests/stubs/FlowStub.kt \
  feature/mpv/src/main/java/com/molina/cinema/MpvLoad.kt scripts/core-tests/ShieldTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ShieldTestsKt

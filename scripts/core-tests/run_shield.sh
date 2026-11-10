#!/usr/bin/env bash
# Uji JVM murni untuk Molina Shield: ShieldPolicy (IPv6-mapped/NAT64/6to4/CGNAT), TrackerList, StreamPin (pinning IP),
# YtdlpCommand (proxy env), MpvLoad (fail-closed, opsi proxy) dan ShieldProxy hidup di loopback (auth, kebijakan,
# batas waktu readHead ~17 dtk). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
C=core/common/src/main/kotlin/com/molina/core/common
"$KC" -nowarn -d "$OUT" $C/*.kt scripts/core-tests/stubs/FlowStub.kt \
  feature/mpv/src/main/java/com/molina/cinema/MpvLoad.kt scripts/core-tests/ShieldTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ShieldTestsKt

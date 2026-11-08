#!/usr/bin/env bash
# Uji JVM murni untuk BtopProbe (Monitor ala btop: meminfo, per-inti, detail proses, pohon) di :core:common.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
C=core/common/src/main/kotlin/com/molina/core/common
"$KC" -nowarn -d "$OUT" $C/TaskTable.kt $C/ProcessReaper.kt $C/BtopProbe.kt scripts/core-tests/BtopProbeTests.kt
java -cp "$OUT:$(dirname "$(readlink -f "$(command -v "$KC")")")/../lib/kotlin-stdlib.jar" BtopProbeTestsKt

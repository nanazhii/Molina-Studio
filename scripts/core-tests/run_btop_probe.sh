#!/usr/bin/env bash
# Uji JVM murni untuk BtopProbe (Monitor ala btop: meminfo, per-inti, detail proses, pohon) di :core:system.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
SYS=core/system/src/main/kotlin/com/molina/core/system
"$KC" -nowarn -d "$OUT" $SYS/TaskTable.kt $SYS/ProcessReaper.kt $SYS/BtopProbe.kt scripts/core-tests/BtopProbeTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" BtopProbeTestsKt

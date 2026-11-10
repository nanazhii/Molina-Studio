#!/usr/bin/env bash
# [NANO-RUNSNAP] Uji JVM murni impor snapshot folder skrip dari nano (NanoRunSnap + RunReport). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc, dan sh.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
F=feature/code/src/main/kotlin/com/molina/code
"$KC" -nowarn -d "$OUT" $F/guard/NanoRunSnap.kt $F/guard/RunReport.kt $F/history/LineDiff.kt $F/history/NanoInbox.kt $F/history/HistoryStore.kt scripts/code-tests/NanoRunSnapTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" NanoRunSnapTestsKt

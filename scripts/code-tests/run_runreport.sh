#!/usr/bin/env bash
# Uji JVM murni untuk laporan perubahan pasca-skrip (RunReport). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
F=feature/code/src/main/kotlin/com/molina/code
"$KC" -nowarn -d "$OUT" $F/guard/RunReport.kt $F/history/LineDiff.kt scripts/code-tests/RunReportTests.kt
java -cp "$OUT:$(dirname "$(readlink -f "$(command -v "$KC")")")/../lib/kotlin-stdlib.jar" RunReportTestsKt

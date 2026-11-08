#!/usr/bin/env bash
# Uji JVM murni untuk penanda proyek lokal (ProjectMarker). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
F=feature/code/src/main/kotlin/com/molina/code
"$KC" -nowarn -d "$OUT" $F/guard/ProjectMarker.kt $F/guard/RunReport.kt $F/history/LineDiff.kt scripts/code-tests/ProjectMarkerTests.kt
java -cp "$OUT:$(dirname "$(readlink -f "$(command -v "$KC")")")/../lib/kotlin-stdlib.jar" ProjectMarkerTestsKt

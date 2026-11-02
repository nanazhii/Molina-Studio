#!/usr/bin/env bash
# Uji JVM murni untuk Riwayat versi (HistoryStore, HistoryFormat, LineDiff). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
F=feature/code/src/main/kotlin/com/molina/code/history
"$KC" -nowarn -d "$OUT" $F/HistoryStore.kt $F/LineDiff.kt scripts/code-tests/HistoryTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" HistoryTestsKt

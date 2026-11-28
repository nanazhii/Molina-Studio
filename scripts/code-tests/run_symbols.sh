#!/usr/bin/env bash
# Uji JVM murni untuk Pergi ke simbol (Outline, SymbolIndex, SymbolSearch, fuzzyScore). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
F=feature/code/src/main/kotlin/com/molina/code/ui
"$KC" -nowarn -d "$OUT" $F/SymbolCore.kt scripts/code-tests/SymbolTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" com.molina.code.ui.SymbolTestsKt

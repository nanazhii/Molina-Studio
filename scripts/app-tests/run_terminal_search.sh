#!/usr/bin/env bash
# Uji JVM murni untuk TerminalSearch (pencarian di transkrip Terminal) di :app.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -d "$OUT" app/src/main/java/com/molina/studio/terminal/TerminalSearch.kt scripts/app-tests/TerminalSearchTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" TerminalSearchTestsKt

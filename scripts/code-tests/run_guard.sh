#!/usr/bin/env bash
# Uji JVM murni untuk penjaga tab basi (DiskGuard) dan pemeriksaan skrip (ScriptPrecheck). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
F=feature/code/src/main/kotlin/com/molina/code/guard
"$KC" -nowarn -d "$OUT" $F/DiskGuard.kt $F/ScriptPrecheck.kt $F/NetworkUse.kt scripts/code-tests/GuardTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" GuardTestsKt

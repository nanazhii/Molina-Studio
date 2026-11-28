#!/usr/bin/env bash
# [EXT-DIFF2] Uji JVM murni kebijakan titik pantau (ShadowPolicy). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
F=feature/code/src/main/kotlin/com/molina/code/history
"$KC" -nowarn -d "$OUT" $F/ShadowPolicy.kt scripts/code-tests/ShadowPolicyTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ShadowPolicyTestsKt

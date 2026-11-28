#!/usr/bin/env bash
# Uji JVM murni untuk ProotHostEnv (:core:exec). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -d "$OUT" core/exec/src/main/kotlin/com/molina/core/exec/ProotHostEnv.kt scripts/core-tests/ProotHostEnvTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ProotHostEnvTestsKt

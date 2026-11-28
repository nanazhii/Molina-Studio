#!/usr/bin/env bash
# Uji KONTRAK parser JSON bersama (:core:json). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
J=core/json/src/main/kotlin/com/molina/core/json
"$KC" -nowarn -d "$OUT" $J/MiniJson.kt $J/JsonCursor.kt scripts/code-tests/MiniJsonContractTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" MiniJsonContractTestsKt

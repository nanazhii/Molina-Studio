#!/usr/bin/env bash
# Uji JVM murni untuk ExternalIntentGate (konfirmasi intent luar) di :core:policy.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
POL=core/policy/src/main/kotlin/com/molina/core/policy
"$KC" -nowarn -d "$OUT" $POL/IntentToken.kt $POL/ExternalIntentGate.kt scripts/core-tests/ExternalIntentGateTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ExternalIntentGateTestsKt

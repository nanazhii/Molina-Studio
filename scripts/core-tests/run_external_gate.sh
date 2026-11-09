#!/usr/bin/env bash
# Uji JVM murni untuk ExternalIntentGate (konfirmasi intent luar) di :core:common.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
C=core/common/src/main/kotlin/com/molina/core/common
"$KC" -nowarn -d "$OUT" $C/IntentToken.kt $C/ExternalIntentGate.kt scripts/core-tests/ExternalIntentGateTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ExternalIntentGateTestsKt

#!/usr/bin/env bash
# Uji JVM murni untuk kebijakan sumber intent MPV (PlaybackSourcePolicy, IntentExtras). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
POL=core/policy/src/main/kotlin/com/molina/core/policy
"$KC" -nowarn -d "$OUT" $POL/PlaybackSourcePolicy.kt $POL/SourceScheme.kt $POL/IntentExtras.kt scripts/core-tests/PlaybackSourcePolicyTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" PlaybackSourcePolicyTestsKt

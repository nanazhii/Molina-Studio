#!/usr/bin/env bash
# Uji JVM murni untuk PlaybackSessions (ID sesi pemutaran mpv di :core:diag).
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -d "$OUT" core/diag/src/main/kotlin/com/molina/core/diag/PlaybackSession.kt scripts/core-tests/PlaybackSessionTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" PlaybackSessionTestsKt

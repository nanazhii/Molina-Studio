#!/usr/bin/env bash
# Uji JVM murni untuk analisis spektrum yang sadar-seek: SpectrumTrack (rentang), SpectrumFrameBuilder.restart, SpectrumPlanner.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
M=feature/mpv/src/main/java/com/molina/cinema
"$KC" -nowarn -d "$OUT" $M/SpectrumMath.kt $M/SpectrumPlanner.kt scripts/mpv-tests/SpectrumSeekTests.kt
java -cp "$OUT:$(dirname "$(readlink -f "$(command -v "$KC")")")/../lib/kotlin-stdlib.jar" SpectrumSeekTestsKt

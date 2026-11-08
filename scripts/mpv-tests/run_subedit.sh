#!/usr/bin/env bash
# Uji JVM murni untuk SubEdit (editor subtitle) dan SpectrumMath (visualizer audio) di :feature:mpv.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
M=feature/mpv/src/main/java/com/molina/cinema
"$KC" -nowarn -d "$OUT" $M/SubtitleCodec.kt $M/AssCodec.kt $M/SubStyle.kt $M/SubEdit.kt $M/SpectrumMath.kt scripts/mpv-tests/SubEditTests.kt
java -cp "$OUT:$(dirname "$(readlink -f "$(command -v "$KC")")")/../lib/kotlin-stdlib.jar" SubEditTestsKt

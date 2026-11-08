#!/usr/bin/env bash
# Uji JVM murni untuk SubtitleCodec (SRT), SubStyle, ASS, rencana konversi, dan SubTextFiles di :feature:mpv.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
M=feature/mpv/src/main/java/com/molina/cinema
"$KC" -nowarn -d "$OUT" $M/SubtitleCodec.kt $M/SubStyle.kt $M/SubTextFiles.kt $M/AssCodec.kt $M/SubConvertPlan.kt $M/SubtitleExtractor.kt $M/SubtitleExtractCommand.kt scripts/mpv-tests/SubtitleTests.kt
java -cp "$OUT:$(dirname "$(readlink -f "$(command -v "$KC")")")/../lib/kotlin-stdlib.jar" SubtitleTestsKt

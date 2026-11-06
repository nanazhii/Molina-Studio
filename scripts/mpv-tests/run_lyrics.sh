#!/usr/bin/env bash
# Uji JVM murni untuk LyricsTimeline (LRC) dan SubtitleCodec (SRT) di :feature:mpv.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
M=feature/mpv/src/main/java/com/molina/cinema
"$KC" -nowarn -d "$OUT" $M/LyricsTimeline.kt $M/SubtitleCodec.kt $M/SubStyle.kt $M/LyricsFiles.kt $M/LyricsEmbedded.kt $M/LyricsOffsets.kt $M/AssCodec.kt $M/SubConvertPlan.kt $M/SubtitleExtractor.kt $M/SubtitleExtractCommand.kt scripts/mpv-tests/LyricsTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" LyricsTestsKt

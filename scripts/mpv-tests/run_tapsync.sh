#!/usr/bin/env bash
# Uji JVM murni untuk LyricsTapSync (TapSession, LrcWriter, LyricsSaver) di :feature:mpv.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
M=feature/mpv/src/main/java/com/molina/cinema
"$KC" -nowarn -d "$OUT" $M/LyricsTimeline.kt $M/LyricsFiles.kt $M/LyricsTapSync.kt scripts/mpv-tests/TapSyncTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" TapSyncTestsKt

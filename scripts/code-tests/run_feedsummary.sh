#!/usr/bin/env bash
# [EXT-FEED7] Uji JVM murni ringkasan butir notifikasi perubahan (FeedSummary). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
# [EXT-FEED8] ChangeFeed.kt ikut dikompilasi (FeedSummary.report memakai ChangeFeed.Item)
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
F=feature/code/src/main/kotlin/com/molina/code
"$KC" -nowarn -d "$OUT" $F/guard/FeedSummary.kt $F/guard/ChangeFeed.kt $F/guard/ProjectLog.kt $F/guard/ProjectMarker.kt $F/guard/RunReport.kt $F/history/LineDiff.kt scripts/code-tests/FeedSummaryTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" FeedSummaryTestsKt

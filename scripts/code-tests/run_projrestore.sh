#!/usr/bin/env bash
# Uji JVM murni untuk pemulihan seluruh proyek (ProjectRestore, ProjectStore, ProjectCommit). Butuh kotlinc (>= 2.1)
# di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
F=feature/code/src/main/kotlin/com/molina/code
"$KC" -nowarn -d "$OUT" $F/guard/ProjectRestore.kt $F/guard/ProjectStore.kt $F/guard/ProjectCommit.kt $F/guard/ProjectLog.kt $F/guard/ProjectMarker.kt $F/guard/RunReport.kt $F/history/LineDiff.kt scripts/code-tests/ProjectRestoreTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ProjectRestoreTestsKt

#!/usr/bin/env bash
# [WORKSPACE-W1] Uji JVM murni untuk logika Folder Kerja (WorkspaceFiles, WorkspaceRootPolicy).
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -d "$OUT" \
  feature/workspace/src/main/kotlin/com/molina/workspace/WorkspaceFiles.kt \
  feature/workspace/src/main/kotlin/com/molina/workspace/WorkspaceRootPolicy.kt \
  scripts/app-tests/WorkspaceTests.kt
java -cp "$OUT:$(dirname "$(readlink -f "$(command -v "$KC")")")/../lib/kotlin-stdlib.jar" WorkspaceTestsKt

#!/usr/bin/env bash
# [WORKSPACE-W1] Uji JVM murni untuk logika Folder Kerja (WorkspaceFiles, WorkspaceRootPolicy).
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/lib/kotlin_env.sh   # [KOTLIN-ENV] menyediakan KC dan KSTD
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -d "$OUT" \
  feature/workspace/src/main/kotlin/com/molina/workspace/WorkspaceFiles.kt \
  feature/workspace/src/main/kotlin/com/molina/workspace/WorkspaceRootPolicy.kt \
  scripts/app-tests/WorkspaceTests.kt
java -cp "$OUT:$KSTD" WorkspaceTestsKt

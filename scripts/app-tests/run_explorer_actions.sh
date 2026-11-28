#!/usr/bin/env bash
# Uji JVM ExplorerActions (mesin operasi berkas Penjelajah) memakai stub ringan di scripts/app-tests/actions-stubs:
# Context/izin terkendali, state Compose sederhana, coroutines sinkron, FileOps minimal. TrashBin, ProtectedPaths, FileOpOutcome asli.
# Yang diuji adalah logika ExplorerActions; perilaku API Android/Compose asli tetap perlu CI dan uji perangkat.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
LIB=core/library/src/main/kotlin/com/molina/core/library
"$KC" -nowarn -d "$OUT" scripts/app-tests/actions-stubs/*.kt \
  core/fileops/src/main/kotlin/com/molina/core/fileops/FileOpOutcome.kt $LIB/TrashBin.kt $LIB/StorageGuard.kt \
  feature/explorer/src/main/kotlin/com/molina/explorer/ExplorerActions.kt \
  scripts/app-tests/ExplorerActionsTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ExplorerActionsTestsKt

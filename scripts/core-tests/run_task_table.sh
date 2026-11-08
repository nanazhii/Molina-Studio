#!/usr/bin/env bash
# Uji JVM murni untuk TaskTable (Pengelola Tugas: kelompok proses, CPU%, urutan) di :core:common.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
C=core/common/src/main/kotlin/com/molina/core/common
"$KC" -nowarn -d "$OUT" $C/ProcessReaper.kt $C/TaskTable.kt scripts/core-tests/TaskTableTests.kt
java -cp "$OUT:$(dirname "$(readlink -f "$(command -v "$KC")")")/../lib/kotlin-stdlib.jar" TaskTableTestsKt

#!/usr/bin/env bash
# Uji JVM murni untuk FileNames (:core:fileops). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=...
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -d "$OUT" core/fileops/src/main/kotlin/com/molina/core/fileops/FileNames.kt scripts/core-tests/FileNamesTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" FileNamesTestsKt

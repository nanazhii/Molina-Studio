#!/usr/bin/env bash
# [DRAWER-CRUMBS] Uji JVM murni breadcrumb path (PathCrumbs, :core:files). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -d "$OUT" core/files/src/main/kotlin/com/molina/core/files/PathCrumbs.kt scripts/core-tests/PathCrumbsTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" PathCrumbsTestsKt

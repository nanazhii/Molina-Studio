#!/usr/bin/env bash
# [CODE-PATHBAR] Uji JVM murni breadcrumb path header editor (PathCrumbs). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
F=feature/code/src/main/kotlin/com/molina/code
"$KC" -nowarn -d "$OUT" $F/ui/PathCrumbs.kt scripts/code-tests/PathCrumbsTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" PathCrumbsTestsKt

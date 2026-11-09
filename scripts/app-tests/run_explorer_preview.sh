#!/usr/bin/env bash
# Uji JVM murni untuk ExplorerPreview (berkas mana yang mendapat menu Pratinjau di Penjelajah).
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -d "$OUT" \
  app/src/main/java/com/molina/studio/preview/ExplorerPreview.kt \
  feature/preview/src/main/kotlin/com/molina/code/preview/PreviewKind.kt \
  $(find core/exec -name '*.kt') \
  scripts/app-tests/ExplorerPreviewTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ExplorerPreviewTestsKt

#!/usr/bin/env bash
# Uji JVM murni untuk pemformat kode (JsonFormatter, XmlFormatter, Formatters). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
F=feature/code/src/main/kotlin/com/molina/code
"$KC" -nowarn -d "$OUT" \
  $F/format/JsonFormatter.kt $F/format/XmlFormatter.kt $F/format/Formatters.kt $F/format/FragmentIndent.kt $F/preview/RunPlan.kt \
  scripts/code-tests/FormatTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" FormatTestsKt

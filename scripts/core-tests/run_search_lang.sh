#!/usr/bin/env bash
# Uji JVM murni untuk SearchLang (kunci hasil pencarian ke bahasa Indonesia, modul core).
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
# (tidak memakai berkas core lama)
"$KC" -nowarn -d "$OUT" core/stream/src/main/kotlin/com/molina/core/stream/StreamQuality.kt core/stream/src/main/kotlin/com/molina/core/stream/StreamResolver.kt core/stream/src/main/kotlin/com/molina/core/stream/SearchLang.kt scripts/core-tests/stubs/FlowStub.kt scripts/core-tests/SearchLangTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" SearchLangTestsKt

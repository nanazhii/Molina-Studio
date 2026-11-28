#!/usr/bin/env bash
# Uji JVM murni untuk ProotConfig/ProotBinds/ProotCaps/FakeProc di :app.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -d "$OUT" app/src/main/java/com/molina/studio/terminal/ProotConfig.kt scripts/app-tests/ProotConfigTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ProotConfigTestsKt

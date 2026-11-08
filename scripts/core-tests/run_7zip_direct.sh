#!/usr/bin/env bash
# Uji JVM murni untuk mode langsung 7z (SevenZipCommand.mapToHost/useDirect) di :core:common.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
# Opsional: SEVENZIP_BIN=/jalur/7z menambah uji integrasi terhadap 7z asli.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
C=core/common/src/main/kotlin/com/molina/core/common
"$KC" -nowarn -d "$OUT" $C/SevenZipCommand.kt scripts/core-tests/SevenZipDirectTests.kt 2>&1 | grep -v '^$' || true
LC_ALL="${LC_ALL:-C.UTF-8}" java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -cp "$OUT:$(dirname "$(readlink -f "$(command -v "$KC")")")/../lib/kotlin-stdlib.jar" SevenZipDirectTestsKt

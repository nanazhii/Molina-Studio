#!/usr/bin/env bash
# Uji JVM murni untuk kontrak berkas (:core:files FileTypes/NaturalOrder/DirOrder/FolderKinds). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -d "$OUT" core/files/src/main/kotlin/com/molina/core/files/*.kt scripts/core-tests/FileTypesTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" FileTypesTestsKt

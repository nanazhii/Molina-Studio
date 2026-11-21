#!/usr/bin/env bash
# [FOLDER-BADGE] Uji JVM murni pemetaan lencana folder (FolderBadges, :core:files). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
F=core/files/src/main/kotlin/com/molina/core/files
"$KC" -nowarn -d "$OUT" $F/FolderBadge.kt $F/FolderKinds.kt scripts/core-tests/FolderBadgeTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" FolderBadgeTestsKt

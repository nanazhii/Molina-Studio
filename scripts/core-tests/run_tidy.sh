#!/usr/bin/env bash
# Uji JVM murni untuk FolderName + ChannelTidy ("Rapikan ke folder kanal", :core:common).
# FolderName harus sama dengan sanitize_folder_name di molina_dl.py (tabel pasangan dihasilkan dari Python).
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
C=core/common/src/main/kotlin/com/molina/core/common
"$KC" -nowarn -d "$OUT" \
  $C/MiniJson.kt $C/JsonCursor.kt $C/MediaSidecar.kt $C/ResumeMark.kt $C/ShelfCard.kt $C/ShelfScan.kt $C/FolderName.kt $C/ChannelTidy.kt \
  scripts/core-tests/ChannelTidyTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ChannelTidyTestsKt

#!/usr/bin/env bash
# Uji JVM murni untuk FolderName + ChannelTidy ("Rapikan ke folder kanal", :core:library).
# FolderName harus sama dengan sanitize_folder_name di molina_dl.py (tabel pasangan dihasilkan dari Python).
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
LIB=core/library/src/main/kotlin/com/molina/core/library
"$KC" -nowarn -d "$OUT" \
  core/json/src/main/kotlin/com/molina/core/json/MiniJson.kt core/json/src/main/kotlin/com/molina/core/json/JsonCursor.kt $LIB/MediaSidecar.kt core/json/src/main/kotlin/com/molina/core/json/InfoJsonKeys.kt $LIB/ResumeMark.kt $LIB/ShelfCard.kt $LIB/ShelfScan.kt $LIB/FolderName.kt $LIB/ChannelTidy.kt \
  scripts/core-tests/ChannelTidyTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" ChannelTidyTestsKt

#!/usr/bin/env bash
# Uji JVM RootfsBackup (export/inspect/restore, SafeTree, penolakan arsip rusak/bukan rootfs/ruang kurang, batal, traversal).
# Butuh: kotlinc (>= 2.1) dan JAR di ARCHIVE_DEPS (dipisah ':'): commons-compress 1.27.1, commons-io, commons-lang3, commons-codec.
# Contoh: ARCHIVE_DEPS=$(ls ~/jars/*.jar | tr '\n' ':') KOTLINC=/opt/kotlinc/bin/kotlinc bash scripts/app-tests/run_rootfs_backup.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
: "${ARCHIVE_DEPS:?isi ARCHIVE_DEPS dengan daftar JAR dependensi (dipisah titik dua)}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -cp "$ARCHIVE_DEPS" -d "$OUT" app/src/main/java/com/molina/studio/terminal/RootfsBackup.kt scripts/app-tests/RootfsBackupTests.kt
java -cp "$OUT:$ARCHIVE_DEPS:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" RootfsBackupTestsKt

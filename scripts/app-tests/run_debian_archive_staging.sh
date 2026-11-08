#!/usr/bin/env bash
# Uji JVM murni untuk DebianFormats + DebianArchiveStaging (staging, audit, commit, rollback) + ArchiveRoute di :app.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc. Opsional: SEVENZIP_BIN=/usr/bin/7z menambah e2e [ARCHIVE6] dengan 7z asli.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
C=app/src/main/java/com/molina/studio/cinema
"$KC" -nowarn -d "$OUT" core/common/src/main/kotlin/com/molina/core/common/SevenZipCommand.kt $C/ArchiveNames.kt $C/DebianArchiveStaging.kt $C/ArchiveRoute.kt scripts/app-tests/DebianArchiveStagingTests.kt 2>&1 | grep -v '^$' || true
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" com.molina.studio.cinema.DebianArchiveStagingTestsKt

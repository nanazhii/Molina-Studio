#!/usr/bin/env bash
# [NANO-INBOX] Uji JVM murni untuk impor inbox snapshot nano (NanoInbox + HistoryStore). Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
F=feature/code/src/main/kotlin/com/molina/code/history
"$KC" -nowarn -d "$OUT" $F/HistoryStore.kt $F/NanoInbox.kt scripts/code-tests/NanoInboxTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" NanoInboxTestsKt

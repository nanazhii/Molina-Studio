#!/usr/bin/env bash
# Uji JVM murni untuk DownloadUrlGuard (tolak URL unduhan ke alamat lokal/LAN) di :core:net.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
C=core/common/src/main/kotlin/com/molina/core/common
NET_D=core/net/src/main/kotlin/com/molina/core/net
"$KC" -nowarn -d "$OUT" $NET_D/UrlPolicy.kt $NET_D/DownloadUrlGuard.kt scripts/core-tests/DownloadUrlGuardTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" DownloadUrlGuardTestsKt

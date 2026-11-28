#!/usr/bin/env bash
# [IPTV-TRUST] Uji JVM murni untuk IptvTrust di :core:net. Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KC" -nowarn -d "$OUT" core/net/src/main/kotlin/com/molina/core/net/IptvTrust.kt core/net/src/main/kotlin/com/molina/core/net/UrlPolicy.kt scripts/core-tests/IptvTrustTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" IptvTrustTestsKt

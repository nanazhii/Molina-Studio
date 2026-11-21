#!/usr/bin/env bash
# Uji JVM murni untuk IptvLocal (muat playlist .m3u/.m3u8 dari folder IPTV Molina) di :core:net.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
# (tidak memakai berkas core lama)
NET_D=core/net/src/main/kotlin/com/molina/core/net
"$KC" -nowarn -d "$OUT" $NET_D/IptvLocal.kt $NET_D/IptvFetch.kt $NET_D/M3uParser.kt $NET_D/UrlPolicy.kt $NET_D/StreamHeaders.kt $NET_D/ShieldPolicy.kt scripts/core-tests/stubs/FlowStub.kt scripts/core-tests/IptvLocalTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" IptvLocalTestsKt

#!/usr/bin/env bash
# Uji JVM murni untuk IptvLocal (muat playlist .m3u/.m3u8 dari folder IPTV Molina) di :core:common.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
C=core/common/src/main/kotlin/com/molina/core/common
"$KC" -nowarn -d "$OUT" $C/IptvLocal.kt $C/IptvFetch.kt $C/M3uParser.kt $C/UrlPolicy.kt scripts/core-tests/IptvLocalTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" IptvLocalTestsKt

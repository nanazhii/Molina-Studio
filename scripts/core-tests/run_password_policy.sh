#!/usr/bin/env bash
# Uji JVM murni untuk PasswordPolicy (kebijakan kata sandi arsip, modul core).
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
# (tidak memakai berkas core lama)
"$KC" -nowarn -d "$OUT" core/fileops/src/main/kotlin/com/molina/core/fileops/PasswordPolicy.kt scripts/core-tests/PasswordPolicyTests.kt 2>&1 | grep -v '^$' || true
java -Dfile.encoding=UTF-8 -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" PasswordPolicyTestsKt

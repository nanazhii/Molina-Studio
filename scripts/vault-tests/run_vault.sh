#!/usr/bin/env bash
# [MOLINA-H1] Uji JVM murni modul Vault (AttemptLogic, SecurityPatch, PassphraseGenerator, BackupCrypto).
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc. Kode yang memakai android.* tidak diuji di sini.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
V=vault/src/main/kotlin/com/molina/vault/crypto
"$KC" -nowarn -d "$OUT" \
  "$V/AttemptLogic.kt" "$V/SecurityPatch.kt" "$V/PassphraseGenerator.kt" "$V/BackupCrypto.kt" \
  scripts/vault-tests/CryptoStubs.kt scripts/vault-tests/VaultTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" VaultTestsKt

#!/usr/bin/env bash
# [MOLINA-H1] Uji JVM murni modul Vault (AttemptLogic, SecurityPatch, PassphraseGenerator, BackupCrypto).
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc. Kode yang memakai android.* tidak diuji di sini.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
# MOLINA-H11-GLUE: uji integrasi VaultStore butuh org.json asli (Android memilikinya, JVM tidak).
# ORG_JSON_CLASSES = direktori kelas org.json; bila kosong, JSON-java (rilis 20240303, MIT) di-clone dari GitHub dan dikompilasi dengan javac.
JSONC="${ORG_JSON_CLASSES:-}"
if [ -z "$JSONC" ]; then
  JSONC="$OUT/orgjson"; mkdir -p "$JSONC"
  git clone -q --depth 1 --branch 20240303 https://github.com/stleary/JSON-java.git "$OUT/json-src"
  [ "$(git -C "$OUT/json-src" rev-parse HEAD)" = "f9b5587c87aaf02e4a5ed1991d48d6c05993624a" ] || { echo "JSON-java: commit tidak cocok dengan pin" >&2; exit 1; }
  JAVAC="javac"; command -v javac >/dev/null 2>&1 || JAVAC="java -m jdk.compiler/com.sun.tools.javac.Main"
  $JAVAC -nowarn -d "$JSONC" $(find "$OUT/json-src/src/main/java" -name '*.java')
fi
V=vault/src/main/kotlin/com/molina/vault/crypto
S=vault/src/main/kotlin/com/molina/vault/store
"$KC" -nowarn -cp "$JSONC" -d "$OUT" \
  "$V/AttemptLogic.kt" "$V/SecurityPatch.kt" "$V/PassphraseGenerator.kt" "$V/BackupCrypto.kt" "$V/StreamCrypto.kt" \
  "$V/SessionLock.kt" "$V/PasswordPolicy.kt" \
  "$S/Models.kt" "$S/Manifest.kt" "$S/BlobRecord.kt" "$S/SecureWipe.kt" "$S/VaultPrefs.kt" "$S/VaultStore.kt" "$S/BackupStore.kt" \
  scripts/vault-tests/CryptoStubs.kt scripts/vault-tests/VaultTests.kt scripts/vault-tests/StreamStoreTests.kt \
  scripts/vault-tests/AndroidStubs.kt scripts/vault-tests/VaultCryptoFake.kt scripts/vault-tests/StoreIntegrationTests.kt scripts/vault-tests/BackupStoreTests.kt scripts/vault-tests/GudangTests.kt scripts/vault-tests/ManifestTests.kt
java -Xmx64m -cp "$OUT:$JSONC:$(dirname "$(readlink -f "$(command -v "$KC")")")/../lib/kotlin-stdlib.jar" VaultTestsKt

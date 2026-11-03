#!/usr/bin/env bash
# Gagalkan build jika APK Vault memuat izin jaringan.
set -euo pipefail
APK="${1:?usage: verify-offline.sh <apk>}"
AAPT2="$(ls "${ANDROID_HOME:-/usr/local/lib/android/sdk}"/build-tools/*/aapt2 | sort -V | tail -1)"
PERMS="$("$AAPT2" dump permissions "$APK")"
if echo "$PERMS" | grep -Eq "INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE"; then
  echo "GAGAL: APK memuat izin jaringan"; echo "$PERMS"; exit 1
fi
echo "OK: Vault tidak memiliki izin jaringan"

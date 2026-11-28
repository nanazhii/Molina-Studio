#!/usr/bin/env bash
set -euo pipefail
APK="${1:?usage: verify-bunker.sh <apk>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
bash "$HERE/verify-offline.sh" "$APK"
AAPT2="$(ls "${ANDROID_HOME:-/usr/local/lib/android/sdk}"/build-tools/*/aapt2 | sort -V | tail -1)"
TREE="$("$AAPT2" dump xmltree --file AndroidManifest.xml "$APK")"
if echo "$TREE" | grep -Eq "E: (provider|service|receiver)"; then
  echo "GAGAL: ada provider/service/receiver (permukaan serangan)"; exit 1
fi
if echo "$TREE" | grep -Eq "debuggable.*=true"; then
  echo "GAGAL: APK debuggable"; exit 1
fi
echo "OK: bunker tersegel"

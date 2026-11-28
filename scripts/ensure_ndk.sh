#!/usr/bin/env bash
# [NDK-PIN] Pastikan NDK pin (dibaca dari app/build.gradle.kts) ada di SDK runner; bila tidak, pasang lewat sdkmanager.
# Aman dijalankan berulang: tidak melakukan apa pun bila NDK sudah ada.
set -eu
cd "$(dirname "$0")/.."
NDK="$(grep -ho 'ndkVersion = "[0-9.]*"' app/build.gradle.kts | head -n1 | grep -o '[0-9][0-9.]*' || true)"
if [ -z "$NDK" ]; then echo "::error::ndkVersion tidak ditemukan di app/build.gradle.kts" >&2; exit 1; fi
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then echo "::error::ANDROID_HOME tidak diatur" >&2; exit 1; fi
if [ -d "$SDK/ndk/$NDK" ]; then echo "NDK $NDK sudah ada."; exit 0; fi
echo "NDK $NDK belum ada, memasang lewat sdkmanager."
SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKM" --licenses >/dev/null 2>&1 || true
"$SDKM" --install "ndk;$NDK"
test -d "$SDK/ndk/$NDK"
echo "NDK $NDK siap."

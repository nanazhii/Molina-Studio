#!/usr/bin/env bash
# Unduh pustaka native mpv dari GitHub Release repo ini, verifikasi SHA-256, lalu ekstrak ke feature/mpv.
# Pin ada di native/prebuilt.env. Kosong = APK dibangun tanpa .so (peringatan, bukan galat).
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
. native/prebuilt.env
DEST=feature/mpv/src/main
if [ -z "${NATIVE_TAG:-}" ] && [ -z "${NATIVE_SHA256:-}" ]; then
  echo "::warning::native/prebuilt.env belum dipin: APK dibangun TANPA libmpv/libplayer"
  exit 0
fi
if [ -z "${NATIVE_TAG:-}" ] || ! [[ "${NATIVE_SHA256:-}" =~ ^[0-9a-f]{64}$ ]]; then
  echo "::error::NATIVE_TAG dan NATIVE_SHA256 (64 hex huruf kecil) harus diisi keduanya" >&2; exit 1
fi
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
gh release download "$NATIVE_TAG" --repo "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY kosong}" \
  --pattern molina-native-arm64.tgz --dir "$T"
echo "$NATIVE_SHA256  $T/molina-native-arm64.tgz" | sha256sum -c -
if tar -tzf "$T/molina-native-arm64.tgz" | grep -Evq '^jniLibs/(arm64-v8a/([A-Za-z0-9_.+-]+\.so)?)?$'; then
  echo "::error::isi tgz di luar jniLibs/arm64-v8a/*.so" >&2; exit 1
fi
rm -rf "$DEST/jniLibs"
tar -xzf "$T/molina-native-arm64.tgz" -C "$DEST"
for f in libmpv.so libplayer.so libc++_shared.so; do
  [ -f "$DEST/jniLibs/arm64-v8a/$f" ] || { echo "::error::hilang: $f" >&2; exit 1; }
done
echo "Pustaka native terpasang dari $NATIVE_TAG:"; ls -l "$DEST/jniLibs/arm64-v8a"

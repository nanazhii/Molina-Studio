#!/bin/bash
# Molina Cinema 5a: bangun libmpv + libplayer (arm64) dari mpv-android pada commit dipin.
# Hasil: _native_out/molina-native-arm64.tgz (jniLibs/arm64-v8a/*.so) + SHA256SUMS.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=/dev/null
. "$ROOT/native/pins.env"
W="${NATIVE_WORK:-$ROOT/_native}"
OUT="${NATIVE_OUT:-$ROOT/_native_out}"
export NATIVE_PINS_FILE="$ROOT/native/pins.sha256"
export NATIVE_HASH_LOG="$OUT/download-sha256.txt"
# dipanggil lewat bash agar tidak perlu bit eksekusi (git di /sdcard tidak menyimpannya)
export WGET="bash $ROOT/scripts/native_wget.sh"
export IN_CI=1
msg() { printf '==> %s\n' "$*"; }

# pin_clone <url> <sha> <dest> [rekursif]: ambil tepat satu commit dan verifikasi.
pin_clone() {
  local url="$1" sha="$2" dest="$3" rec="${4:-}"
  if [ -d "$dest/.git" ] && [ "$(git -C "$dest" rev-parse HEAD)" = "$sha" ]; then return 0; fi
  rm -rf "$dest"; mkdir -p "$dest"
  git -C "$dest" init -q
  git -C "$dest" remote add origin "$url"
  git -C "$dest" fetch -q --depth 1 origin "$sha"
  git -C "$dest" checkout -q FETCH_HEAD
  [ "$(git -C "$dest" rev-parse HEAD)" = "$sha" ] || { echo "SHA tidak cocok: $dest" >&2; exit 1; }
  if [ -n "$rec" ]; then git -C "$dest" submodule update -q --init --recursive --depth 1; fi
}

mkdir -p "$W" "$OUT"; : > "$OUT/download-sha256.txt"
UP="$W/mpv-android"
msg "Sumber upstream (dipin)"
pin_clone https://github.com/mpv-android/mpv-android.git "$UPSTREAM_SHA" "$UP"

msg "Rename JNI ke com.molina.cinema (idempoten)"
sed -i 's#is/xyz/mpv/MPVLib#com/molina/cinema/MPVLib#' "$UP/app/src/main/jni/jni_utils.cpp"
sed -i 's#Java_is_xyz_mpv_MPVLib_#Java_com_molina_cinema_MPVLib_#' "$UP/app/src/main/jni/jni_utils.h"
grep -q 'com/molina/cinema/MPVLib' "$UP/app/src/main/jni/jni_utils.cpp"
grep -q 'Java_com_molina_cinema_MPVLib_' "$UP/app/src/main/jni/jni_utils.h"
if grep -rn 'is/xyz\|is_xyz' "$UP/app/src/main/jni"; then echo "sisa nama lama di JNI" >&2; exit 1; fi

msg "Profil FFmpeg Molina (daftar putih demuxer/protokol + pengerasan)"
FFSH="$UP/buildscripts/scripts/ffmpeg.sh"
cp "$ROOT/native/molina_ffmpeg_profile.sh" "$UP/buildscripts/scripts/molina_ffmpeg_profile.sh"
if ! grep -q molina_ffmpeg_args "$FFSH"; then
  python3 - "$FFSH" << 'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
a = "args=(\n"
b = "\n)\n../configure \"${args[@]}\""
assert s.count(a) == 1 and s.count(b) == 1, "ffmpeg.sh upstream berubah, sesuaikan patch"
s = s.replace(a, ". ../../../scripts/molina_ffmpeg_profile.sh\n\n" + a, 1)
s = s.replace(b, "\n)\nargs+=(\"${molina_ffmpeg_args[@]}\")\n../configure \"${args[@]}\"", 1)
open(p, "w").write(s)
PYEOF
fi
grep -q molina_ffmpeg_args "$FFSH" && grep -q molina_ffmpeg_profile.sh "$FFSH"
# [PROFIL-PREFLIGHT] Jalur source profil harus ada dari cwd ffmpeg.sh (deps/ffmpeg/_build_arm64).
PREL="$(grep -o '\. [^ ]*molina_ffmpeg_profile.sh' "$FFSH" | head -1 | cut -c3-)"
[ -n "$PREL" ] && [ -f "$(realpath -m "$UP/buildscripts/deps/ffmpeg/_build_arm64/$PREL")" ] || { echo "jalur source profil FFmpeg tidak valid: ${PREL:-kosong}" >&2; exit 1; }

cd "$UP/buildscripts"
# shellcheck source=/dev/null
. ./include/depinfo.sh
mkdir -p deps prefix sdk/bin

msg "Dependensi git (dipin)"
pin_clone https://github.com/mpv-player/mpv.git "$MPV_SHA" deps/mpv
pin_clone https://github.com/videolan/dav1d.git "$DAV1D_SHA" deps/dav1d
pin_clone https://github.com/FFmpeg/FFmpeg.git "$FFMPEG_SHA" deps/ffmpeg
pin_clone https://github.com/libass/libass.git "$LIBASS_SHA" deps/libass
pin_clone https://github.com/haasn/libplacebo.git "$LIBPLACEBO_SHA" deps/libplacebo rec

msg "Dependensi tarball + freetype (versi dari depinfo.sh, hash dicatat/diverifikasi)"
./include/download-deps.sh

msg "NDK ${v_ndk}"
if [ ! -d "sdk/android-ndk-${v_ndk}" ]; then
  (cd sdk && $WGET "https://dl.google.com/android/repository/android-ndk-${v_ndk}-linux.zip" \
    && unzip -q "android-ndk-${v_ndk}-linux.zip" && rm "android-ndk-${v_ndk}-linux.zip")
fi
grep -qF "${v_ndk_n}" "sdk/android-ndk-${v_ndk}/source.properties"
$WGET "https://github.com/FFmpeg/gas-preprocessor/raw/master/gas-preprocessor.pl" -O sdk/bin/gas-preprocessor.pl
chmod +x sdk/bin/gas-preprocessor.pl

msg "Kompilasi dependensi (arm64)"
./buildall.sh --only-deps mpv --arch arm64
msg "Audit komponen FFmpeg yang terkompilasi"
bash "$ROOT/scripts/ffmpeg_components_audit.sh" deps/ffmpeg/_build_arm64/config_components.h "$OUT/ffmpeg-components.txt"
msg "Kompilasi libmpv (arm64)"
./buildall.sh -n mpv --arch arm64 || {
  [ -f deps/mpv/_build_arm64/config.h ] || cat deps/mpv/_build_arm64/meson-logs/meson-log.txt || true
  exit 1
}
[ -f prefix/arm64/lib/libmpv.so ]

msg "Kompilasi libplayer (ndk-build)"
export ANDROID_NDK_ROOT="$PWD/sdk/android-ndk-${v_ndk}"
cd "$UP"
PREFIX64="$(realpath buildscripts/prefix/arm64)" "$ANDROID_NDK_ROOT/ndk-build" -C app/src/main -j"$(nproc)"

msg "Kumpulkan, tutup dependensi, strip, cek"
BIN="$(echo "$ANDROID_NDK_ROOT"/toolchains/llvm/prebuilt/*/bin)"
P="$UP/buildscripts/prefix/arm64"
D="$OUT/jniLibs/arm64-v8a"; rm -rf "$OUT/jniLibs"; mkdir -p "$D"
cp app/src/main/libs/arm64-v8a/*.so "$D"/
needed() { "$BIN/llvm-readelf" -d "$1" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'; }
changed=1
while [ "$changed" -eq 1 ]; do
  changed=0
  for f in "$D"/*.so; do
    for n in $(needed "$f"); do
      if [ ! -e "$D/$n" ] && [ -f "$P/lib/$n" ]; then cp "$P/lib/$n" "$D/"; changed=1; fi
    done
  done
done
for f in libmpv.so libplayer.so libc++_shared.so; do [ -f "$D/$f" ] || { echo "hilang: $f" >&2; exit 1; }; done
"$BIN/llvm-strip" --strip-unneeded "$D"/*.so
msg "Audit pengerasan .so (informasi; belum fatal)"
bash "$ROOT/scripts/native_audit.sh" "$D" | tee "$OUT/hardening-audit.txt" || echo "PERINGATAN: native_audit melaporkan GAGAL (lihat hardening-audit.txt)"
SYS=" libc.so libm.so libdl.so liblog.so libandroid.so libz.so libEGL.so libGLESv2.so libGLESv3.so libOpenSLES.so libmediandk.so libnativewindow.so libvulkan.so libjnigraphics.so libaaudio.so libstdc++.so "
bad=0
for f in "$D"/*.so; do
  for n in $(needed "$f"); do
    if [ ! -e "$D/$n" ] && [[ "$SYS" != *" $n "* ]]; then echo "FALTA: $(basename "$f") butuh $n" >&2; bad=1; fi
  done
done
[ "$bad" -eq 0 ]

( cd "$OUT" && sha256sum jniLibs/arm64-v8a/*.so > SHA256SUMS \
  && tar -czf molina-native-arm64.tgz jniLibs && sha256sum molina-native-arm64.tgz >> SHA256SUMS )
{ echo "mpv-android=$UPSTREAM_SHA"; echo "mpv=$MPV_SHA"; echo "dav1d=$DAV1D_SHA"; echo "ffmpeg=$FFMPEG_SHA"
  echo "libass=$LIBASS_SHA"; echo "libplacebo=$LIBPLACEBO_SHA"; echo "ndk=$v_ndk_n"; } > "$OUT/BUILD-INFO.txt"
{ echo "--- komponen FFmpeg (profil Molina)"; cat "$OUT/ffmpeg-components.txt"
  echo "--- audit pengerasan"; cat "$OUT/hardening-audit.txt"; } >> "$OUT/BUILD-INFO.txt"
cat "$OUT/SHA256SUMS"; du -sh "$OUT/molina-native-arm64.tgz"

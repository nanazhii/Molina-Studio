#!/usr/bin/env bash
# Build ffmpeg + ffprobe STATIS (arm64) untuk Molina Terminal (Debian PRoot). Cakupan Penuh:
# ekstraksi subtitle/audio, potong, konversi video x264, encode mp3/opus/vorbis.
# Versi dipin lewat FFMPEG_SHA di native/pins.env (sama dengan FFmpeg libmpv). Daftar putih: tidak
# ada komponen di luar daftar yang dikompilasi. Jaringan dimatikan (hanya protokol file dan pipe).
# Dijalankan di runner arm64 native; CONFIGURE_ONLY=1 hanya menguji configure.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# shellcheck disable=SC1091
. "$REPO/native/pins.env"
: "${FFMPEG_SHA:?FFMPEG_SHA kosong di native/pins.env}"

WORK="${WORK_DIR:-$REPO/_ffcli_work}"
OUT="${OUT_DIR:-$REPO/_ffcli_out}"
SRC="${SRC_DIR:-$WORK/ffmpeg-src}"
mkdir -p "$WORK" "$OUT"

if [ ! -d "$SRC/.git" ]; then
  mkdir -p "$SRC"
  git -C "$SRC" init -q
  git -C "$SRC" remote add origin https://github.com/FFmpeg/FFmpeg.git
  git -C "$SRC" fetch -q --depth 1 origin "$FFMPEG_SHA"
  git -C "$SRC" checkout -q FETCH_HEAD
fi
[ "$(git -C "$SRC" rev-parse HEAD)" = "$FFMPEG_SHA" ] || { echo "GAGAL: SHA sumber tidak cocok dengan pin" >&2; exit 1; }

PROTOCOLS="file,pipe"
DEMUXERS="matroska,mov,avi,asf,flv,mpegts,mpegps,mpegvideo,ogg,wav,mp3,flac,aac,ac3,eac3,dts,aiff,caf,ape,wv,amr,h264,hevc,m4v,mjpeg,ivf,image2,srt,ass,webvtt,microdvd,subviewer,sami,lrc"
MUXERS="mp4,mov,ipod,matroska,webm,mp3,adts,ogg,opus,flac,wav,srt,webvtt,ass,image2,mjpeg,null"
DECODERS="wrapped_avframe,h264,hevc,vp8,vp9,mpeg4,mpeg2video,mpeg1video,mjpeg,png,gif,rawvideo,aac,aac_latm,mp3,mp3float,opus,vorbis,flac,alac,ac3,eac3,dca,wmav2,pcm_u8,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_f64le,ass,ssa,subrip,srt,webvtt,mov_text"
ENCODERS="libx264,aac,libmp3lame,libopus,libvorbis,flac,pcm_s16le,pcm_s24le,mjpeg,png,mov_text,ass,ssa,subrip,srt,webvtt"
PARSERS="h264,hevc,vp8,vp9,av1,mpeg4video,mpegvideo,mjpeg,png,aac,aac_latm,mpegaudio,opus,vorbis,flac,ac3,dca"
BSFS="h264_mp4toannexb,hevc_mp4toannexb,aac_adtstoasc,extract_extradata,vp9_superframe,vp9_superframe_split,av1_frame_merge,av1_frame_split,setts,text2movsub,mov2textsub,null"
FILTERS="aresample,aformat,anull,null,format,scale,fps,crop,pad,transpose,hflip,vflip,trim,atrim,setpts,asetpts,setsar,setdar,volume,loudnorm,amix,amerge,pan,channelmap,atempo,afade,concat,split,asplit,buffer,abuffer,buffersink,abuffersink,testsrc,sine"

cd "$SRC"
./configure \
  --enable-gpl --enable-version3 \
  --disable-everything --disable-autodetect \
  --disable-doc --disable-debug --disable-ffplay --disable-network \
  --enable-small --enable-static --disable-shared \
  --enable-ffmpeg --enable-ffprobe \
  --enable-zlib --enable-libx264 --enable-libmp3lame --enable-libopus --enable-libvorbis \
  --pkg-config-flags=--static \
  "--extra-cflags=-fstack-protector-strong -D_FORTIFY_SOURCE=2" \
  "--extra-ldflags=-static -Wl,-z,relro,-z,now,-z,noexecstack" \
  --enable-protocol="$PROTOCOLS" \
  --enable-indev=lavfi \
  --enable-demuxer="$DEMUXERS" \
  --enable-muxer="$MUXERS" \
  --enable-decoder="$DECODERS" \
  --enable-encoder="$ENCODERS" \
  --enable-parser="$PARSERS" \
  --enable-bsf="$BSFS" \
  --enable-filter="$FILTERS"

if [ "${CONFIGURE_ONLY:-0}" = "1" ]; then
  echo "CONFIGURE_ONLY: configure lolos, build dilewati"
  exit 0
fi

make -j"$(nproc)"
strip -s ffmpeg ffprobe
install -m 0755 ffmpeg ffprobe "$OUT/"

BIN="$OUT/ffmpeg"
if ldd "$BIN" >/dev/null 2>&1; then
  echo "GAGAL: biner tidak statis" >&2
  ldd "$BIN" >&2 || true
  exit 1
fi
"$BIN" -hide_banner -version | head -n1
ls -lh "$OUT"

# --- Smoke test fungsi yang dijanjikan ---
T="$(mktemp -d)"
F=(-hide_banner -loglevel error -y)
"$BIN" "${F[@]}" -f lavfi -i testsrc=d=3:s=320x240:r=25 -f lavfi -i sine=d=3 \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$T/a.mp4"
"$BIN" "${F[@]}" -i "$T/a.mp4" -ss 1 -t 1 -c copy "$T/cut.mp4"
"$BIN" "${F[@]}" -i "$T/a.mp4" -vn -c:a copy "$T/a.m4a"
"$BIN" "${F[@]}" -i "$T/a.mp4" -vn -c:a libmp3lame "$T/a.mp3"
"$BIN" "${F[@]}" -i "$T/a.mp4" -vn -c:a libopus "$T/a.opus"
"$BIN" "${F[@]}" -i "$T/a.mp4" -vn -c:a libvorbis "$T/a.ogg"
"$BIN" "${F[@]}" -i "$T/a.mp4" -c:v libx264 -c:a aac "$T/a.mkv"
"$BIN" "${F[@]}" -i "$T/a.mp4" -frames:v 1 "$T/thumb.png"
printf '1\n00:00:00,000 --> 00:00:01,000\nhalo\n' > "$T/s.srt"
"$BIN" "${F[@]}" -i "$T/s.srt" -c:s ass -f ass "$T/s.ass"
"$BIN" "${F[@]}" -i "$T/s.ass" -c:s srt -f srt "$T/s2.srt"
for f in cut.mp4 a.m4a a.mp3 a.opus a.ogg a.mkv thumb.png s.ass s2.srt; do
  test -s "$T/$f" || { echo "Smoke test GAGAL: $f" >&2; exit 1; }
done
"$OUT/ffprobe" -v error -show_entries format=format_name -of csv=p=0 "$T/a.mp4" >/dev/null
echo "Smoke test OK"

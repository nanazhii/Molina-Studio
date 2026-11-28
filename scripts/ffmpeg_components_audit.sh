#!/usr/bin/env bash
# Periksa komponen FFmpeg yang BENAR-BENAR terkompilasi (dari config_components.h hasil configure).
# Pakai: bash scripts/ffmpeg_components_audit.sh <config_components.h> [keluaran.txt]
# GAGAL (exit 1) bila ada protokol/demuxer terlarang aktif atau jumlah demuxer melebihi batas.
set -euo pipefail
H="${1:?config_components.h wajib}"; OUT="${2:-}"
[ -f "$H" ] || { echo "tidak ada: $H" >&2; exit 2; }
MAX_DEMUXERS="${MAX_DEMUXERS:-110}"; MAX_PROTOCOLS="${MAX_PROTOCOLS:-20}"
enabled() { sed -n "s/^#define CONFIG_\\(.*\\)_$1 1\$/\\1/p" "$H" | tr 'A-Z' 'a-z' | sort; }
protos="$(enabled PROTOCOL)"; demux="$(enabled DEMUXER)"
np=$(printf '%s\n' "$protos" | grep -c . || true); nd=$(printf '%s\n' "$demux" | grep -c . || true)
echo "protokol aktif: $np | demuxer aktif: $nd"
bad=0
for p in ftp concat concatf subfile pipe fd unix tee md5 gopher gophers sftp android_content async cache srtp udplite \
         rtmpt rtmpe rtmpte rtmpts mmsh mmst icecast sctp prompeg dtls bluray ipfs_gateway ipns_gateway; do
  if printf '%s\n' "$protos" | grep -qx "$p"; then echo "GAGAL: protokol terlarang aktif: $p" >&2; bad=1; fi
done
for d in concat ffmetadata data sap rtp; do
  if printf '%s\n' "$demux" | grep -qx "$d"; then echo "GAGAL: demuxer terlarang aktif: $d" >&2; bad=1; fi
done
[ "$np" -le "$MAX_PROTOCOLS" ] || { echo "GAGAL: protokol $np > batas $MAX_PROTOCOLS" >&2; bad=1; }
[ "$nd" -le "$MAX_DEMUXERS" ] || { echo "GAGAL: demuxer $nd > batas $MAX_DEMUXERS" >&2; bad=1; }
# [PROFIL-GATE] Bukti profil Molina benar-benar dipakai (tangkap regresi source yang gagal diam-diam).
MIN_DEMUXERS="${MIN_DEMUXERS:-40}"; MIN_PROTOCOLS="${MIN_PROTOCOLS:-8}"
[ "$np" -ge "$MIN_PROTOCOLS" ] || { echo "GAGAL: protokol $np < minimum $MIN_PROTOCOLS (profil tidak terpakai/terlalu sempit)" >&2; bad=1; }
[ "$nd" -ge "$MIN_DEMUXERS" ] || { echo "GAGAL: demuxer $nd < minimum $MIN_DEMUXERS (profil tidak terpakai/terlalu sempit)" >&2; bad=1; }
for p in file http https tcp tls; do
  grep -qx "$p" <<< "$protos" || { echo "GAGAL: protokol wajib tidak aktif: $p" >&2; bad=1; }
done
for d in matroska mov mpegts hls; do
  grep -qx "$d" <<< "$demux" || { echo "GAGAL: demuxer wajib tidak aktif: $d" >&2; bad=1; }
done
CFG="$(dirname "$H")/config.h"
if [ -f "$CFG" ]; then
  conf="$(grep -m1 '^#define FFMPEG_CONFIGURATION ' "$CFG" || true)"
  for m in "--disable-protocols" "--disable-demuxers" "--enable-protocol=" "--enable-demuxer=" "fstack-protector-strong" "-Wl,-z,relro,-z,now,-z,noexecstack"; do
    case "$conf" in *"$m"*) ;; *) echo "GAGAL: configure tanpa penanda profil Molina: $m" >&2; bad=1 ;; esac
  done
else
  echo "GAGAL: tidak ada $CFG (profil tidak bisa dibuktikan terpakai)" >&2; bad=1
fi
if [ -n "$OUT" ]; then { echo "# protokol ($np)"; printf '%s\n' "$protos"; echo "# demuxer ($nd)"; printf '%s\n' "$demux"; } > "$OUT"; fi
[ "$bad" -eq 0 ] && echo "OK: permukaan FFmpeg sesuai profil"
exit "$bad"

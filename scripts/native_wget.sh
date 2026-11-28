#!/bin/bash
# Pembungkus unduhan build-time (menggantikan wget upstream): HTTPS saja, TLS >= 1.2,
# mencatat SHA-256 ke $NATIVE_HASH_LOG, dan memverifikasi terhadap $NATIVE_PINS_FILE
# bila berkas itu tidak kosong (mode verifikasi: URL yang belum dipin ditolak).
set -euo pipefail
url=""; out=""
while [ $# -gt 0 ]; do
  case "$1" in
    -O) out="$2"; shift 2 ;;
    --progress=*|-q|-nv) shift ;;
    http://*|https://*) url="$1"; shift ;;
    *) echo "native_wget: argumen tak dikenal: $1" >&2; exit 2 ;;
  esac
done
[ -n "$url" ] || { echo "native_wget: URL kosong" >&2; exit 2; }
url="https://${url#*://}"
[ -n "$out" ] || out="$(basename "$url")"
tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
curl -fsSL --retry 3 --proto '=https' --tlsv1.2 "$url" -o "$tmp"
sum="$(sha256sum "$tmp" | cut -d' ' -f1)"
echo "$sum  $url" >> "${NATIVE_HASH_LOG:?}"
pins="${NATIVE_PINS_FILE:?}"
if [ -s "$pins" ]; then
  want="$(awk -v u="$url" '$2==u{print $1; exit}' "$pins")"
  [ -n "$want" ] || { echo "native_wget: URL belum dipin: $url" >&2; exit 1; }
  [ "$want" = "$sum" ] || { echo "native_wget: SHA-256 TIDAK COCOK: $url" >&2; exit 1; }
fi
if [ "$out" = "-" ]; then cat "$tmp"; else mv "$tmp" "$out"; trap - EXIT; fi

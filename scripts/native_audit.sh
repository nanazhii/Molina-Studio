#!/usr/bin/env bash
# Audit pengerasan (hardening) pustaka native mpv/ffmpeg. Hanya membaca; tidak mengubah apa pun.
# Pakai: bash scripts/native_audit.sh [folder]   (butuh readelf: apt install binutils)
# GAGAL (exit 1): stack bisa dieksekusi, atau TEXTREL. PERINGATAN: tanpa RELRO, BIND_NOW, atau canary.
set -u
DIR="${1:-feature/mpv/src/main/jniLibs/arm64-v8a}"
command -v readelf >/dev/null || { echo "readelf tidak ada (apt install binutils)" >&2; exit 2; }
[ -d "$DIR" ] || { echo "folder tidak ada: $DIR" >&2; exit 2; }
fail=0; warn=0; n=0
for f in "$DIR"/*.so; do
  [ -e "$f" ] || { echo "tidak ada .so di $DIR" >&2; exit 2; }
  n=$((n+1)); name=$(basename "$f"); msgs=""
  fl=$(readelf -lW "$f" 2>/dev/null | awk '/GNU_STACK/ {print $(NF-1)}')
  case "$fl" in
    *E*) msgs="$msgs GAGAL:stack-executable"; fail=$((fail+1)) ;;
    "")  msgs="$msgs PERINGATAN:tanpa-GNU_STACK"; warn=$((warn+1)) ;;
  esac
  dyn=$(readelf -dW "$f" 2>/dev/null)
  if echo "$dyn" | grep -q TEXTREL; then msgs="$msgs GAGAL:TEXTREL"; fail=$((fail+1)); fi
  if ! readelf -lW "$f" 2>/dev/null | grep -q GNU_RELRO; then msgs="$msgs PERINGATAN:tanpa-RELRO"; warn=$((warn+1)); fi
  if ! echo "$dyn" | grep -Eq 'BIND_NOW|FLAGS.*NOW|FLAGS_1.*NOW'; then msgs="$msgs PERINGATAN:tanpa-BIND_NOW"; warn=$((warn+1)); fi
  if ! readelf --dyn-syms -W "$f" 2>/dev/null | grep -q __stack_chk_fail; then msgs="$msgs info:tanpa-stack-canary"; fi
  if [ -z "$msgs" ]; then echo "OK      $name"; else echo "$name:$msgs"; fi
done
echo "--- $n pustaka, gagal=$fail, peringatan=$warn"
[ "$fail" -eq 0 ]

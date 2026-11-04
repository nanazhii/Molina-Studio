#!/usr/bin/env bash
# [BANNER] Uji pemasangan banner di assets/molina/bashrc: hanya menampilkan berkas banner; kosong/hilang = tanpa keluaran.
set -uo pipefail
cd "$(dirname "$0")/../.."
RC=app/src/main/assets/molina/bashrc
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
LINE="$(grep -F '[ -s "${MOLINA_BANNER_FILE:-' "$RC")"
[ -n "$LINE" ] || { echo "GAGAL: baris banner tidak ditemukan"; exit 1; }
grep -q '__molina_banner' "$RC" && { echo "GAGAL: sisa fungsi banner lama"; exit 1; }
runs=0; fails=0
check() { runs=$((runs+1)); if ! eval "$2"; then fails=$((fails+1)); echo "GAGAL: $1"; fi; }
show() { MOLINA_BANNER_FILE="$1" bash -c "$LINE" 2>&1; }

printf 'baris1\nbaris2\n' > "$T/ada"; : > "$T/kosong"
out=$(show "$T/ada");     check "berkas berisi: ditampilkan"  '[ "$out" = "$(printf "baris1\nbaris2")" ]'
out=$(show "$T/kosong");  check "berkas kosong: tanpa keluaran" '[ -z "$out" ]'
out=$(show "$T/hilang");  check "berkas hilang: tanpa keluaran" '[ -z "$out" ]'
show "$T/hilang" >/dev/null; check "status keluar 0 saat hilang" '[ $? -eq 0 ]'

[ "$fails" -eq 0 ] && echo "OK: $runs uji banner lulus" || echo "$fails dari $runs uji banner GAGAL"
exit "$fails"

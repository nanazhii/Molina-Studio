#!/bin/sh
# Molina Terminal - pembungkus nano (dibuat otomatis, ditimpa tiap sesi).
# Tugas: pakai nanorc Molina bila pengguna tak punya ~/.nanorc, ekspor MOLINA_NANO_FILE untuk makro
# "Buka di Code", dan beri judul sesi "nano: <berkas>" agar aplikasi menampilkan baris tombol nano.
real="${MOLINA_NANO_BIN:-/usr/bin/nano}"
if [ ! -x "$real" ]; then
  echo "nano belum terpasang. Pasang lewat Setelan > Paket (Starter) atau: apt-get install nano" >&2
  exit 127
fi
h="${HOME:-/root}"
rc="${MOLINA_NANORC:-/etc/molina/nanorc}"
if [ -f "$h/.nanorc" ] || [ -f "${XDG_CONFIG_HOME:-$h/.config}/nano/nanorc" ]; then rc=""; fi
# Berkas yang diedit = argumen bukan-opsi terakhir (opsi -x dan +BARIS dilewati).
file=""
for a in "$@"; do
  case "$a" in -*|+*) ;; *) file="$a" ;; esac
done
name="(baru)"
if [ -n "$file" ]; then
  case "$file" in /*) ;; *) file="$PWD/$file" ;; esac
  MOLINA_NANO_FILE="$file"; export MOLINA_NANO_FILE
  name=$(basename "$file" | tr -d '\000-\037\177' | cut -c1-40)
fi
if [ -t 1 ]; then
  printf '\033]0;nano: %s\007' "$name"
  trap 'printf "\033]0;\007"' EXIT
  trap 'exit 143' TERM HUP
fi
# [NANO-RUN] Alt+R di nano: simpan + buat berkas penanda + keluar. Setelah nano selesai, skrip dijalankan dari berkasnya.
flag="${TMPDIR:-/tmp}/molina-run.$$"
rm -f "$flag"
MOLINA_RUN_FLAG="$flag"; export MOLINA_RUN_FLAG
if [ -n "$rc" ]; then "$real" --rcfile="$rc" "$@"; else "$real" "$@"; fi
st=$?
if [ -e "$flag" ]; then
  rm -f "$flag"
  if [ "$st" -eq 0 ] && [ -n "${MOLINA_NANO_FILE:-}" ] && [ -f "$MOLINA_NANO_FILE" ]; then
    if [ -t 1 ]; then printf '\033]0;\007'; fi
    trap : INT
    "${MOLINA_RUN_BIN:-/usr/local/bin/molina-run}" "$MOLINA_NANO_FILE"
    st=$?
  fi
fi
exit $st

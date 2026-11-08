#!/bin/sh
# Molina Terminal - molina-run <berkas> [argumen...] (dibuat otomatis, ditimpa tiap sesi).
# Jalankan skrip dari BERKAS, bukan ditempel ke terminal, jadi skrip panjang tidak terpotong.
# Penerjemah: shebang baris pertama; bila tak ada, dari ekstensi (py sh bash js pl rb lua php awk).
# Direktori kerja tidak diubah (sama seperti `sh berkas`). Dipakai nano lewat Alt+R.
f="$1"
if [ -z "$f" ]; then
  echo "Pemakaian: molina-run <berkas> [argumen...]" >&2
  exit 2
fi
shift
if [ ! -f "$f" ]; then
  echo "molina-run: berkas tidak ditemukan: $f" >&2
  exit 2
fi
case "$f" in /*) ;; *) f="$PWD/$f" ;; esac
first=$(head -n 1 "$f" 2>/dev/null | tr -d '\r')
set -f
case "$first" in
  '#!'*)
    line=${first#??}
    set -- $line "$f" "$@"
    if [ "${1##*/}" = "env" ]; then
      shift
      while :; do
        case "$1" in -*|*=*) shift ;; *) break ;; esac
      done
    fi
    if ! command -v "$1" >/dev/null 2>&1; then
      alt=$(command -v "${1##*/}" 2>/dev/null)
      if [ -n "$alt" ]; then
        shift
        set -- "$alt" "$@"
      fi
    fi
    ;;
  *)
    case "$f" in
      *.py) set -- python3 "$f" "$@" ;;
      *.sh) if command -v bash >/dev/null 2>&1; then set -- bash "$f" "$@"; else set -- sh "$f" "$@"; fi ;;
      *.bash) set -- bash "$f" "$@" ;;
      *.js|*.mjs|*.cjs) set -- node "$f" "$@" ;;
      *.pl) set -- perl "$f" "$@" ;;
      *.rb) set -- ruby "$f" "$@" ;;
      *.lua) set -- lua "$f" "$@" ;;
      *.php) set -- php "$f" "$@" ;;
      *.awk) set -- awk -f "$f" "$@" ;;
      *)
        echo "molina-run: tidak tahu cara menjalankan ${f##*/}. Tambahkan shebang di baris pertama (contoh: #!/bin/sh) atau pakai ekstensi .py .sh .js" >&2
        exit 126
        ;;
    esac
    ;;
esac
set +f
interp="$1"
if ! command -v "$interp" >/dev/null 2>&1; then
  echo "molina-run: penerjemah tidak ditemukan: $interp (pasang dengan: apt-get install ${interp##*/})" >&2
  exit 127
fi
cr=$(printf '\r')
case "${interp##*/}" in
  sh|bash|dash)
    if grep -q "$cr" "$f" 2>/dev/null; then
      echo "peringatan: ${f##*/} memakai akhir baris Windows (CRLF). Perbaiki: sed -i 's/\\r\$//' ${f##*/}" >&2
    fi
    ;;
esac
g=""; r=""; z=""
if [ -t 1 ]; then g=$(printf '\033[1;32m'); r=$(printf '\033[1;31m'); z=$(printf '\033[0m'); fi
printf '%s▶ %s (%s)%s\n' "$g" "${f##*/}" "${interp##*/}" "$z"
t0=$(date +%s)
trap : INT
"$@"
rc=$?
trap - INT
dt=$(( $(date +%s) - t0 ))
if [ "$rc" -eq 0 ]; then
  printf '%s✔ selesai (%s dtk)%s\n' "$g" "$dt" "$z"
elif [ "$rc" -eq 130 ]; then
  printf '%s✘ dihentikan (Ctrl+C)%s\n' "$r" "$z"
else
  printf '%s✘ keluar dengan kode %s (%s dtk)%s\n' "$r" "$rc" "$dt" "$z"
fi
exit "$rc"

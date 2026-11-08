#!/bin/sh
# Molina Terminal - pembungkus nano (dibuat otomatis, ditimpa tiap sesi).
real=/usr/bin/nano
if [ ! -x "$real" ]; then
  echo "nano belum terpasang. Pasang lewat Setelan > Paket (Starter) atau: apt-get install nano" >&2
  exit 127
fi
h="${HOME:-/root}"
if [ -f "$h/.nanorc" ] || [ -f "${XDG_CONFIG_HOME:-$h/.config}/nano/nanorc" ]; then
  exec "$real" "$@"
fi
exec "$real" --rcfile=/etc/molina/nanorc "$@"

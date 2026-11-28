#!/bin/sh
# Molina CLI: jembatan Debian -> aplikasi Molina. Dibuat otomatis oleh aplikasi (jangan diedit).
if ! command -v python3 >/dev/null 2>&1; then
  echo "molina: python3 belum terpasang. Jalankan: apt install -y python3" >&2
  exit 127
fi
exec python3 /usr/local/lib/molina/molina_cli.py "$@"

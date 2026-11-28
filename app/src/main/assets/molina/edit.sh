#!/bin/sh
# Molina Terminal - perintah `edit` (dibuat otomatis, ditimpa tiap sesi): buka berkas di nano Molina.
# Contoh: edit catatan.txt   |   edit +42 skrip.sh (langsung ke baris 42)   |   molina keys (daftar pintasan)
nano="${MOLINA_NANO_WRAPPER:-/usr/local/bin/nano}"
if [ "$#" -eq 0 ]; then
  echo "Pemakaian: edit <berkas>    (berkas baru dibuat saat disimpan)" >&2
  echo "Pintasan : molina keys" >&2
  exit 2
fi
exec "$nano" "$@"

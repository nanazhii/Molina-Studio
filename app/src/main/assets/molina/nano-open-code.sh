#!/bin/sh
# Molina Terminal - dipanggil makro nano (Alt+O): buka berkas yang sedang diedit di Molina Code.
f="${MOLINA_NANO_FILE:-}"
if [ -z "$f" ] || [ ! -f "$f" ]; then
  molina notify "Buka di Code: simpan berkas dulu (Ctrl+S), lalu coba lagi" >/dev/null 2>&1
  exit 1
fi
out=$(molina open "$f" 2>&1) || molina notify "Buka di Code gagal: $out" >/dev/null 2>&1
exit 0

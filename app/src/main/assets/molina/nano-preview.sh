#!/bin/sh
# [NANO-PREVIEW] Molina Terminal - dipanggil makro nano (Alt+P): simpan lalu pratinjau berkas (Markdown/HTML/JSON).
f="${MOLINA_NANO_FILE:-}"
if [ -z "$f" ] || [ ! -f "$f" ]; then
  molina notify "Pratinjau: simpan berkas dulu (Ctrl+S), lalu coba lagi" >/dev/null 2>&1
  exit 1
fi
out=$(molina preview "$f" 2>&1) || molina notify "Pratinjau gagal: $out" >/dev/null 2>&1
exit 0

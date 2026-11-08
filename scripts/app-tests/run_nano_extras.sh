#!/usr/bin/env bash
# [NANO-EXTRAS] Uji perintah `edit` (assets/molina/edit.sh) dan sorotan sintaks Molina (molina-media.nanorc).
# Bagian sintaks memakai nano ASLI lewat pty; dilewati bila nano atau python3 tak ada.
set -uo pipefail
cd "$(dirname "$0")/../.."
E="$PWD/app/src/main/assets/molina/edit.sh"
S="$PWD/app/src/main/assets/molina/molina-media.nanorc"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
runs=0; fails=0
check() { runs=$((runs+1)); if ! eval "$2"; then fails=$((fails+1)); echo "GAGAL: $1"; fi; }
printf '#!/bin/sh\necho "ARGS:$*"\n' > "$T/fakenano"; chmod +x "$T/fakenano"
ed() { MOLINA_NANO_WRAPPER="$T/fakenano" sh "$E" "$@" 2>&1; }
out="$(ed a.txt)";            check "edit meneruskan berkas ke nano"   '[ "$out" = "ARGS:a.txt" ]'
out="$(ed +42 a.txt)";        check "edit meneruskan +BARIS"           '[ "$out" = "ARGS:+42 a.txt" ]'
out="$(ed 'a b.txt')";        check "edit menjaga nama berspasi"       '[ "$out" = "ARGS:a b.txt" ]'
out="$(ed)"; rc=$?;           check "edit tanpa argumen: exit 2 + cara pakai" '[ $rc -eq 2 ] && [[ "$out" == *"Pemakaian: edit"* ]]'

if command -v nano >/dev/null 2>&1 && command -v python3 >/dev/null 2>&1; then
  printf '#EXTM3U\n#EXTINF:-1 tvg-id="a",Satu\nhttp://x/y\n' > "$T/x.m3u"
  # syn <nama-sintaks|-> <rcfile>: jalankan nano sebentar, cetak layar (tanpa kode warna)
  syn() { (cd "$T" && HOME="$T" TERM=xterm-256color python3 - "$1" "$2" << 'PYEOF'
import os, pty, re, select, sys, time
name, rc = sys.argv[1], sys.argv[2]
args = ["nano", "--rcfile=" + rc] + ([] if name == "-" else ["-Y", name]) + ["x.m3u"]
pid, fd = pty.fork()
if pid == 0:
    os.execvp("nano", args)
out = b""
end = time.time() + 1.2
while time.time() < end:
    r, _, _ = select.select([fd], [], [], 0.1)
    if r:
        try: out += os.read(fd, 65536)
        except OSError: break
os.kill(pid, 9)
print(re.sub(rb"\x1b\[[0-9;?]*[A-Za-z]", b"", out).decode("utf8", "replace"))
PYEOF
  ); }
  printf 'include "%s"\n' "$S" > "$T/rc"
  for n in m3u mpv-conf mpv-input; do
    out="$(syn "$n" "$T/rc")"; check "sintaks $n dikenali nano" '[[ "$out" != *nknown* && "$out" != *rror* ]]'
  done
  out="$(syn bukan-sintaks "$T/rc")"; check "kontrol: nama sintaks palsu ditolak" '[[ "$out" == *nknown* ]]'
  printf 'include "/tidak/ada/*.nanorc"\n' > "$T/rc2"
  out="$(syn - "$T/rc2")"; check "include pola kosong tidak menimbulkan galat" '[[ "$out" != *rror* ]]'
else
  echo "nano/python3 tidak ada: tes sintaks dilewati"
fi
echo "Selesai: $runs kasus, $fails gagal"; [ "$fails" -eq 0 ]

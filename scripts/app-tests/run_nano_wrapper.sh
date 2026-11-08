#!/usr/bin/env bash
# [NANO-WRAPPER] Uji pembungkus nano Molina (assets/molina/nano-wrapper.sh) dengan nano palsu. Tanpa nano asli.
set -uo pipefail
cd "$(dirname "$0")/../.."
W="$PWD/app/src/main/assets/molina/nano-wrapper.sh"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
ESC=$(printf '\033'); BEL=$(printf '\007')
runs=0; fails=0
check() { runs=$((runs+1)); if ! eval "$2"; then fails=$((fails+1)); echo "GAGAL: $1"; fi; }
cat > "$T/fakenano" << 'EOS'
#!/bin/sh
echo "ARGS:$*"
echo "FILE:${MOLINA_NANO_FILE:-}"
exit "${FAKE_EXIT:-0}"
EOS
chmod +x "$T/fakenano"; mkdir -p "$T/home" "$T/work"
run() { (cd "$T/work" && HOME="$T/home" MOLINA_NANO_BIN="$T/fakenano" MOLINA_NANORC="$T/rc" sh "$W" "$@" 2>&1); }
inpty() { (cd "$T/work" && HOME="$T/home" MOLINA_NANO_BIN="$T/fakenano" MOLINA_NANORC="$T/rc" python3 -c '
import os, pty, sys
buf = []
def rd(fd):
    d = os.read(fd, 4096); buf.append(d); return d
st = pty.spawn(["sh", sys.argv[1]] + sys.argv[2:], rd)
sys.stdout.buffer.write(b"".join(buf))
sys.exit(os.WEXITSTATUS(st))' "$W" "$@"); }

out="$(run a.txt)"
check "pakai nanorc Molina" '[[ "$out" == *"ARGS:--rcfile=$T/rc a.txt"* ]]'
check "MOLINA_NANO_FILE absolut dari path relatif" '[[ "$out" == *"FILE:$T/work/a.txt"* ]]'
out="$(run -l +5 /x/y.txt)"
check "opsi dan +BARIS dilewati" '[[ "$out" == *"FILE:/x/y.txt"* ]]'
out="$(run)"
check "tanpa berkas: MOLINA_NANO_FILE kosong" '[[ "$out" == *"FILE:" || "$out" == *"FILE:"[[:space:]]* ]]'
touch "$T/home/.nanorc"; out="$(run a.txt)"
check "~/.nanorc pengguna diutamakan" '[[ "$out" == *"ARGS:a.txt"* && "$out" != *"--rcfile"* ]]'
rm -f "$T/home/.nanorc"
run a.txt >/dev/null; rc=$?
check "exit 0 diteruskan" '[ $rc -eq 0 ]'
FAKE_EXIT=3 run a.txt >/dev/null; rc=$?
check "exit 3 diteruskan" '[ $rc -eq 3 ]'
out="$(cd "$T/work" && MOLINA_NANO_BIN=/tidak/ada sh "$W" a 2>&1)"; rc=$?
check "nano belum terpasang: exit 127 + pesan" '[ $rc -eq 127 ] && [[ "$out" == *"belum terpasang"* ]]'
out="$(inpty a.txt)"
check "pty: judul diset sebelum nano" '[[ "$out" == *"${ESC}]0;nano: a.txt${BEL}"* ]]'
check "pty: judul dikosongkan setelah nano" '[[ "$out" == *"ARGS:"*"${ESC}]0;${BEL}"* ]]'
out="$(inpty "a${BEL}b.txt")"
check "pty: karakter kontrol dibuang dari judul" '[[ "$out" == *"${ESC}]0;nano: ab.txt${BEL}"* ]]'
FAKE_EXIT=3 inpty a.txt >/dev/null; rc=$?
check "pty: exit 3 diteruskan" '[ $rc -eq 3 ]'
echo "Selesai: $runs kasus, $fails gagal"; [ "$fails" -eq 0 ]

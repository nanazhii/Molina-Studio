#!/usr/bin/env bash
# [NANO-RUN] Uji molina-run (assets/molina/molina-run.sh), alur Alt+R di pembungkus nano (nano palsu), dan makro Alt+R di nano ASLI.
# Bagian nano asli dilewati bila nano atau python3 tak ada.
set -uo pipefail
cd "$(dirname "$0")/../.."
R="$PWD/app/src/main/assets/molina/molina-run.sh"
W="$PWD/app/src/main/assets/molina/nano-wrapper.sh"
RC="$PWD/app/src/main/assets/molina/nanorc"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
runs=0; fails=0
check() { runs=$((runs+1)); if ! eval "$2"; then fails=$((fails+1)); echo "GAGAL: $1"; fi; }
mkdir -p "$T/work" "$T/home"
mr() { (cd "$T" && sh "$R" "$@" 2>&1); }

printf 'echo halo "$1"\nexit 3\n' > "$T/a.sh"
out="$(mr a.sh dunia)"; rc=$?
check "sh: dijalankan dengan argumen" '[[ "$out" == *"halo dunia"* ]]'
check "sh: kode keluar 3 diteruskan" '[ $rc -eq 3 ]'
check "sh: pesan kode keluar" '[[ "$out" == *"kode 3"* ]]'
printf 'true\n' > "$T/ok.sh"; out="$(mr ok.sh)"; rc=$?
check "sh: sukses -> selesai + exit 0" '[ $rc -eq 0 ] && [[ "$out" == *"selesai"* ]]'
if command -v python3 >/dev/null 2>&1; then
  printf 'import sys\nprint("py", sys.argv[1:])\n' > "$T/b.py"; out="$(mr b.py x)"
  check "py: dijalankan dengan argumen" '[[ "$out" == *"py ["*"x"*"]"* ]]'
  printf '#!/usr/bin/env python3\nprint("env ok")\n' > "$T/c"; out="$(mr c)"
  check "shebang env python3 tanpa ekstensi" '[[ "$out" == *"env ok"* ]]'
fi
printf '#!/bin/sh\r\necho crlf\r\n' > "$T/d.sh"; out="$(mr d.sh)"
check "shebang CRLF tetap jalan + peringatan CRLF" '[[ "$out" == *"crlf"* && "$out" == *"CRLF"* ]]'
printf 'x\n' > "$T/e.xyz"; out="$(mr e.xyz)"; rc=$?
check "ekstensi asing: exit 126 + petunjuk shebang" '[ $rc -eq 126 ] && [[ "$out" == *"shebang"* ]]'
printf '#!/usr/bin/tidak-ada-xyz\n' > "$T/f.sh"; out="$(mr f.sh)"; rc=$?
check "penerjemah tak ada: exit 127" '[ $rc -eq 127 ]'
out="$(mr tidak-ada.sh)"; rc=$?
check "berkas tak ada: exit 2" '[ $rc -eq 2 ]'
out="$(mr)"; rc=$?
check "tanpa argumen: exit 2 + cara pakai" '[ $rc -eq 2 ] && [[ "$out" == *"Pemakaian"* ]]'
# Inti fitur: skrip sangat panjang (satu baris 60.000 karakter) berjalan utuh dari berkas
printf 'echo %s\n' "$(head -c 60000 /dev/zero | tr '\0' a)" > "$T/long.sh"; out="$(mr long.sh)"
check "skrip 60.000 karakter berjalan utuh" '[ ${#out} -ge 60000 ]'

# Alur Alt+R di pembungkus (nano dan runner palsu)
printf '#!/bin/sh\n[ -n "${FAKE_FLAG:-}" ] && : > "$MOLINA_RUN_FLAG"\nexit "${FAKE_EXIT:-0}"\n' > "$T/fakenano"
printf '#!/bin/sh\necho "RUN:$*"\nexit 7\n' > "$T/fakerun"; chmod +x "$T/fakenano" "$T/fakerun"
echo 'true' > "$T/work/a.sh"
w() { (cd "$T/work" && env HOME="$T/home" MOLINA_NANO_BIN="$T/fakenano" MOLINA_NANORC="$T/rc" MOLINA_RUN_BIN="$T/fakerun" "$@" sh "$W" a.sh 2>&1); }
out="$(w FAKE_FLAG=1)"; rc=$?
check "Alt+R: skrip dijalankan setelah nano" '[[ "$out" == *"RUN:$T/work/a.sh"* ]]'
check "Alt+R: kode keluar runner diteruskan" '[ $rc -eq 7 ]'
out="$(w FAKE_FLAG=)"; rc=$?
check "tanpa Alt+R: tidak dijalankan, exit 0" '[[ "$out" != *"RUN:"* ]] && [ $rc -eq 0 ]'
out="$(w FAKE_FLAG=1 FAKE_EXIT=2)"; rc=$?
check "nano gagal: skrip tidak dijalankan, exit 2" '[[ "$out" != *"RUN:"* ]] && [ $rc -eq 2 ]'

# Makro Alt+R di nano ASLI: simpan + buat penanda + keluar
# [NANO-TEST-REALBIN] di perangkat "nano" = pembungkus (menimpa MOLINA_RUN_FLAG): uji biner asli
NANO_REAL="${MOLINA_NANO_BIN:-/usr/bin/nano}"; [ -x "$NANO_REAL" ] || NANO_REAL="$(command -v nano || true)"
if [ -n "$NANO_REAL" ] && command -v python3 >/dev/null 2>&1; then
  printf 'echo satu\n' > "$T/work/m.sh"
  res="$(cd "$T/work" && HOME="$T" TERM=xterm-256color NANO_REAL="$NANO_REAL" MOLINA_RUN_FLAG="$T/flag" python3 - "$RC" << 'PYEOF'
import os, pty, select, sys, time
pid, fd = pty.fork()
if pid == 0:
    os.execvp(os.environ["NANO_REAL"], ["nano", "--rcfile=" + sys.argv[1], "m.sh"])
def drain(t):
    end = time.time() + t
    while time.time() < end:
        r, _, _ = select.select([fd], [], [], 0.1)
        if r:
            try: os.read(fd, 65536)
            except OSError: return
drain(1.5)
os.write(fd, b"#"); drain(0.5)
os.write(fd, b"\x1br")
done = False
for _ in range(40):
    drain(0.2)
    time.sleep(0.2)  # drain kembali seketika bila pty sudah ditutup nano (EIO)
    p, _ = os.waitpid(pid, os.WNOHANG)
    if p:
        done = True
        break
if not done:
    os.kill(pid, 9)
print("EXIT" if done else "ALIVE")
PYEOF
)"
  check "nano asli: Alt+R membuat nano keluar" '[ "$res" = "EXIT" ]'
  check "nano asli: Alt+R menyimpan berkas" '[ "$(head -c 11 "$T/work/m.sh")" = "#echo satu" ]'
  check "nano asli: Alt+R membuat berkas penanda" '[ -e "$T/flag" ]'
else
  echo "nano/python3 tidak ada: tes nano asli dilewati"
fi
echo "Selesai: $runs kasus, $fails gagal"; [ "$fails" -eq 0 ]

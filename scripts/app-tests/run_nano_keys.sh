#!/usr/bin/env bash
# [NANO-KEYS] Uji makro Ctrl+A (pilih semua) di nanorc Molina dengan nano ASLI lewat pty. Dilewati bila nano tak ada.
set -uo pipefail
cd "$(dirname "$0")/../.."
command -v nano >/dev/null 2>&1 || { echo "nano tidak terpasang: tes dilewati"; exit 0; }
command -v python3 >/dev/null 2>&1 || { echo "python3 tidak ada: tes dilewati"; exit 0; }
RC="$PWD/app/src/main/assets/molina/nanorc"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
runs=0; fails=0
check() { runs=$((runs+1)); if ! eval "$2"; then fails=$((fails+1)); echo "GAGAL: $1"; fi; }
# keys: urutan tombol (heks, dipisah spasi); keluar: isi berkas setelah nano selesai
drive() { (cd "$T" && printf 'satu\ndua\ntiga\n' > f.txt && HOME="$T" TERM=xterm-256color python3 - "$RC" "$@" << 'PYEOF'
import os, pty, select, sys, time
rc, keys = sys.argv[1], sys.argv[2:]
pid, fd = pty.fork()
if pid == 0:
    os.execvp("nano", ["nano", "--rcfile=" + rc, "f.txt"])
def drain(t):
    end = time.time() + t
    while time.time() < end:
        r, _, _ = select.select([fd], [], [], 0.1)
        if r:
            try: os.read(fd, 65536)
            except OSError: return
drain(1.0)
for k in keys:
    os.write(fd, bytes.fromhex(k)); drain(0.3)
for _ in range(20):
    done, _ = os.waitpid(pid, os.WNOHANG)
    if done: break
    drain(0.2)
else:
    os.kill(pid, 9)
print(open("f.txt").read().replace("\n", "|"))
PYEOF
); }
SAVE=13; QUIT=11; CUT=18; ALL=01; DOWN=1b5b42
out="$(drive $ALL $CUT $SAVE $QUIT)";          check "Ctrl+A lalu potong: berkas kosong" '[ "$out" = "" ]'
out="$(drive $DOWN $ALL $CUT $SAVE $QUIT)";    check "Ctrl+A dari tengah berkas: berkas kosong" '[ "$out" = "" ]'
out="$(drive $ALL $ALL $CUT $SAVE $QUIT)";     check "Ctrl+A dua kali = batal seleksi: isi utuh" '[ "$out" = "satu|dua|tiga|" ]'
# [NANO-PROMPT] Teks prompt nano asli yang dibaca aplikasi (NanoKeys) untuk menampilkan tombol YA/TIDAK/BATAL dan ENTER/BATAL.
prompts() { (cd "$T" && printf 'satu\n' > f.txt && HOME="$T" TERM=xterm-256color python3 - "$RC" << 'PYEOF'
import os, pty, re, select, sys, time
pid, fd = pty.fork()
if pid == 0:
    os.execvp("nano", ["nano", "--rcfile=" + sys.argv[1], "f.txt"])
out = b""
def drain(t):
    global out
    end = time.time() + t
    while time.time() < end:
        r, _, _ = select.select([fd], [], [], 0.1)
        if r:
            try: out += os.read(fd, 65536)
            except OSError: return
drain(1.0)
for k in (b"x", b"\x11"):
    os.write(fd, k); drain(0.5)
a = re.sub(rb"\x1b\[[0-9;?]*[A-Za-z]", b"", out).decode("utf8", "replace")
n0 = len(out)
os.write(fd, b"y"); drain(0.5)
b = re.sub(rb"\x1b\[[0-9;?]*[A-Za-z]", b"", out[n0:]).decode("utf8", "replace")
os.kill(pid, 9)
print(("SAVE" if "save modified buffer" in a.lower() else "-") + " " + ("NAME" if "file name to write" in b.lower() else "-"))
PYEOF
); }
out="$(prompts)"
check "prompt 'Save modified buffer' terbaca" '[[ "$out" == SAVE* ]]'
check "prompt 'File Name to Write' terbaca" '[[ "$out" == *NAME ]]'
echo "Selesai: $runs kasus, $fails gagal"; [ "$fails" -eq 0 ]

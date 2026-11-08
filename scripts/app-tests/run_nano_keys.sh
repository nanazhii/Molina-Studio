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
# [NANO-PROMPT-POLL] Menunggu teks muncul (maks 6 dtk, aman di PRoot yang lambat), bukan jeda tetap; bila gagal, cetak keluaran mentah ke stderr.
prompts() { (cd "$T" && printf 'satu\n' > f.txt && HOME="$T" TERM=xterm-256color python3 - "$RC" << 'PYEOF'
import os, pty, re, select, sys, time
pid, fd = pty.fork()
if pid == 0:
    os.execvp("nano", ["nano", "--rcfile=" + sys.argv[1], "f.txt"])
buf = b""
def pump(t):
    global buf
    end = time.time() + t
    while time.time() < end:
        r, _, _ = select.select([fd], [], [], 0.1)
        if r:
            try: buf += os.read(fd, 65536)
            except OSError: return
def clean(b):
    b = re.sub(rb"\x1b(\[[0-9;?]*[A-Za-z]|\([A-Z0-9])|[\r\x00-\x08]", b"", b)
    return re.sub(r"\s+", " ", b.decode("utf8", "replace")).lower()
def send_wait(key, pat):
    start = len(buf)
    os.write(fd, key)
    end = time.time() + 6
    while time.time() < end:
        pump(0.2)
        if re.search(pat, clean(buf[start:])):
            return True
    return False
pump(1.5)
os.write(fd, b"x"); pump(0.8)
save = send_wait(b"\x11", r"save modified buffer")
name = send_wait(b"y", r"file name to write|write to file|write selection to file")
if not (save and name):
    sys.stderr.write("DEBUG nano: save=%s name=%s tail=%r\n" % (save, name, buf[-300:]))
try: os.kill(pid, 9)
except OSError: pass
print(("SAVE" if save else "-") + " " + ("NAME" if name else "-"))
PYEOF
); }
out="$(prompts)"
check "prompt 'Save modified buffer' terbaca" '[[ "$out" == SAVE* ]]'
check "prompt nama berkas terbaca (File Name to Write / Write to File)" '[[ "$out" == *NAME ]]'
echo "Selesai: $runs kasus, $fails gagal"; [ "$fails" -eq 0 ]

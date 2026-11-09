#!/usr/bin/env bash
# [NANO-RUNFIX] Uji parser dialog molina-run (NanoRunPrompt, JVM murni) + kunci teks dialog dari molina-run ASLI lewat pty.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc dan python3 (tanpa python3: hanya uji parser).
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
fail=0
cap=()
if command -v python3 >/dev/null 2>&1; then
  mkdir -p "$OUT/home" "$OUT/cfg"
  printf 'python3 - << '"'"'PYEOF'"'"'\nprint("halo")\nPYEOF\n' > "$OUT/fix.py"   # dapat diperbaiki
  printf 'python3 - << '"'"'PYEOF'"'"'\nprint("halo")\n' > "$OUT/trunc.py"          # penutup hilang
  capture() { # $1 berkas skrip, $2 keluaran bersih; cetak "judul=ya/tidak reset=ya/tidak"
    HOME="$OUT/home" XDG_CONFIG_HOME="$OUT/cfg" TERM=xterm-256color python3 - "$PWD/app/src/main/assets/molina/molina-run.sh" "$1" "$2" << 'PYEOF'
import os, pty, re, select, sys, time
run, script, dest = sys.argv[1:4]
for k in ("MOLINA_RUN_ANSWER", "MOLINA_RUN_ASK"): os.environ.pop(k, None)
pid, fd = pty.fork()
if pid == 0:
    os.chdir(os.path.dirname(script))
    os.execvp("sh", ["sh", run, script])
buf = b""
def pump(t):
    global buf
    end = time.time() + t
    while time.time() < end:
        r, _, _ = select.select([fd], [], [], 0.1)
        if r:
            try: buf += os.read(fd, 65536)
            except OSError: return
end = time.time() + 10
while time.time() < end and b"Pilih [" not in buf:
    pump(0.2)
title = b"\x1b]0;nano-run: " in buf
os.write(fd, b"x\r")
for _ in range(25):
    pump(0.2)
    if os.waitpid(pid, os.WNOHANG)[0]: break
else:
    os.kill(pid, 9)
reset = buf.rfind(b"\x1b]0;\x07") > buf.rfind(b"\x1b]0;nano-run: ")
txt = re.sub(rb"\x1b\][^\x07]*\x07|\x1b\[[0-9;?]*[A-Za-z]", b"", buf).decode("utf8", "replace").replace("\r", "")
open(dest, "w").write(txt)
print("judul=%s reset=%s" % ("ya" if title else "tidak", "ya" if reset else "tidak"))
PYEOF
  }
  r1="$(capture "$OUT/fix.py" "$OUT/cap_fix.txt")"; r2="$(capture "$OUT/trunc.py" "$OUT/cap_trunc.txt")"
  [ "$r1" = "judul=ya reset=ya" ] || { echo "GAGAL: judul sesi nano-run dipasang lalu dihapus (fix): $r1"; fail=1; }
  [ "$r2" = "judul=ya reset=ya" ] || { echo "GAGAL: judul sesi nano-run dipasang lalu dihapus (terpotong): $r2"; fail=1; }
  cap=("$OUT/cap_fix.txt" "$OUT/cap_trunc.txt")
else
  echo "python3 tidak ada: uji pty dilewati"
fi
"$KC" -nowarn -d "$OUT/cls" app/src/main/java/com/molina/studio/terminal/NanoRunPrompt.kt scripts/app-tests/NanoRunPromptTests.kt
java -cp "$OUT/cls:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" NanoRunPromptTestsKt "${cap[@]}" || fail=1
[ "$fail" -eq 0 ]

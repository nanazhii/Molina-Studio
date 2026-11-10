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
# [NANO-FIX]
# [NANO-FIX] Alt+F: nano keluar dengan penanda -> molina-run --fix -> (di terminal) nano dibuka lagi
cat > "$T/fixnano" << 'EOS'
#!/bin/sh
n=$(cat "$T_COUNT" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$T_COUNT"
echo "NANO#$n"
if [ "$n" -eq 1 ] && [ -n "${FAKE_FIX:-}" ]; then touch "$MOLINA_FIX_FLAG"; fi
exit "${FAKE_EXIT:-0}"
EOS
printf '#!/bin/sh\necho "FIXRUN:$*"\n' > "$T/fakerun"; chmod +x "$T/fixnano" "$T/fakerun"
echo isi > "$T/work/a.txt"
fixrun() { (cd "$T/work" && rm -f "$T/count" && T_COUNT="$T/count" FAKE_FIX=1 HOME="$T/home" MOLINA_NANO_BIN="$T/fixnano" MOLINA_RUN_BIN="$T/fakerun" MOLINA_NANORC="$T/rc" sh "$W" a.txt 2>&1); }
out="$(fixrun)"
check "fix (non-terminal): molina-run --fix dipanggil dengan berkas absolut" '[[ "$out" == *"FIXRUN:--fix $T/work/a.txt"* ]]'
check "fix (non-terminal): nano tidak dibuka ulang tanpa terminal" '[ "$(cat "$T/count")" = "1" ]'
out="$(cd "$T/work" && rm -f "$T/count" && T_COUNT="$T/count" HOME="$T/home" MOLINA_NANO_BIN="$T/fixnano" MOLINA_RUN_BIN="$T/fakerun" MOLINA_NANORC="$T/rc" sh "$W" a.txt 2>&1)"
check "tanpa penanda: molina-run --fix tidak dipanggil" '[[ "$out" != *"FIXRUN"* ]]'
if command -v python3 >/dev/null 2>&1; then
  out="$(cd "$T/work" && rm -f "$T/count" && T_COUNT="$T/count" FAKE_FIX=1 HOME="$T/home" MOLINA_NANO_BIN="$T/fixnano" MOLINA_RUN_BIN="$T/fakerun" MOLINA_NANORC="$T/rc" python3 -c '
import os, pty, select, sys, time
pid, fd = pty.fork()
if pid == 0:
    os.execvp("sh", ["sh", sys.argv[1], "a.txt"])
buf = b""; sent = False; end = time.time() + 15
while time.time() < end:
    r, _, _ = select.select([fd], [], [], 0.2)
    if r:
        try: d = os.read(fd, 4096)
        except OSError: break
        if not d: break
        buf += d
        if not sent and b"Tekan Enter" in buf:
            os.write(fd, b"\n"); sent = True
sys.stdout.buffer.write(buf)' "$W")"
  check "pty: nano dibuka lagi sesudah Enter" '[[ "$out" == *"NANO#1"*"FIXRUN:--fix"*"Tekan Enter"*"NANO#2"* ]]'
  check "pty: judul nano dipasang ulang sebelum nano kedua" '[[ "$out" == *"Tekan Enter"*"${ESC}]0;nano: a.txt${BEL}"*"NANO#2"* ]]'
fi

# [NANO-DETECT]
# [NANO-DETECT] template shebang untuk berkas baru + saran sesudah nano ditutup
cp "$PWD/app/src/main/assets/molina/molina-run.sh" "$T/mrun"; chmod +x "$T/mrun"
cat > "$T/tplnano" << 'EOS'
#!/bin/sh
echo "ARGS:$*"
echo "HEAD:$(head -n 1 "$MOLINA_NANO_FILE" 2>/dev/null)"
if [ -n "${FAKE_EDIT:-}" ]; then printf 'print(1)\n' >> "$MOLINA_NANO_FILE"; fi
exit 0
EOS
chmod +x "$T/tplnano"
tpl() { (cd "$T/work" && HOME="$T/home" XDG_CONFIG_HOME="$T/home/.config" MOLINA_NANO_BIN="$T/tplnano" MOLINA_RUN_BIN="$T/mrun" MOLINA_NANORC="$T/rc" sh "$W" "$@" 2>&1); }
rm -f "$T/work/n.py" "$T/work/n.sh" "$T/work/n.txt" "$T/work/k.py"
out="$(tpl n.py)"
check "template: berkas .py baru -> shebang terisi saat nano dibuka" '[[ "$out" == *"HEAD:#!/usr/bin/env python3"* ]]'
check "template: kursor di baris 2 (+2)" '[[ "$out" == *"ARGS:--rcfile=$T/rc +2 n.py"* ]]'
check "template: tak diubah -> berkas baru dihapus lagi" '[ ! -e "$T/work/n.py" ]'
out="$(FAKE_EDIT=1 tpl n.py)"
check "template: diubah -> berkas dipertahankan dengan shebang" '[ -f "$T/work/n.py" ] && [ "$(head -n 1 "$T/work/n.py")" = "#!/usr/bin/env python3" ] && grep -q "print(1)" "$T/work/n.py"'
out="$(tpl n.py)"
check "template: berkas berisi tidak diberi +2 dan tidak disentuh" '[[ "$out" == *"ARGS:--rcfile=$T/rc n.py"* && "$out" == *"HEAD:#!/usr/bin/env python3"* ]] && grep -q "print(1)" "$T/work/n.py"'
out="$(tpl n.sh)"
check "template: .sh -> #!/bin/sh" '[[ "$out" == *"HEAD:#!/bin/sh"* ]]'
out="$(tpl n.txt)"
check "template: .txt -> tanpa template, berkas tidak dibuat" '[[ "$out" == *"HEAD:"* && "$out" != *"HEAD:#!"* && "$out" != *"+2"* ]] && [ ! -e "$T/work/n.txt" ]'
out="$(MOLINA_NANO_TEMPLATE=off tpl n.sh)"
check "template: MOLINA_NANO_TEMPLATE=off -> dimatikan" '[[ "$out" != *"HEAD:#!"* && "$out" != *"+2"* ]]'
out="$(tpl -l +5 k.py)"
check "template: +BARIS dari pengguna dihormati (tanpa +2 tambahan)" '[[ "$out" == *"+5 k.py"* && "$out" != *"+2"* ]]'
: > "$T/work/e.py"; out="$(tpl e.py)"
check "template: berkas kosong yang ada -> diisi, lalu dikosongkan lagi bila tak diubah" '[[ "$out" == *"HEAD:#!/usr/bin/env python3"* ]] && [ -e "$T/work/e.py" ] && [ ! -s "$T/work/e.py" ]'
out="$(HOME="$T/home" MOLINA_NANO_BIN="$T/tplnano" MOLINA_RUN_BIN="$T/nonexist" MOLINA_NANORC="$T/rc" sh -c 'cd "$1" && sh "$2" m.py' _ "$T/work" "$W" 2>&1)"
check "template: molina-run tidak ada -> nano tetap jalan tanpa template" '[[ "$out" == *"ARGS:--rcfile=$T/rc m.py"* ]]'
out="$(printf "print(2)\n" > "$T/work/s.py"; tpl s.py)"
check "saran: bukan terminal -> tidak ada saran/tawaran" '[[ "$out" != *"saran untuk"* && "$out" != *"Perbaiki sekarang"* ]]'
if command -v python3 >/dev/null 2>&1; then
  cat > "$T/ptydrv.py" << 'PYX'
import os, pty, select, sys, time
ans = {"ENTER": b"\n", "n": b"n\n", "y": b"y\n"}[sys.argv[1]]
argv = sys.argv[2:]
pid, fd = pty.fork()
if pid == 0:
    os.execvp(argv[0], argv)
buf = b""; sent = False; end = time.time() + 15
while time.time() < end:
    r, _, _ = select.select([fd], [], [], 0.2)
    if r:
        try: d = os.read(fd, 4096)
        except OSError: break
        if not d: break
        buf += d
        if not sent and b"[Y/n]" in buf:
            os.write(fd, ans); sent = True
sys.stdout.buffer.write(buf)
PYX
  ptyw() { (cd "$T/work" && HOME="$T/home" XDG_CONFIG_HOME="$T/home/.config" MOLINA_NANO_BIN="$T/tplnano" MOLINA_RUN_BIN="$T/mrun" MOLINA_NANORC="$T/rc" python3 "$T/ptydrv.py" "$1" sh "$W" "${@:2}" 2>&1); }
  printf "print(3)\n" > "$T/work/p.py"
  out="$(ptyw ENTER p.py)"
  check "pty: .py tanpa shebang -> saran dan tawaran perbaiki" '[[ "$out" == *"saran untuk p.py"*"belum ada shebang"*"Perbaiki sekarang"* ]]'
  check "pty: Enter = ya -> shebang ditambah" '[ "$(head -n 1 "$T/work/p.py")" = "#!/usr/bin/env python3" ] && [ -e "$T/work/p.py.bak" ]'
  printf "print(4)\n" > "$T/work/q.py"
  out="$(ptyw n q.py)"
  check "pty: jawab n -> berkas tidak diubah" '[[ "$out" == *"Perbaiki sekarang"* ]] && [ "$(cat "$T/work/q.py")" = "print(4)" ]'
  printf '#!/bin/sh\necho ok\n' > "$T/work/r.sh"
  out="$(ptyw ENTER r.sh)"
  check "pty: skrip sudah bershebang -> tidak ada tawaran" '[[ "$out" != *"Perbaiki sekarang"* && "$out" != *"saran untuk"* ]]'
  printf "catatan\n" > "$T/work/c.txt"
  out="$(ptyw ENTER c.txt)"
  check "pty: berkas bukan skrip -> tidak ada saran" '[[ "$out" != *"Perbaiki sekarang"* && "$out" != *"saran untuk"* ]]'
  printf "print(5)\n" > "$T/work/o.py"
  out="$(MOLINA_RUN_NOTES=off ptyw ENTER o.py)"
  check "pty: tips dimatikan -> tidak ada saran" '[[ "$out" != *"Perbaiki sekarang"* && "$out" != *"saran untuk"* ]] && [ "$(cat "$T/work/o.py")" = "print(5)" ]'
fi

echo "Selesai: $runs kasus, $fails gagal"; [ "$fails" -eq 0 ]

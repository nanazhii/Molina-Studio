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
# [NANO-STALE-ENV] MOLINA_NANO_FILE warisan (mis. tes dijalankan dari skrip lewat RUN nano) tidak bocor ke nano tanpa berkas
out="$(MOLINA_NANO_FILE=/warisan/lama.sh run)"
check "tanpa berkas: MOLINA_NANO_FILE warisan dibersihkan" '[[ "$out" != *"/warisan/lama.sh"* ]]'
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

# [NANO-INBOX] snapshot sebelum/sesudah nano masuk inbox riwayat Code
mkdir -p "$T/sd/Molina" "$T/sd/work"; IB="$T/sd/Molina/.history-inbox"
hist() { (cd "$T/sd/work" && HOME="$T/home" XDG_CONFIG_HOME="$T/home/.config" MOLINA_NANO_TEMPLATE=off MOLINA_HISTORY_INBOX="$IB" MOLINA_HISTORY_PREFIX="$T/sd" MOLINA_NANO_BIN="$T/tplnano" MOLINA_RUN_BIN="$T/mrun" MOLINA_NANORC="$T/rc" sh "$W" "$@" 2>&1); }
printf 'satu\n' > "$T/sd/work/h.txt"
FAKE_EDIT=1 hist h.txt >/dev/null
nb=$(ls "$IB"/*_n_*.snap 2>/dev/null | head -n 1); no=$(ls "$IB"/*_o_*.snap 2>/dev/null | head -n 1)
check "inbox: snapshot sebelum nano (n) berisi isi awal" '[ -n "$nb" ] && [ "$(cat "$nb")" = "satu" ]'
check "inbox: snapshot sesudah nano (o) berisi hasil edit" '[ -n "$no" ] && grep -q "print(1)" "$no"'
check "inbox: .path berisi jalur berkas" '[ -n "$nb" ] && [ "$(cat "${nb%.snap}.path")" = "$T/sd/work/h.txt" ]'
check "inbox: urutan nama = n lalu o" '[ "$(ls "$IB" | grep "\.path$" | sed "s/^[0-9]*_\(.\)_.*/\1/" | tr -d "\n")" = "no" ]'
rm -rf "$IB"; hist h.txt >/dev/null
check "inbox: nano tanpa perubahan -> hanya snapshot sebelum" '[ "$(ls "$IB" | grep -c "_n_.*\.path$")" = "1" ] && [ "$(ls "$IB" | grep -c "_o_.*\.path$")" = "0" ]'
rm -rf "$IB"; MOLINA_NANO_HISTORY=off FAKE_EDIT=1 hist h.txt >/dev/null
check "inbox: MOLINA_NANO_HISTORY=off -> tidak ada inbox" '[ ! -e "$IB" ]'
rm -rf "$IB"; rm -f "$T/sd/work/baru.txt"; FAKE_EDIT=1 hist baru.txt >/dev/null
check "inbox: berkas baru dari nano -> hanya snapshot sesudah (o)" '[ "$(ls "$IB" | grep -c "_n_.*\.path$")" = "0" ] && [ "$(ls "$IB" | grep -c "_o_.*\.path$")" = "1" ]'
rm -rf "$IB"; printf 'x\n' > "$T/work/luar.txt"; (cd "$T/work" && HOME="$T/home" XDG_CONFIG_HOME="$T/home/.config" MOLINA_NANO_TEMPLATE=off MOLINA_HISTORY_INBOX="$IB" MOLINA_HISTORY_PREFIX="$T/sd" MOLINA_NANO_BIN="$T/tplnano" MOLINA_RUN_BIN="$T/mrun" MOLINA_NANORC="$T/rc" FAKE_EDIT=1 sh "$W" luar.txt >/dev/null 2>&1)
check "inbox: berkas di luar /sdcard tidak disalin" '[ ! -e "$IB" ]'
rm -rf "$IB"; mkdir -p "$IB"; i=0; while [ $i -lt 200 ]; do : > "$IB/pad$i"; i=$((i+1)); done; FAKE_EDIT=1 hist h.txt >/dev/null
check "inbox: penuh (200 berkas) -> tidak menambah" '[ "$(ls "$IB" | grep -c "\.snap$")" = "0" ]'
rm -rf "$IB"

# [NANO-RUNSNAP] snapshot folder sebelum skrip (Alt+R) untuk kartu "Perubahan skrip"
cat > "$T/runnano" << 'EOS'
#!/bin/sh
: > "$MOLINA_RUN_FLAG"
exit 0
EOS
cat > "$T/fakerun" << 'EOS'
#!/bin/sh
d=$(dirname "$1")
printf 'diubah\n' >> "$d/a.sh"; printf 'baru\n' > "$d/baru.sh"; rm -f "$d/hapus.sh"
exit 0
EOS
chmod +x "$T/runnano" "$T/fakerun"
rs() { (cd "$T/sd/work" && HOME="$T/home" XDG_CONFIG_HOME="$T/home/.config" MOLINA_NANO_TEMPLATE=off MOLINA_HISTORY_INBOX="$IB" MOLINA_HISTORY_PREFIX="$T/sd" MOLINA_NANO_BIN="$T/runnano" MOLINA_RUN_BIN="$T/fakerun" MOLINA_NANORC="$T/rc" sh "$W" "$@" 2>&1); }
rm -rf "$IB" "$T/sd/work"; mkdir -p "$T/sd/work/sub"
printf 'satu\n' > "$T/sd/work/a.sh"; printf 'hapus\n' > "$T/sd/work/hapus.sh"; printf 'dalam\n' > "$T/sd/work/sub/x.sh"
head -c 600000 /dev/zero > "$T/sd/work/besar.dat"; printf 'png' > "$T/sd/work/g.PNG"; printf 'x' > "$T/sd/work/m.pyc"; printf 'sembunyi\n' > "$T/sd/work/.env"
touch -d '2024-01-02 03:04:05' "$T/sd/work/a.sh"
rs a.sh >/dev/null
RD=$(ls -d "$IB"/*_r_*.runsnap 2>/dev/null | head -n 1)
check "runsnap: folder dibuat dengan penanda done" '[ -n "$RD" ] && [ -f "$RD/done" ] && [ ! -e "$RD/done.tmp" ]'
check "runsnap: root berisi jalur folder skrip" '[ "$(cat "$RD/root" 2>/dev/null)" = "$T/sd/work" ]'
check "runsnap: isi SEBELUM skrip yang disalin (bukan sesudah)" '[ "$(cat "$RD/files/a.sh")" = "satu" ] && [ -f "$RD/files/hapus.sh" ] && [ ! -e "$RD/files/baru.sh" ]'
check "runsnap: mtime asli dipertahankan (cp -p)" '[ "$(stat -c %Y "$RD/files/a.sh")" = "$(date -d "2024-01-02 03:04:05" +%s)" ]'
check "runsnap: dotfile ikut, subfolder dan .pyc tidak" '[ -f "$RD/files/.env" ] && [ ! -e "$RD/files/sub" ] && [ ! -e "$RD/files/x.sh" ] && [ ! -e "$RD/files/m.pyc" ]'
check "runsnap: besar/biner hanya ukuran (big.tsv) + placeholder mtime" '[ ! -e "$RD/files/besar.dat" ] && [ ! -e "$RD/files/g.PNG" ] && grep -q "^besar.dat	600000$" "$RD/big.tsv" && grep -q "^g.PNG	3$" "$RD/big.tsv" && [ -e "$RD/big/besar.dat" ]'
check "runsnap: skrip benar-benar jalan sesudah snapshot" '[ -f "$T/sd/work/baru.sh" ] && [ ! -e "$T/sd/work/hapus.sh" ]'
rm -rf "$IB" "$T/sd/work"; mkdir -p "$T/sd/work"; printf 'a\n' > "$T/sd/work/a.sh"; MOLINA_NANO_HISTORY=off rs a.sh >/dev/null
check "runsnap: MOLINA_NANO_HISTORY=off -> tidak ada runsnap" '[ ! -e "$IB" ]'
rm -rf "$IB"; mkdir -p "$T/luar2"; printf 'a\n' > "$T/luar2/a.sh"; (cd "$T/luar2" && HOME="$T/home" MOLINA_NANO_TEMPLATE=off MOLINA_HISTORY_INBOX="$IB" MOLINA_HISTORY_PREFIX="$T/sd" MOLINA_NANO_BIN="$T/runnano" MOLINA_RUN_BIN="$T/fakerun" MOLINA_NANORC="$T/rc" sh "$W" a.sh >/dev/null 2>&1)
check "runsnap: folder di luar /sdcard tidak disalin" '[ ! -e "$IB" ]'
rm -rf "$IB" "$T/sd/work"; mkdir -p "$T/sd/work"; i=0; while [ $i -lt 201 ]; do : > "$T/sd/work/f$i.txt"; i=$((i+1)); done; printf 'a\n' > "$T/sd/work/a.sh"; rs a.sh >/dev/null
check "runsnap: folder > 200 berkas -> dilewati" '[ -z "$(ls -d "$IB"/*.runsnap 2>/dev/null)" ]'
rm -rf "$IB" "$T/sd/work"; mkdir -p "$T/sd/work" "$IB"; printf 'a\n' > "$T/sd/work/a.sh"; i=0; while [ $i -lt 200 ]; do : > "$IB/pad$i"; i=$((i+1)); done; rs a.sh >/dev/null
check "runsnap: inbox penuh (200) -> tidak menambah" '[ -z "$(ls -d "$IB"/*.runsnap 2>/dev/null)" ]'
rm -rf "$IB" "$T/sd/work"

echo "Selesai: $runs kasus, $fails gagal"; [ "$fails" -eq 0 ]

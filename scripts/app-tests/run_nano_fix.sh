#!/usr/bin/env bash
# [RUN-CHECK] Uji pemeriksa skrip di molina-run: pembungkus heredoc, isi shell di berkas bukan-shell, tempelan terpotong,
# CRLF/BOM, tebak bahasa dari isi, dan tiap pilihan dialog (1 2 a 3 e x). Dialog dijawab lewat MOLINA_RUN_ANSWER.
set -uo pipefail
cd "$(dirname "$0")/../.."
R="$PWD/app/src/main/assets/molina/molina-run.sh"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
runs=0; fails=0
check() { runs=$((runs+1)); if ! eval "$2"; then fails=$((fails+1)); echo "GAGAL: $1"; fi; }
mkdir -p "$T/home" "$T/cfg"
printf '#!/bin/sh\necho "NANO:$*"\n' > "$T/fakeedit"; chmod +x "$T/fakeedit"
# mx VAR=nilai ... sh "$R" berkas : jalankan di folder uji dengan lingkungan terisolasi
mx() { (cd "$T" && env HOME="$T/home" XDG_CONFIG_HOME="$T/cfg" MOLINA_NANO_WRAPPER="$T/fakeedit" "$@" 2>&1); }
have_py=0; command -v python3 >/dev/null 2>&1 && have_py=1

# ---- hasil pemeriksaan (--check)
printf "python3 - << 'PYEOF'\nprint('hai')\nPYEOF\n" > "$T/w.py"
out="$(mx sh "$R" --check w.py)"
check "check: .py + pembungkus python3 -> unwrap" '[[ "$out" == *"lang=py"*"wrap=interp"*"unwrap=1"* ]]'
printf "python3 - << 'PYEOF'\nprint('hai')\nPYEOF\n" > "$T/v.sh"
out="$(mx sh "$R" --check v.sh)"
check "check: .sh + python3 << EOF valid -> tanpa masalah" '[[ "$out" == *"issues=0"* ]]'
printf "print('hai')\n" > "$T/ok.py"
out="$(mx sh "$R" --check ok.py)"
check "check: .py biasa -> tanpa masalah" '[[ "$out" == *"issues=0"* ]]'
printf "python3 << 'EOF'\nprint(1)\n" > "$T/t.py"
out="$(mx sh "$R" --check t.py)"
check "check: penutup hilang -> trunc=1" '[[ "$out" == *"trunc=1"* ]]'

if [ $have_py -eq 1 ]; then
  # ---- 1: perbaiki sementara
  cp "$T/w.py" "$T/w1.py"
  out="$(mx MOLINA_RUN_ANSWER=1 sh "$R" w1.py)"; rc=$?
  check "[1] pembungkus dibuang, skrip jalan" '[ $rc -eq 0 ] && [[ "$out" == *"hai"* && "$out" == *"selesai"* ]]'
  check "[1] berkas asli tidak diubah" 'cmp -s "$T/w.py" "$T/w1.py"'
  # ---- 2: perbaiki berkas + cadangan
  cp "$T/w.py" "$T/w2.py"
  out="$(mx MOLINA_RUN_ANSWER=2 sh "$R" w2.py)"; rc=$?
  check "[2] skrip jalan" '[ $rc -eq 0 ] && [[ "$out" == *"hai"* ]]'
  check "[2] berkas berisi isi saja" '[ "$(cat "$T/w2.py")" = "print('"'"'hai'"'"')" ]'
  check "[2] cadangan .bak = isi asli" 'cmp -s "$T/w.py" "$T/w2.py.bak"'
  # ---- 3: apa adanya -> Python gagal
  cp "$T/w.py" "$T/w3.py"
  out="$(mx MOLINA_RUN_ANSWER=3 sh "$R" w3.py)"; rc=$?
  check "[3] apa adanya: gagal (pembungkus tak valid di Python)" '[ $rc -ne 0 ] && [[ "$out" == *"kode"* ]]'
  # ---- x: batal
  out="$(mx MOLINA_RUN_ANSWER=x sh "$R" w1.py)"; rc=$?
  check "[x] batal: exit 125, tidak jalan" '[ $rc -eq 125 ] && [[ "$out" != *"hai"* ]]'
  # ---- tanpa tty dan tanpa jawaban: apa adanya + peringatan
  out="$(mx sh "$R" w1.py)"; rc=$?
  check "tanpa tty: peringatan + apa adanya (tidak menebak diam-diam)" '[ $rc -ne 0 ] && [[ "$out" == *"peringatan"* ]]'
  # ---- MOLINA_RUN_ASK=fix
  out="$(mx MOLINA_RUN_ASK=fix sh "$R" w1.py)"; rc=$?
  check "ASK=fix: diperbaiki sementara tanpa tanya" '[ $rc -eq 0 ] && [[ "$out" == *"hai"* ]]'
  # ---- a: jangan tanya lagi
  out="$(mx MOLINA_RUN_ANSWER=a sh "$R" w1.py)"
  check "[a] jalan + simpan pilihan" '[[ "$out" == *"hai"* ]] && [ "$(cat "$T/cfg/molina/run-ask")" = "fix" ]'
  out="$(mx sh "$R" w1.py)"; rc=$?
  check "setelah [a]: berikutnya otomatis diperbaiki" '[ $rc -eq 0 ] && [[ "$out" == *"hai"* ]]'
  rm -rf "$T/cfg"
  # ---- tanpa ekstensi + pembungkus -> shell, tanpa dialog
  printf "python3 << 'EOF'\nprint('noext')\nEOF\n" > "$T/noext"
  out="$(mx sh "$R" noext)"; rc=$?
  check "tanpa ekstensi + pembungkus: dijalankan dengan shell" '[ $rc -eq 0 ] && [[ "$out" == *"noext"* ]]'
  # ---- .sh berisi python3 << EOF (valid) tanpa dialog
  out="$(mx sh "$R" v.sh)"; rc=$?
  check ".sh berisi python3 << EOF: jalan tanpa dialog" '[ $rc -eq 0 ] && [[ "$out" == *"hai"* && "$out" != *"periksa skrip"* && "$out" != *"peringatan"* ]]'
  # ---- tebak Python dari isi
  printf 'import sys\nprint("tebak")\n' > "$T/g.txt"
  out="$(mx sh "$R" g.txt)"; rc=$?
  check "tebak python dari isi (.txt)" '[ $rc -eq 0 ] && [[ "$out" == *"tebak"* ]]'
fi

# ---- isi shell di berkas bukan-shell (.py berisi cat << EOF)
printf "cat << 'EOF'\nhalo-shell\nEOF\n" > "$T/s.py"
out="$(mx sh "$R" --check s.py)"
check "check: .py berisi perintah shell -> interp=shell" '[[ "$out" == *"interp=bash"* || "$out" == *"interp=sh"* ]]'
out="$(mx MOLINA_RUN_ANSWER=1 sh "$R" s.py)"; rc=$?
check "[1] isi shell di .py dijalankan dengan shell" '[ $rc -eq 0 ] && [[ "$out" == *"halo-shell"* ]]'

# ---- terpotong
printf "python3 << 'EOF'\nprint('x')\n" > "$T/tr.sh"
out="$(mx sh "$R" tr.sh)"; rc=$?
check "terpotong tanpa tty: batal (exit 125)" '[ $rc -eq 125 ] && [[ "$out" == *"terpotong"* ]]'
out="$(mx MOLINA_RUN_ANSWER=e sh "$R" tr.sh)"; rc=$?
check "[e] membuka nano di baris terakhir" '[[ "$out" == *"NANO:+2 "*"tr.sh"* ]]'
out="$(mx MOLINA_RUN_ANSWER=e sh "$R" w.py)"
check "[e] pembungkus: nano di baris pembungkus" '[[ "$out" == *"NANO:+1 "*"w.py"* ]]'

# ---- CRLF dan BOM pada skrip shell
printf 'echo bersih\r\n' > "$T/c.sh"
out="$(mx MOLINA_RUN_ANSWER=1 sh "$R" c.sh)"; rc=$?
check "[1] CRLF dibuang, keluaran tanpa ^M" '[ $rc -eq 0 ] && [[ "$out" == *"bersih"* && "$out" != *$'"'"'\r'"'"'* ]]'
printf '\357\273\277#!/bin/sh\necho bom-ok\n' > "$T/b.sh"
out="$(mx sh "$R" --check b.sh)"
check "check: BOM + shebang terdeteksi" '[[ "$out" == *"clean=1"* ]]'
out="$(mx MOLINA_RUN_ANSWER=1 sh "$R" b.sh)"; rc=$?
check "[1] BOM dibuang, skrip jalan" '[ $rc -eq 0 ] && [[ "$out" == *"bom-ok"* ]]'

# [RUN-HINT]
# ---- [RUN-HINT] shebang vs ekstensi, tips shebang/izin eksekusi, dan --fix
printf '#!/bin/sh\nprint(1)\n' > "$T/m.py"
out="$(mx sh "$R" --check m.py)"
check "check: shebang sh di berkas .py -> mismatch + issues=1" '[[ "$out" == *"sheb=mismatch"* && "$out" == *"issues=1"* ]]'
printf '#!/usr/bin/env python3\nprint(1)\n' > "$T/g.py"
out="$(mx sh "$R" --check g.py)"
check "check: shebang python3 di .py -> cocok, tanpa masalah" '[[ "$out" == *"sheb=ok"* && "$out" == *"issues=0"* ]]'
printf "print('hai')\n" > "$T/n.py"
out="$(mx sh "$R" --check n.py)"
check "check: .py tanpa shebang -> tips (bukan masalah)" '[[ "$out" == *"sheb=none"* && "$out" == *"issues=0"* && "$out" == *"hints=1"* ]]'
printf '#!/bin/sh\necho x\n' > "$T/p.sh"; chmod -x "$T/p.sh"
out="$(mx sh "$R" --check p.sh)"
check "check: shebang tanpa +x -> exec=0 + tips" '[[ "$out" == *"exec=0"* && "$out" == *"hints=1"* ]]'
chmod +x "$T/p.sh"
out="$(mx sh "$R" --check p.sh)"
check "check: shebang + x -> tanpa tips" '[[ "$out" == *"exec=1"* && "$out" == *"hints=0"* ]]'
printf 'echo tanpa-ekstensi\ncd /tmp\n' > "$T/noext"
out="$(mx sh "$R" --check noext)"
check "check: tanpa ekstensi, isi shell -> tips shebang" '[[ "$out" == *"hints=1"* ]]'
# tips hanya di terminal atau MOLINA_RUN_NOTES=on; off mematikannya
out="$(mx MOLINA_RUN_NOTES=on sh "$R" n.py)"
check "tips tampil dengan MOLINA_RUN_NOTES=on" '[[ "$out" == *"belum ada shebang"* ]]'
out="$(mx MOLINA_RUN_NOTES=off sh "$R" n.py)"
check "tips mati dengan MOLINA_RUN_NOTES=off" '[[ "$out" != *"belum ada shebang"* ]]'
out="$(mx sh "$R" n.py)"
check "tanpa terminal: tidak ada tips (keluaran lama tidak berubah)" '[[ "$out" != *"belum ada shebang"* ]]'
# --fix
cp "$T/n.py" "$T/f1.py"; chmod -x "$T/f1.py"
out="$(mx sh "$R" --fix f1.py)"; rc=$?
check "--fix: shebang ditambah di baris 1" '[ $rc -eq 0 ] && [ "$(head -n 1 "$T/f1.py")" = "#!/usr/bin/env python3" ]'
check "--fix: cadangan .bak = isi lama" 'cmp -s "$T/n.py" "$T/f1.py.bak"'
check "--fix: chmod +x berlaku di folder biasa" '[ -x "$T/f1.py" ]'
out="$(mx sh "$R" --fix f1.py)"
check "--fix: kedua kali tidak mengubah apa-apa" '[[ "$out" == *"Tidak ada yang perlu diperbaiki"* ]]'
printf 'echo a\r\necho b\r\n' > "$T/f2.sh"
out="$(mx sh "$R" --fix f2.sh)"
check "--fix: .sh CRLF -> shebang ditambah dan CR dibuang" '[ "$(head -n 1 "$T/f2.sh")" = "#!/bin/sh" ] && ! grep -q "$(printf "\r")" "$T/f2.sh"'
printf 'hal biasa\n' > "$T/f3.txt"
out="$(mx sh "$R" --fix f3.txt)"; rc=$?
check "--fix: bahasa tak dikenal -> exit 2 dan berkas utuh" '[ $rc -eq 2 ] && [ "$(cat "$T/f3.txt")" = "hal biasa" ]'
printf "python3 << 'EOF'\nprint(1)\nEOF\n" > "$T/f4.py"
out="$(mx sh "$R" --fix f4.py)"; rc=$?
check "--fix: ada pembungkus heredoc -> ditolak, arahkan ke [2]" '[ $rc -eq 2 ] && [[ "$out" == *"[2]"* ]]'
if [ $have_py -eq 1 ]; then
  printf '#!/usr/bin/python\nprint("lama-ok")\n' > "$T/old.py"
  out="$(mx sh "$R" old.py)"; rc=$?
  if ! command -v /usr/bin/python >/dev/null 2>&1 && ! command -v python >/dev/null 2>&1; then
    check "shebang /usr/bin/python tak ada -> pakai python3" '[ $rc -eq 0 ] && [[ "$out" == *"lama-ok"* && "$out" == *"dipakai python3"* ]]'
  fi
fi

echo "Selesai: $runs kasus, $fails gagal"; [ "$fails" -eq 0 ]

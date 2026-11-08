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

echo "Selesai: $runs kasus, $fails gagal"; [ "$fails" -eq 0 ]

#!/usr/bin/env bash
# Uji profil Starter tanpa kotlinc: ekstrak SCRIPT dari StarterProfile.kt, cek sintaks, lalu jalankan dengan apt-get/df tiruan.
# Jalankan: bash scripts/app-tests/run_starter_profile.sh
set -u
cd "$(dirname "$0")/../.."
KT=app/src/main/java/com/molina/studio/packages/StarterProfile.kt
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
python3 - "$KT" "$W/script.sh" "$W/pkgs.txt" << 'PY'
import re, sys, textwrap
s = open(sys.argv[1]).read()
body = re.search(r'SCRIPT: String = """\n(.*?)"""\.trimIndent\(\)', s, re.S).group(1)
open(sys.argv[2], "w").write(textwrap.dedent(body))
p = re.search(r'PACKAGES = listOf\((.*?)\)', s).group(1)
open(sys.argv[3], "w").write(" ".join(re.findall(r'"([^"]+)"', p)))
PY
mkdir "$W/bin"
cat > "$W/bin/apt-get" << 'EOS'
#!/bin/sh
echo "$*" >> "$APT_LOG"
if [ "$1" = update ] && [ "${FAIL_UPDATE:-0}" = 1 ]; then exit 100; fi
exit 0
EOS
cat > "$W/bin/df" << 'EOS'
#!/bin/sh
[ "${DF_KB:-}" = none ] && exit 1
echo "Filesystem 1024-blocks Used Available Capacity Mounted on"
echo "/dev/x 9999999 1 ${DF_KB:-5000000} 3% /"
EOS
chmod +x "$W/bin/apt-get" "$W/bin/df"

fail=0; n=0
ok() { n=$((n+1)); if ( eval "$2" ); then echo "  ok   $1"; else echo "  GAGAL $1"; fail=$((fail+1)); fi; }
run() { : > "$W/apt.log"; APT_LOG="$W/apt.log" PATH="$W/bin:$PATH" "$@" sh "$W/script.sh" >"$W/out.txt" 2>&1; echo $? > "$W/code"; }

ok "sintaks sh valid" 'sh -n "$W/script.sh"'
ok "daftar PACKAGES sama dengan SCRIPT" 'for p in $(cat "$W/pkgs.txt"); do grep -qw -- "$p" "$W/script.sh" || exit 1; done; exit 0'
ok "ca-certificates ada di PACKAGES dan di baris install" 'grep -qw ca-certificates "$W/pkgs.txt" && grep "apt-get install" "$W/script.sh" | grep -qw ca-certificates'
ok "tanpa paket tak terdaftar di baris install" '[ "$(grep "apt-get install" "$W/script.sh" | sed "s/.*root //" | wc -w)" = "$(wc -w < "$W/pkgs.txt")" ]'

# [STARTER-MUTAGEN]
ok "python3-mutagen ada di PACKAGES dan di baris install" 'grep -qw python3-mutagen "$W/pkgs.txt" && grep "apt-get install" "$W/script.sh" | grep -qw python3-mutagen'

# [STARTER-YTDLP]
ok "vim dan ffmpeg tidak ikut Starter" '! grep -qwE "vim|ffmpeg" "$W/pkgs.txt" && ! grep -qwE "vim|ffmpeg" "$W/script.sh"'
ok "yt-dlp dirangkai setelah apt (bukan lewat apt)" 'grep -q "installIfMissing" app/src/main/java/com/molina/studio/settings/DebianAptUi.kt && ! grep -qw yt-dlp "$W/pkgs.txt"'

run env DF_KB=5000000
ok "ruang cukup: exit 0" '[ "$(cat "$W/code")" = 0 ]'
ok "ruang cukup: update lalu install dipanggil berurutan" '[ "$(sed -n 1p "$W/apt.log" | cut -d" " -f1)" = update ] && [ "$(sed -n 2p "$W/apt.log" | cut -d" " -f1)" = install ]'
ok "install memakai --no-install-recommends dan -y" 'grep -q -- "--no-install-recommends" "$W/apt.log" && grep -q -- " -y " "$W/apt.log"'

run env DF_KB=1000
ok "ruang kurang: exit 4" '[ "$(cat "$W/code")" = 4 ]'
ok "ruang kurang: apt-get tidak dipanggil" '[ ! -s "$W/apt.log" ]'
ok "ruang kurang: pesan tampil" 'grep -q "kurang dari 1 GB" "$W/out.txt"'

run env DF_KB=none
ok "df gagal: lanjut dan exit 0" '[ "$(cat "$W/code")" = 0 ] && grep -q "^install" "$W/apt.log"'

run env DF_KB=1048576
ok "tepat 1 GB: lanjut" '[ "$(cat "$W/code")" = 0 ]'

run env DF_KB=5000000 FAIL_UPDATE=1
ok "update gagal: install tidak dipanggil" '! grep -q "^install" "$W/apt.log"'
ok "update gagal: exit tidak nol" '[ "$(cat "$W/code")" != 0 ]'

echo "Selesai: $n kasus, $fail gagal"
[ "$fail" = 0 ]

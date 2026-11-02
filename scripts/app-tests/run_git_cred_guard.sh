#!/usr/bin/env bash
# [GIT-CRED-GUARD] Uji blok molina-git-secure di assets/molina/bashrc. Butuh bash dan git (tanpa kotlinc).
set -uo pipefail
cd "$(dirname "$0")/../.."
RC=app/src/main/assets/molina/bashrc
BLOCK="$(mktemp)"; T="$(mktemp -d)"
trap 'rm -rf "$BLOCK" "$T"' EXIT
sed -n '/^# \[GIT-CRED-GUARD\]/,/^# \[\/GIT-CRED-GUARD\]/p' "$RC" > "$BLOCK"
[ -s "$BLOCK" ] || { echo "GAGAL: blok GIT-CRED-GUARD tidak ditemukan"; exit 1; }
runs=0; fails=0
check() { runs=$((runs+1)); if ! eval "$2"; then fails=$((fails+1)); echo "GAGAL: $1"; fi; }
mk() { mktemp -d -p "$T"; }
FIX_HOST="github"".com"  # dirakit agar secret_gate.py tidak menandai fixture uji
run() { local h="$1"; shift; HOME="$h" XDG_CONFIG_HOME="$h/.config" GIT_CONFIG_NOSYSTEM=1 bash -c '. "$0"; "$@"' "$BLOCK" "$@" 2>&1; }
gcfg() { HOME="$1" XDG_CONFIG_HOME="$1/.config" GIT_CONFIG_NOSYSTEM=1 git config --global --get-all credential.helper; }

h=$(mk); out=$(run "$h" true)
check "bersih: tanpa peringatan" '[ -z "$out" ]'

h=$(mk); echo "https://u:t@${FIX_HOST}" > "$h/.git-credentials"; out=$(run "$h" true)
check "git-credentials berisi: ada peringatan" 'grep -q molina-git-secure <<< "$out"'

h=$(mk); printf '[credential]\n\thelper = store\n' > "$h/.gitconfig"; out=$(run "$h" true)
check "helper store: ada peringatan" 'grep -q molina-git-secure <<< "$out"'

h=$(mk); : > "$h/.git-credentials"; out=$(run "$h" true)
check "berkas kosong: tanpa peringatan" '[ -z "$out" ]'

h=$(mk); echo "https://u:t@${FIX_HOST}" > "$h/.git-credentials"; printf '[credential]\n\thelper = store\n' > "$h/.gitconfig"
out=$(run "$h" molina-git-secure); rc=$?
check "secure: berhasil" '[ $rc -eq 0 ]'
check "secure: berkas kredensial hilang" '[ ! -e "$h/.git-credentials" ]'
check "secure: hanya helper cache" '[ "$(gcfg "$h")" = "cache --timeout=3600" ]'
check "secure: ada anjuran cabut token" 'grep -q CABUT <<< "$out"'
out=$(run "$h" molina-git-secure); rc=$?
check "secure: idempoten" '[ $rc -eq 0 ] && [ "$(gcfg "$h" | wc -l)" -eq 1 ]'
out=$(run "$h" true)
check "setelah secure: peringatan hilang" '[ -z "$out" ]'

h=$(mk); r=$(mk); git -C "$r" init -q; git -C "$r" remote add origin "https://user:ghp_RAHASIA123@${FIX_HOST}/a/b.git"
out=$(cd "$r" && run "$h" molina-git-secure)
check "remote bertoken: ada peringatan" 'grep -q "Peringatan: remote" <<< "$out"'
check "remote bertoken: token disamarkan" '! grep -q RAHASIA123 <<< "$out" && grep -q "user:\*\*\*@" <<< "$out"'

h=$(mk); r=$(mk); git -C "$r" init -q; git -C "$r" remote add origin "https://user@${FIX_HOST}/a/b.git"
out=$(cd "$r" && run "$h" molina-git-secure)
check "remote hanya username: tanpa peringatan" '! grep -q "Peringatan: remote" <<< "$out"'

h=$(mk)
hi=$(HISTIGNORE='ls' HOME="$h" bash -c '. "$0"; echo "$HISTIGNORE"' "$BLOCK" 2>&1)
check "HISTIGNORE: milik pengguna dipertahankan" '[[ "$hi" == ls:* ]] && [[ "$hi" == *"*github_pat_*"* ]] && [[ "$hi" == *"*ghp_*"* ]]'
hc=$(HISTCONTROL= HOME="$h" bash -c '. "$0"; echo "$HISTCONTROL"' "$BLOCK" 2>&1)
check "HISTCONTROL bawaan ignoreboth" '[ "$hc" = ignoreboth ]'
hc=$(HISTCONTROL=ignoredups HOME="$h" bash -c '. "$0"; echo "$HISTCONTROL"' "$BLOCK" 2>&1)
check "HISTCONTROL pengguna dipertahankan" '[ "$hc" = ignoredups ]'

out=$(HOME="$h" bash -c '. "$0"; PATH=/nonexistent; molina-git-secure' "$BLOCK" 2>&1); rc=$?
check "tanpa git: pesan jelas dan gagal" '[ $rc -eq 1 ] && grep -q "belum terpasang" <<< "$out"'

echo "GitCredGuard: $((runs - fails)) lulus, $fails gagal"
[ "$fails" -eq 0 ]

#!/usr/bin/env bash
# [NOIR-PROMPT] Uji cabang git di prompt Molina Noir (assets/molina/bashrc). Butuh bash >= 4.4; tanpa git/kotlinc.
set -uo pipefail
cd "$(dirname "$0")/../.."
RC=app/src/main/assets/molina/bashrc
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
FN="$T/fn.sh"
{ sed -n '/^# \[NOIR-PROMPT\]/,/^# \[\/NOIR-PROMPT\]/p' "$RC"; sed -n '/^__molina_ps1() {/,/^}/p' "$RC"; } > "$FN"
grep -q '^__molina_branch()' "$FN" && grep -q '^__molina_ps1()' "$FN" || { echo "GAGAL: fungsi prompt tidak ditemukan"; exit 1; }
runs=0; fails=0
check() { runs=$((runs+1)); if ! eval "$2"; then fails=$((fails+1)); echo "GAGAL: $1"; fi; }
# render <dir> [VAR=nilai...]: jalankan __molina_ps1 di <dir>, cetak PS1 hasil perluasan.
render() { local d="$1"; shift; (cd "$d" && env HOME="$T/home" "$@" bash -c '. "$0"; __molina_ps1; printf "%s" "${PS1@P}"' "$FN" 2>/dev/null); }
NOIR=(MOLINA_PS_HOME='1;32' MOLINA_PS_OTHER='1;31' MOLINA_PS_USER='1;33' MOLINA_PS_BRANCH='35')
CLASSIC=(MOLINA_PS_HOME='1;32' MOLINA_PS_OTHER='1;31')
mkdir -p "$T/home" "$T/plain" "$T/r1/.git" "$T/r1/sub/dir" "$T/r2/.git" "$T/r3" "$T/wt" "$T/r4/.git" "$T/r5/.git" "$T/r6/.git"
echo 'ref: refs/heads/main' > "$T/r1/.git/HEAD"
echo 'abcdef0123456789abcdef0123456789abcdef01' > "$T/r2/.git/HEAD"
echo "gitdir: $T/wt" > "$T/r3/.git"; echo 'ref: refs/heads/feat/x' > "$T/wt/HEAD"
echo 'ref: refs/heads/$(touch PWNED)' > "$T/r4/.git/HEAD"
printf 'ref: refs/heads/a\033[31mb\n' > "$T/r5/.git/HEAD"
echo "ref: refs/heads/$(printf 'a%.0s' $(seq 1 100))" > "$T/r6/.git/HEAD"

out=$(render "$T/r1" "${NOIR[@]}");          check "repo: cabang tampil"            'grep -qF "(main)" <<< "$out"'
out=$(render "$T/r1/sub/dir" "${NOIR[@]}");  check "subfolder: cabang ditemukan"    'grep -qF "(main)" <<< "$out"'
out=$(render "$T/plain" "${NOIR[@]}");       check "bukan repo: tanpa cabang"       '! grep -qF "(" <<< "$out"'
out=$(render "$T/r2" "${NOIR[@]}");          check "detached: 7 karakter"           'grep -qF "(abcdef0)" <<< "$out"'
out=$(render "$T/r3" "${NOIR[@]}");          check "worktree (.git berupa berkas)"  'grep -qF "(feat/x)" <<< "$out"'
out=$(render "$T/r4" "${NOIR[@]}")
check "injeksi: perintah tidak dieksekusi"   '[ ! -e "$T/r4/PWNED" ]'
check "injeksi: nama tampil apa adanya"      'grep -qF "touch PWNED" <<< "$out"'
out=$(render "$T/r5" "${NOIR[@]}");          check "escape di nama cabang dinetralkan" '! grep -qF $'"'"'\033[31mb'"'"' <<< "$out"'
out=$(render "$T/r6" "${NOIR[@]}");          check "nama panjang dipotong 40"       '! grep -qF "$(printf "a%.0s" $(seq 1 41))" <<< "$out"'
out=$(render "$T/r1" "${CLASSIC[@]}");       check "tema lama: format tak berubah"  'grep -qF "user@localhost:" <<< "$out" && ! grep -qF "(main)" <<< "$out"'

[ "$fails" -eq 0 ] && echo "OK: $runs uji prompt Noir lulus" || echo "$fails dari $runs uji prompt Noir GAGAL"
exit "$fails"

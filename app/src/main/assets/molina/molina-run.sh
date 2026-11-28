#!/bin/sh
# Molina Terminal - molina-run <berkas> [argumen...] (dibuat otomatis, ditimpa tiap sesi). [RUN-CHECK]
# Jalankan skrip dari BERKAS, bukan ditempel ke terminal, jadi skrip panjang tidak terpotong.
# Penerjemah: shebang baris pertama; bila tak ada, dari ekstensi (py sh bash js pl rb lua php awk), lalu dari isinya.
# Sebelum jalan, skrip diperiksa: pembungkus heredoc yang tak valid di dalam berkas (python3 << EOF ... EOF),
# isi shell di berkas bukan-shell, tempelan terpotong (penutup EOF hilang), CRLF, dan BOM. Bila ada, tampil dialog.
#   molina-run --check <berkas>   hanya cetak hasil pemeriksaan (untuk tes dan alat lain)
#   molina-run --fix <berkas>     [RUN-HINT] tambah shebang yang hilang (cadangan .bak) dan chmod +x bila berlaku
#   molina-run --init <berkas>    [NANO-DETECT] berkas belum ada/kosong + ekstensi dikenal: tulis baris shebang (keluar 0 = dibuat, 3 = dilewati)
#   molina-run --scan <folder>    [SCRIPT-SCAN] satu baris per skrip yang punya saran: nama<TAB>fix|warn<TAB>pesan (fix = shebang belum ada, bisa --fix)
#   molina-run --hint <berkas>    [NANO-DETECT] saran singkat sesudah nano ditutup (keluar 10 = shebang belum ada, bisa --fix); diam bila tips dimatikan
# [RUN-HINT] Selain pemeriksaan di atas, ada "tips" satu-dua baris (tidak menghentikan skrip): tanpa shebang, belum +x,
# dan penyimpanan bersama Android (chmod +x tidak berlaku di sana). Shebang yang tak cocok dengan ekstensi masuk dialog.
# Matikan tips: MOLINA_RUN_NOTES=off atau isi ~/.config/molina/run-notes dengan 'off' (on = tampil juga bukan-terminal).
# Atur lewat MOLINA_RUN_ASK = ask (bawaan) | fix (perbaiki sementara tanpa tanya) | no (jalankan apa adanya).
# MOLINA_RUN_ANSWER = 1|2|3|a|e|x menjawab dialog tanpa interaksi (otomasi dan tes).
# Direktori kerja tidak diubah (sama seperti `sh berkas`). Dipakai nano lewat Alt+R.
# [SCRIPT-SCAN] --scan <folder>: dipakai halaman Script Execution (satu proses untuk seluruh folder). Memakai logika --hint yang sama,
# jadi ikut sakelar tips (mati = tidak ada baris). Maksimal 200 berkas; nama berisi tab/baris baru dilewati; berkas titik-awal tak dipindai.
if [ "$1" = "--scan" ]; then
  sd="${2%/}"
  [ -d "$sd" ] || exit 2
  stab=$(printf '\t'); scnt=0
  for sp in "$sd"/*; do
    [ -f "$sp" ] || continue
    case "${sp##*/}" in *"$stab"*|*"
"*) continue ;; esac
    scnt=$((scnt+1)); [ "$scnt" -le 200 ] || break
    sout=$(sh "$0" --hint "$sp" 2>/dev/null); src_rc=$?
    [ -n "$sout" ] || continue
    smsg=$(printf '%s\n' "$sout" | sed -n '2p' | sed 's/^[[:space:]]*[^[:space:]]*[[:space:]]*//')
    if [ "$src_rc" -eq 10 ]; then sk=fix; else sk=warn; fi
    printf '%s\t%s\t%s\n' "${sp##*/}" "$sk" "$smsg"
  done
  exit 0
fi
check_only=0; fix_only=0; init_only=0; hint_only=0 # [RUN-HINT] [NANO-DETECT]
if [ "$1" = "--check" ]; then check_only=1; shift
elif [ "$1" = "--fix" ]; then fix_only=1; shift
elif [ "$1" = "--init" ]; then init_only=1; shift
elif [ "$1" = "--hint" ]; then hint_only=1; shift; fi
f="$1"
if [ -z "$f" ]; then
  echo "Pemakaian: molina-run [--check|--fix|--init|--hint] <berkas> [argumen...]" >&2
  exit 2
fi
shift
if [ ! -f "$f" ] && [ "$init_only" -eq 0 ]; then
  echo "molina-run: berkas tidak ditemukan: $f" >&2
  exit 2
fi
case "$f" in /*) ;; *) f="$PWD/$f" ;; esac
src="$f"
exe="$f"
name="${src##*/}"
cr=$(printf '\r')
nl='
'

lang_of() {
  case "${1##*/}" in
    python|python2|python3*|*.py) echo py ;;
    sh|bash|dash|zsh|ksh|ash|*.sh|*.bash) echo sh ;;
    node|nodejs|*.js|*.mjs|*.cjs) echo js ;;
    perl|*.pl) echo pl ;;
    ruby|*.rb) echo rb ;;
    lua|lua5*|*.lua) echo lua ;;
    php|*.php) echo php ;;
    awk|gawk|mawk|*.awk) echo awk ;;
    *) echo "" ;;
  esac
}
sheb_name() {
  set -f
  set -- $1
  if [ "${1##*/}" = "env" ]; then
    shift
    while :; do
      case "$1" in -*|*=*) shift ;; *) break ;; esac
    done
  fi
  echo "${1##*/}"
}
lang_label() {
  case "$1" in py) echo Python ;; sh) echo shell ;; js) echo JavaScript ;; pl) echo Perl ;; rb) echo Ruby ;; lua) echo Lua ;; php) echo PHP ;; awk) echo awk ;; *) echo "$1" ;; esac
}
shell_bin() { if command -v bash >/dev/null 2>&1; then echo bash; else echo sh; fi; }
# [RUN-HINT] tebak bahasa dari isi (hanya untuk saran/perbaikan shebang); kosong bila tidak yakin
guess_lang() {
  if grep -m1 -Eq '^[[:space:]]*(import [A-Za-z_]|from [A-Za-z_.]+ import |def [A-Za-z_]+\(|class [A-Za-z_]+|print\()' "$1" 2>/dev/null; then echo py
  elif grep -m1 -Eq '^[[:space:]]*(console\.log\(|const [A-Za-z_]+ *=|require\()' "$1" 2>/dev/null; then echo js
  elif grep -m1 -Eq '^[[:space:]]*(echo |cd |export |set -[eu]|if \[|for [A-Za-z_]+ in |mkdir |apt(-get)? |pkg |chmod |source )' "$1" 2>/dev/null; then echo sh
  fi
}
sheb_for() { # bahasa -> baris shebang yang disarankan
  case "$1" in
    py) echo '#!/usr/bin/env python3' ;;
    sh) case "$src" in *.bash) echo '#!/usr/bin/env bash' ;; *) echo '#!/bin/sh' ;; esac ;;
    js) echo '#!/usr/bin/env node' ;;
    pl) echo '#!/usr/bin/env perl' ;;
    rb) echo '#!/usr/bin/env ruby' ;;
    lua) echo '#!/usr/bin/env lua' ;;
    php) echo '#!/usr/bin/env php' ;;
    *) echo "" ;;
  esac
}
# Penyimpanan bersama Android (/sdcard dll.) tidak menyimpan bit eksekusi; skrip di sana dijalankan lewat penerjemah.
shared_storage() { case "$1" in /sdcard|/sdcard/*|/storage/emulated/*|/storage/*-*/*|/mnt/sdcard*|/mnt/shared*) return 0 ;; esac; return 1; }

# [NANO-DETECT] --init: berkas belum ada atau kosong, ekstensi dikenal -> isi baris shebang + satu baris kosong.
if [ "$init_only" -eq 1 ]; then
  if [ -d "$src" ] || [ -s "$src" ]; then exit 3; fi
  il=$(lang_of "$src"); isl=$(sheb_for "$il")
  [ -n "$isl" ] || exit 3
  [ -d "${src%/*}" ] || exit 3
  printf '%s\n\n' "$isl" > "$src" 2>/dev/null || exit 1
  exit 0
fi

# ---- pemeriksaan
bom=0
if [ "$(head -c 3 "$src" 2>/dev/null | od -An -tx1 | tr -d ' \n')" = "efbbbf" ]; then bom=1; fi
crlf=0
if grep -q "$cr" "$src" 2>/dev/null; then crlf=1; fi
first=$(head -n 1 "$src" 2>/dev/null | tr -d '\r' | LC_ALL=C sed '1s/^\xef\xbb\xbf//')
case "$first" in
  '#!'*) srclang=$(lang_of "$(sheb_name "${first#??}")") ;;
  *) srclang=$(lang_of "$src") ;;
esac
wrap=none; wint=""; delim=""; wfirst=0; wclose=0; wlast=0
an=$(LC_ALL=C awk '
function trim(s) { sub(/^[ \t]+/, "", s); sub(/[ \t]+$/, "", s); return s }
{ l = $0; sub(/\r$/, "", l); a[NR] = l }
END {
  fi = 0; la = 0
  for (i = 1; i <= NR; i++) if (trim(a[i]) != "") { if (!fi) fi = i; la = i }
  wrap = "none"; wint = ""; d = ""; cl = 0
  if (fi > 0) {
    s = a[fi]
    if (fi == 1) sub(/^\357\273\277/, "", s)
    s = trim(s); sub(/^\$ /, "", s); sub(/^sudo /, "", s)
    p = index(s, "<<")
    if (p > 0 && substr(s, p, 3) != "<<<") {
      left = trim(substr(s, 1, p - 1)); right = trim(substr(s, p + 2))
      sub(/^-/, "", right); right = trim(right)
      if (right ~ /^["\047]?[A-Za-z_][A-Za-z0-9_]*["\047]?$/) {
        d = right; gsub(/["\047]/, "", d)
        split(left, w, /[ \t]+/); cmd = w[1]; sub(/.*\//, "", cmd)
        rest = substr(left, length(w[1]) + 1)
        wrap = "shellcmd"
        if (cmd ~ /^(python|python2|python3|bash|sh|dash|zsh|node|nodejs|perl|ruby|php|lua)$/ && rest ~ /^([ \t]+-[^ \t|>&;]*)*[ \t]*$/) wrap = "interp"
        wint = cmd
        for (j = fi + 1; j <= la; j++) if (trim(a[j]) == d) { cl = j; break }
      }
    }
  }
  print "wrap=" wrap; print "wint=" wint; print "delim=" d
  print "wfirst=" fi; print "wclose=" cl; print "wlast=" la
}' "$src" 2>/dev/null)
while IFS='=' read -r k v; do
  case "$k" in
    wrap) wrap="$v" ;; wint) wint="$v" ;; delim) delim="$v" ;;
    wfirst) wfirst="$v" ;; wclose) wclose="$v" ;; wlast) wlast="$v" ;;
  esac
done << EOFAN
$an
EOFAN

plan_unwrap=0; plan_clean=0; plan_interp=""; trunc=0; issues=""
add() { issues="${issues}  • $1${nl}"; }
if [ "$wrap" != "none" ]; then
  wl=$(lang_of "$wint")
  if [ "$wclose" -eq 0 ]; then
    trunc=1
    add "pembungkus '$wint << $delim' dibuka, tetapi penutup '$delim' tidak ada (tempelan kemungkinan terpotong)"
  fi
  if [ "$wrap" = "interp" ] && [ -n "$srclang" ] && [ "$srclang" = "$wl" ] && [ "$srclang" != "sh" ]; then
    plan_unwrap=1
    add "berkas $name memuat pembungkus '$wint << $delim'. Itu bukan bagian skrip $(lang_label "$srclang"); cukup isinya yang dijalankan"
  elif [ -n "$srclang" ] && [ "$srclang" != "sh" ]; then
    plan_interp=$(shell_bin)
    add "isi $name adalah perintah shell ('$wint ... << $delim'), padahal bahasanya $(lang_label "$srclang"); lebih tepat dijalankan dengan shell"
  fi
fi
shellish=0
if [ "$srclang" = "sh" ] || [ -n "$plan_interp" ]; then shellish=1; fi
if [ "$wrap" != "none" ] && [ -z "$srclang" ]; then shellish=1; fi
if [ "$crlf" -eq 1 ] && [ "$shellish" -eq 1 ]; then
  plan_clean=1
  add "akhir baris Windows (CRLF) membuat skrip shell gagal ('command not found' dengan karakter ^M)"
fi
if [ "$bom" -eq 1 ] && { [ "$shellish" -eq 1 ] || [ "${first#\#!}" != "$first" ]; }; then
  plan_clean=1
  add "tanda BOM di awal berkas merusak baris shebang"
fi
fixable=0
if [ "$plan_unwrap" -eq 1 ] || [ "$plan_clean" -eq 1 ] || [ -n "$plan_interp" ]; then fixable=1; fi

# ---- [RUN-HINT] shebang vs ekstensi (masalah) + tips shebang/izin eksekusi (bukan masalah, tidak masuk 'issues')
extlang=$(lang_of "$src")
hasheb=0; shlang=""; sstate=none
case "$first" in '#!'*) hasheb=1; sstate=ok; shlang=$(lang_of "$(sheb_name "${first#??}")") ;; esac
if [ "$wrap" = "none" ] && [ "$hasheb" -eq 1 ] && [ -n "$extlang" ] && [ -n "$shlang" ] && [ "$extlang" != "$shlang" ]; then
  sstate=mismatch
  add "shebang '$first' menandakan $(lang_label "$shlang"), tetapi ekstensi $name menandakan $(lang_label "$extlang"); skrip dijalankan sebagai $(lang_label "$shlang")"
fi
notes=""
nadd() { notes="${notes}  ℹ $1${nl}"; }
tl="$extlang"; if [ -z "$tl" ] && [ "$wrap" = "none" ]; then tl=$(guess_lang "$src"); fi
xstate=0; if [ -x "$src" ]; then xstate=1; fi
if [ "$wrap" = "none" ]; then
  if [ "$hasheb" -eq 0 ] && [ -n "$tl" ]; then
    nadd "belum ada shebang (baris 1, contoh: $(sheb_for "$tl")). Lewat molina-run tetap jalan; shebang perlu untuk './$name' atau dibagikan. Otomatis: molina-run --fix $name"
  elif [ "$hasheb" -eq 1 ]; then
    if shared_storage "$src"; then
      nadd "$name ada di penyimpanan bersama Android: chmod +x tidak berlaku di sini. Jalankan lewat 'molina-run $name', atau salin ke ~ lalu chmod +x"
    elif [ "$xstate" -eq 0 ]; then
      nadd "belum ada izin eksekusi: './$name' akan 'Permission denied'. Perbaiki: chmod +x $name (atau molina-run --fix $name)"
    fi
  fi
fi

if [ "$check_only" -eq 1 ]; then
  echo "lang=$srclang wrap=$wrap issues=$(printf '%s' "$issues" | grep -c .) unwrap=$plan_unwrap clean=$plan_clean interp=$plan_interp trunc=$trunc sheb=$sstate exec=$xstate hints=$(printf '%s' "$notes" | grep -c .)"
  exit 0
fi

g=""; r=""; y=""; z=""
if [ -t 1 ]; then g=$(printf '\033[1;32m'); r=$(printf '\033[1;31m'); y=$(printf '\033[1;33m'); z=$(printf '\033[0m'); fi

# [NANO-DETECT] --hint: dipanggil pembungkus nano sesudah nano ditutup. Diam bila bukan skrip, tips dimatikan, atau tak ada saran.
if [ "$hint_only" -eq 1 ]; then
  hconf="${XDG_CONFIG_HOME:-${HOME:-/root}/.config}/molina/run-notes"
  hmode="${MOLINA_RUN_NOTES:-}"
  if [ -z "$hmode" ] && [ -f "$hconf" ]; then hmode=$(head -n 1 "$hconf" 2>/dev/null); fi
  [ "$hmode" = "off" ] && exit 0
  [ -s "$src" ] || exit 0
  if [ -z "$extlang" ] && [ "$hasheb" -eq 0 ] && [ "$wrap" = "none" ]; then exit 0; fi
  need_sheb=0
  if [ "$wrap" = "none" ] && [ "$hasheb" -eq 0 ] && [ -n "$(sheb_for "$extlang")" ]; then need_sheb=1; fi
  if [ -z "$issues" ] && [ "$need_sheb" -eq 0 ]; then exit 0; fi
  printf '%sMolina · saran untuk %s:%s\n' "$y" "$name" "$z"
  printf '%s' "$issues"
  if [ "$need_sheb" -eq 1 ]; then
    printf '  • belum ada shebang (baris 1, contoh: %s)\n' "$(sheb_for "$extlang")"
    exit 10
  fi
  printf '  Periksa lewat Alt+R di nano, atau: molina-run %s\n' "$name"
  exit 0
fi

# ---- dialog dan perbaikan
td=""
title_set=0
cleanup() {
  if [ -n "$td" ]; then rm -rf "$td"; fi
  if [ "$title_set" -eq 1 ]; then printf '\033]0;\007'; fi # [NANO-RUNFIX]
}
trap cleanup EXIT

# ---- [RUN-HINT] molina-run --fix: tambah shebang yang hilang + chmod +x (berkas dicadangkan sebagai .bak)
if [ "$fix_only" -eq 1 ]; then
  if [ "$wrap" != "none" ]; then
    echo "molina-run: $name memuat pembungkus heredoc; jalankan 'molina-run $name' dan pilih [2] dulu." >&2
    exit 2
  fi
  fl="$tl"
  did=0
  if [ "$hasheb" -eq 0 ]; then
    sl=$(sheb_for "$fl")
    if [ -z "$sl" ]; then
      echo "molina-run: bahasa $name tidak dikenali; tambahkan shebang manual (contoh: #!/bin/sh)" >&2
      exit 2
    fi
    bak="${src}.bak"; n=1
    while [ -e "$bak" ]; do bak="${src}.bak.$n"; n=$((n+1)); done
    td=$(mktemp -d "${TMPDIR:-/tmp}/molina-run.XXXXXX")
    {
      printf '%s\n' "$sl"
      if [ "$bom" -eq 1 ]; then tail -c +4 "$src"; else cat "$src"; fi
    } | { if [ "$crlf" -eq 1 ] && [ "$fl" = "sh" ]; then tr -d '\r'; else cat; fi; } > "$td/$name" \
      && cp "$src" "$bak" && cp "$td/$name" "$src" || { echo "molina-run: gagal menambah shebang" >&2; exit 1; }
    printf '%s✔ Shebang ditambah: %s (cadangan: %s)%s\n' "$g" "$sl" "${bak##*/}" "$z"
    did=1
  fi
  if shared_storage "$src"; then
    printf '%sℹ %s di penyimpanan bersama Android: chmod +x tidak berlaku. Jalankan: molina-run %s%s\n' "$y" "$name" "$name" "$z"
  elif [ -x "$src" ]; then
    :
  elif chmod +x "$src" 2>/dev/null; then
    printf '%s✔ Izin eksekusi ditambah (chmod +x)%s\n' "$g" "$z"; did=1
  else
    echo "molina-run: gagal chmod +x $name" >&2
  fi
  if [ "$did" -eq 0 ]; then echo "Tidak ada yang perlu diperbaiki."; fi
  if [ "$sstate" = "mismatch" ]; then echo "Catatan: shebang tidak cocok dengan ekstensi; periksa manual (molina-run $name lalu [e])." >&2; fi
  exit 0
fi
force_interp=""
if [ -n "$issues" ]; then
  conf="${XDG_CONFIG_HOME:-${HOME:-/root}/.config}/molina/run-ask"
  mode="${MOLINA_RUN_ASK:-}"
  if [ -z "$mode" ] && [ -f "$conf" ]; then mode=$(head -n 1 "$conf" 2>/dev/null); fi
  [ -z "$mode" ] && mode=ask
  interactive=0
  if [ -t 1 ] && ( : < /dev/tty ) 2>/dev/null; then interactive=1; fi
  if [ "$trunc" -eq 1 ]; then def=x; elif [ "$fixable" -eq 1 ]; then def=1; else def=3; fi
  ans="${MOLINA_RUN_ANSWER:-}"
  if [ -z "$ans" ]; then
    if [ "$mode" = "fix" ] && [ "$fixable" -eq 1 ] && [ "$trunc" -eq 0 ]; then
      ans=1
      printf '%s⚙ Perbaikan otomatis (sementara) untuk %s:%s\n%s' "$y" "$name" "$z" "$issues"
    elif [ "$mode" = "no" ] || [ "$interactive" -eq 0 ]; then
      if [ "$trunc" -eq 1 ]; then ans=x; else ans=3; fi
      printf '%speringatan untuk %s:%s\n%s' "$y" "$name" "$z" "$issues" >&2
    else
      printf '%s┌ Molina · periksa skrip: %s%s\n' "$y" "$name" "$z"
      printf '%s' "$issues"
      keys=""
      if [ "$fixable" -eq 1 ]; then
        echo "  [1] Perbaiki sementara lalu jalankan (berkas tidak diubah)"
        echo "  [2] Perbaiki berkas (cadangan: ${name}.bak) lalu jalankan"
        echo "  [a] Seperti [1], dan jangan tanya lagi"
        keys="1/2/a/"
      fi
      echo "  [3] Jalankan apa adanya"
      echo "  [e] Edit lagi di nano"
      echo "  [x] Batal"
      # [NANO-RUNFIX] judul sesi "nano-run: <berkas>" selama menunggu jawaban: aplikasi menampilkan tombol di baris nano
      printf '\033]0;nano-run: %s\007' "$name"; title_set=1
      printf '%s└ Pilih [%s3/e/x] (Enter = %s): ' "$y" "$keys" "$def"
      printf '%s' "$z"
      IFS= read -r ans < /dev/tty
      printf '\033]0;\007'; title_set=0
      [ -z "$ans" ] && ans=$def
    fi
  fi
  case "$ans" in
    1|2|a|A)
      if [ "$fixable" -eq 0 ]; then ans=3; fi
      ;;
  esac
  case "$ans" in
    x|X)
      echo "molina-run: dibatalkan." >&2
      exit 125
      ;;
    e|E)
      ln="$wfirst"; if [ "$trunc" -eq 1 ]; then ln="$wlast"; fi
      [ "$ln" -gt 0 ] 2>/dev/null || ln=1
      exec "${MOLINA_NANO_WRAPPER:-/usr/local/bin/nano}" "+$ln" "$src"
      ;;
    1|2|a|A)
      td=$(mktemp -d "${TMPDIR:-/tmp}/molina-run.XXXXXX")
      endl=$((wlast + 1))
      if [ "$wclose" -gt 0 ]; then endl="$wclose"; fi
      {
        if [ "$bom" -eq 1 ]; then tail -c +4 "$src"; else cat "$src"; fi
      } | {
        if [ "$plan_clean" -eq 1 ] || [ "$plan_unwrap" -eq 1 ]; then tr -d '\r'; else cat; fi
      } | {
        if [ "$plan_unwrap" -eq 1 ]; then awk -v s="$wfirst" -v e="$endl" 'NR>s && NR<e'; else cat; fi
      } > "$td/$name"
      if [ "$ans" = "2" ]; then
        bak="${src}.bak"; n=1
        while [ -e "$bak" ]; do bak="${src}.bak.$n"; n=$((n+1)); done
        cp "$src" "$bak" && cp "$td/$name" "$src" || { echo "molina-run: gagal memperbaiki berkas" >&2; exit 1; }
        printf '%s✔ Berkas diperbaiki. Cadangan: %s%s\n' "$g" "${bak##*/}" "$z"
        exe="$src"
      else
        exe="$td/$name"
        if [ "$ans" = "a" ] || [ "$ans" = "A" ]; then
          mkdir -p "${conf%/*}" 2>/dev/null && printf 'fix\n' > "$conf" 2>/dev/null \
            && printf '%s✔ Berikutnya diperbaiki otomatis. Kembalikan: rm %s%s\n' "$g" "$conf" "$z"
        fi
      fi
      force_interp="$plan_interp"
      ;;
  esac
fi

# [RUN-HINT] tips: dicetak setelah dialog, sebelum skrip jalan; tidak pernah menghentikan
notes_on=0
nconf="${XDG_CONFIG_HOME:-${HOME:-/root}/.config}/molina/run-notes"
nmode="${MOLINA_RUN_NOTES:-}"
if [ -z "$nmode" ] && [ -f "$nconf" ]; then nmode=$(head -n 1 "$nconf" 2>/dev/null); fi
if [ "$nmode" = "on" ] || { [ "$nmode" != "off" ] && [ -t 1 ]; }; then notes_on=1; fi
if [ -n "$notes" ] && [ "$notes_on" -eq 1 ]; then
  printf '%sTips skrip %s:%s\n%s' "$y" "$name" "$z" "$notes"
  printf '  (matikan tips: mkdir -p %s && echo off > %s)\n' "${nconf%/*}" "$nconf"
fi

# ---- pilih penerjemah
set -f
if [ -n "$force_interp" ]; then
  set -- "$force_interp" "$exe" "$@"
else
  case "$first" in
    '#!'*)
      line=${first#??}
      set -- $line "$exe" "$@"
      if [ "${1##*/}" = "env" ]; then
        shift
        while :; do
          case "$1" in -*|*=*) shift ;; *) break ;; esac
        done
      fi
      if ! command -v "$1" >/dev/null 2>&1; then
        alt=$(command -v "${1##*/}" 2>/dev/null)
        if [ -n "$alt" ]; then
          shift
          set -- "$alt" "$@"
        else
          # [RUN-HINT] shebang lama '#!/usr/bin/python' (Python 2) tidak ada di Debian: pakai python3 bila tersedia
          case "${1##*/}" in
            python|python2)
              if command -v python3 >/dev/null 2>&1; then
                echo "molina-run: '$1' tidak ada; dipakai python3" >&2
                shift
                set -- python3 "$@"
              fi
              ;;
          esac
        fi
      fi
      ;;
    *)
      case "$src" in
        *.py) set -- python3 "$exe" "$@" ;;
        *.sh) set -- "$(shell_bin)" "$exe" "$@" ;;
        *.bash) set -- bash "$exe" "$@" ;;
        *.js|*.mjs|*.cjs) set -- node "$exe" "$@" ;;
        *.pl) set -- perl "$exe" "$@" ;;
        *.rb) set -- ruby "$exe" "$@" ;;
        *.lua) set -- lua "$exe" "$@" ;;
        *.php) set -- php "$exe" "$@" ;;
        *.awk) set -- awk -f "$exe" "$@" ;;
        *)
          # Tanpa shebang/ekstensi dikenal: tebak dari isi
          if [ "$wrap" != "none" ]; then
            echo "molina-run: $name tanpa ekstensi; isinya perintah shell, dijalankan dengan shell" >&2
            set -- "$(shell_bin)" "$exe" "$@"
          elif grep -m1 -Eq '^[[:space:]]*(import [A-Za-z_]|from [A-Za-z_.]+ import |def [A-Za-z_]+\(|class [A-Za-z_]+|print\()' "$exe" 2>/dev/null; then
            echo "molina-run: $name tanpa ekstensi; isinya terlihat seperti Python, dijalankan dengan python3" >&2
            set -- python3 "$exe" "$@"
          else
            echo "molina-run: tidak tahu cara menjalankan $name. Tambahkan shebang di baris pertama (contoh: #!/bin/sh) atau pakai ekstensi .py .sh .js" >&2
            exit 126
          fi
          ;;
      esac
      ;;
  esac
fi
set +f
interp="$1"
if ! command -v "$interp" >/dev/null 2>&1; then
  echo "molina-run: penerjemah tidak ditemukan: $interp (pasang dengan: apt-get install ${interp##*/})" >&2
  exit 127
fi

printf '%s▶ %s (%s)%s\n' "$g" "$name" "${interp##*/}" "$z"
t0=$(date +%s)
trap : INT
"$@"
rc=$?
trap - INT
dt=$(( $(date +%s) - t0 ))
if [ "$rc" -eq 0 ]; then
  printf '%s✔ selesai (%s dtk)%s\n' "$g" "$dt" "$z"
elif [ "$rc" -eq 130 ]; then
  printf '%s✘ dihentikan (Ctrl+C)%s\n' "$r" "$z"
else
  printf '%s✘ keluar dengan kode %s (%s dtk)%s\n' "$r" "$rc" "$dt" "$z"
fi
exit "$rc"

#!/usr/bin/env bash
# mkc v2: mini compiler Kotlin multi-module tanpa Gradle/APK [MKC-2]
# Pakai: mkc.sh list | all | clean | fetch-libs | <modul> [--no-deps] [--pure|--full] [--prune] [--pkg a,b]
# Env  : MKC_ANDROID_JAR  MKC_LIBS (bawaan .mkc/libs)  MKC_JVM (17)  MKC_LINES (40)  MKC_NONPURE (regex paket)
set -u
ROOT="${MKC_ROOT:-$PWD}"; OUT="$ROOT/.mkc/out"; LIBS="${MKC_LIBS:-$ROOT/.mkc/libs}"
AJAR="${MKC_ANDROID_JAR:-}"; LINES_MAX="${MKC_LINES:-40}"; export JAVA_OPTS="${JAVA_OPTS:--Xmx768m}"
NONPURE="${MKC_NONPURE:-android|androidx|io\.github\.rosemoe|org\.eclipse\.tm4e|com\.google\.android}"
usage(){ sed -n '2,4p' "$0" | sed 's/^# //'; exit 2; }
[ $# -ge 1 ] || usage
CMD="$1"; shift
NODEPS=0; MODE=auto; PKG=""; PRUNE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --no-deps) NODEPS=1;; --prune) PRUNE=1;; --pure) MODE=pure; PRUNE=1;; --full) MODE=full;;
    --pkg) shift; PKG="${1:-}";; --pkg=*) PKG="${1#--pkg=}";;
    *) echo "opsi tidak dikenal: $1"; usage;;
  esac; shift
done
if [ "$CMD" = fetch-libs ]; then
  mkdir -p "$LIBS"; MV="https://repo1.maven.org/maven2"
  for u in "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.9.0/kotlinx-coroutines-core-jvm-1.9.0.jar" "org/json/json/20240303/json-20240303.jar"; do
    f="$LIBS/$(basename "$u")"; [ -s "$f" ] && { echo "ada: $(basename "$f")"; continue; }
    wget -nv -O "$f" "$MV/$u" || { rm -f "$f"; echo "GAGAL: $u"; }
  done; ls "$LIBS"; exit 0
fi
if [ "$CMD" = clean ]; then rm -rf "$ROOT/.mkc/out"; echo "bersih"; exit 0; fi
S="$ROOT/settings.gradle.kts"; [ -f "$S" ] || S="$ROOT/settings.gradle"
[ -f "$S" ] || { echo "settings.gradle(.kts) tidak ada di $ROOT"; exit 2; }

declare -A DIR DEPS SEEN ST REB
MODS=()
for m in $(grep -oE "[\"']:[A-Za-z0-9_:.-]+[\"']" "$S" | tr -d "\"'" | sort -u); do
  DIR[$m]="$(echo "${m#:}" | tr : /)"; MODS+=("$m")
done
for m in $(grep -oE "includeBuild\([\"'][^\"']+" "$S" | sed "s/.*[\"']//"); do
  DIR[$m]="$m"; MODS+=("$m")
done
bfile(){ local d="$ROOT/${DIR[$1]}"
  if [ -f "$d/build.gradle.kts" ]; then echo "$d/build.gradle.kts"
  elif [ -f "$d/build.gradle" ]; then echo "$d/build.gradle"; fi; }
for m in "${MODS[@]}"; do
  f="$(bfile "$m")"; DEPS[$m]=""
  [ -n "$f" ] && DEPS[$m]="$(grep -oE "project\([\"']:[A-Za-z0-9_:.-]+" "$f" | sed "s/.*[\"']//" | sort -u | tr '\n' ' ')"
done
msrc(){ local dir="$ROOT/${DIR[$1]}" s; for s in src/main/kotlin src/main/java; do [ -d "$dir/$s" ] && echo "$dir/$s"; done; }
if [ "$CMD" = list ]; then
  for m in "${MODS[@]}"; do
    f="$(bfile "$m")"; kind=jvm; [ -n "$f" ] && grep -q 'com\.android' "$f" && kind=android
    srcs=(); while IFS= read -r x; do srcs+=("$x"); done < <(msrc "$m")
    n=0; [ ${#srcs[@]} -gt 0 ] && n=$(find "${srcs[@]}" \( -name '*.kt' -o -name '*.java' \) | wc -l)
    printf '%-22s %-8s %4s file  <- %s\n' "$m" "$kind" "$n" "${DEPS[$m]:-}"
  done; exit 0
fi
command -v kotlinc >/dev/null || { echo "kotlinc belum ada: Settings > Paket > Kotlin (.kts)"; exit 2; }

ORDER=(); TARGETS=()
visit(){
  [ -n "${SEEN[$1]:-}" ] && return; SEEN[$1]=1
  local d
  if [ "$NODEPS" = 0 ]; then
    for d in ${DEPS[$1]:-}; do [ -n "${DIR[$d]:-}" ] && visit "$d"; done
  fi
  ORDER+=("$1")
}
if [ "$CMD" = all ]; then
  for m in "${MODS[@]}"; do TARGETS+=("$m"); visit "$m"; done
else
  t="$CMD"; [ -n "${DIR[$t]:-}" ] || t=":$t"
  [ -n "${DIR[$t]:-}" ] || { echo "modul tidak dikenal: $CMD (lihat: mkc.sh list)"; exit 2; }
  TARGETS+=("$t"); visit "$t"
fi
is_target(){ local x; for x in "${TARGETS[@]}"; do [ "$x" = "$1" ] && return 0; done; return 1; }

# Keluarkan daftar file sumber modul $1 sesuai mode $2 dan filter paket $3
pick(){
  local m="$1" mode="$2" pkg="$3" srcs=() x f p keep
  while IFS= read -r x; do srcs+=("$x"); done < <(msrc "$m")
  [ ${#srcs[@]} -gt 0 ] || return 0
  find "${srcs[@]}" \( -name '*.kt' -o -name '*.java' \) | sort | while IFS= read -r f; do
    if [ -n "$pkg" ]; then
      keep=0; for p in ${pkg//,/ }; do case "$f" in */"$p"/*) keep=1;; esac; done
      [ $keep = 1 ] || continue
    fi
    if [ "$mode" = pure ]; then
      grep -qE "^import ($NONPURE)\.|(^|[^A-Za-z0-9_.])android\.(util|os|content|app|view|widget|graphics|net|provider)\." "$f" && continue
    fi
    echo "$f"
  done
}

build(){
  local m="$1" o="$OUT/${1//:/_}" files=() f d j cp="" rc mode sig nerr bf pkg=""
  bf="$(bfile "$m")"; mode="$MODE"
  if [ "$mode" = auto ]; then
    mode=full
    if [ -n "$bf" ] && grep -q 'com\.android' "$bf" && { [ -z "$AJAR" ] || [ ! -f "$AJAR" ]; }; then mode=pure; fi
  fi
  is_target "$m" && pkg="$PKG"
  [ "$mode" = pure ] && PRUNE=1
  while IFS= read -r f; do [ -n "$f" ] && files+=("$f"); done < <(pick "$m" "$mode" "$pkg")
  if [ -z "$(printf '%s\n' "${files[@]:-}" | grep -m1 '\.kt$')" ]; then ST[$m]="LEWATI (tanpa .kt)"; return 0; fi
  for d in ${DEPS[$m]:-}; do
    [ -n "${DIR[$d]:-}" ] || continue
    case "${ST[$d]:-}" in GAGAL*|"LEWATI (dep"*) ST[$m]="LEWATI (dep $d gagal)"; return 1;; esac
    [ -d "$OUT/${d//:/_}/classes" ] && cp="$cp:$OUT/${d//:/_}/classes"
    [ -n "${REB[$d]:-}" ] && REB[$m]=1
  done
  sig="$mode|$pkg|$(printf '%s\n' "${files[@]}" | cksum)"
  if [ -z "${REB[$m]:-}" ] && [ -f "$o/.ok" ] && [ "$(cat "$o/.ok")" = "$sig" ] && \
     [ -z "$(find "${files[@]}" -newer "$o/.ok" -print -quit)" ]; then
    local pn=0; [ -f "$o/pruned.txt" ] && pn=$(sort -u "$o/pruned.txt" | grep -c .)
    if [ "$pn" -gt 0 ]; then ST[$m]="OK-SEBAGIAN (up-to-date, $mode, $pn dipangkas)"
    else ST[$m]="OK (up-to-date, $mode, ${#files[@]} file)"; fi
    return 0
  fi
  [ -n "$AJAR" ] && [ -f "$AJAR" ] && cp="$cp:$AJAR"
  for j in "$LIBS"/*.jar; do [ -f "$j" ] && cp="$cp:$j"; done
  rm -rf "$o"; mkdir -p "$o/classes"
  local args=(-jvm-target "${MKC_JVM:-17}" -nowarn -d "$o/classes")
  [ -n "$cp" ] && args+=(-cp "${cp#:}")
  echo "  mode=$mode, ${#files[@]} file${pkg:+, paket=$pkg}"
  local total=${#files[@]} round bad keep2=() pr=0; : > "$o/pruned.txt"
  for round in 1 2 3 4 5; do
    kotlinc "${args[@]}" "${files[@]}" > "$o/log.txt" 2>&1; rc=$?
    [ "$rc" -eq 0 ] && break
    [ "$PRUNE" = 1 ] || break
    bad="$(grep ': error:' "$o/log.txt" | sed -E 's/:[0-9]+:[0-9]+: error:.*//' | awk '{ if (substr($0,1,1)=="/") print $0; else print ENVIRON["PWD"] "/" $0 }' | sort -u)"
    [ -n "$bad" ] || break
    keep2=(); for f in "${files[@]}"; do grep -qxF "$f" <<< "$bad" || keep2+=("$f"); done
    [ ${#keep2[@]} -lt ${#files[@]} ] || break
    echo "$bad" >> "$o/pruned.txt"; files=("${keep2[@]}")
    [ -n "$(printf '%s\n' "${files[@]:-}" | grep -m1 '\.kt$')" ] || break
  done
  pr=$(sort -u "$o/pruned.txt" | grep -c .)
  if [ "$rc" -eq 0 ]; then
    echo "$sig" > "$o/.ok"; REB[$m]=1
    if [ "$pr" -gt 0 ]; then
      echo "  ! $pr file dipangkas (bergantung android/lib yang tidak ada), daftar: ${o#$ROOT/}/pruned.txt"
      ST[$m]="OK-SEBAGIAN ($mode, ${#files[@]}/$total file, $pr dipangkas)"
    else ST[$m]="OK ($mode, ${#files[@]} file)"; fi
    return 0
  fi
  nerr=$(grep -c ': error:' "$o/log.txt")
  grep ': error:' "$o/log.txt" | sed "s|$ROOT/||" | head -n "$LINES_MAX" | sed 's/^/  /'
  echo "  ... $nerr error. Log penuh: ${o#$ROOT/}/log.txt"
  ST[$m]="GAGAL ($mode, $nerr error)"; return 1
}

RC=0; SECONDS=0
for m in "${ORDER[@]}"; do echo "== $m"; build "$m" || RC=1; done
echo; echo "Ringkasan (${SECONDS}s):"
for m in "${ORDER[@]}"; do printf '  %-22s %s\n' "$m" "${ST[$m]:-?}"; done
exit $RC

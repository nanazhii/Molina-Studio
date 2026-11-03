#!/usr/bin/env bash
# [JVM-TESTS] Jalankan semua uji JVM murni (kotlinc) di scripts/{core,app,code}-tests dan ringkas hasilnya.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc. Paralel: JOBS=3 (bawaan 2).
# Dikecualikan (butuh JAR/stub tambahan lewat ARCHIVE_DEPS atau W7_STUBS): lihat SKIP di bawah.
set -uo pipefail
cd "$(dirname "$0")/.."
SKIP=" run_archive_compress.sh run_archive_engine.sh run_rootfs_backup.sh run_w7thumb.sh "
export LOGS; LOGS="$(mktemp -d)"
trap 'rm -rf "$LOGS"' EXIT
: > "$LOGS/hasil"
LIST=()
for f in scripts/core-tests/run*.sh scripts/app-tests/run*.sh scripts/code-tests/run*.sh scripts/vault-tests/run*.sh; do
  [ -f "$f" ] || continue
  case "$SKIP" in *" $(basename "$f") "*) echo "LEWATI $f"; continue ;; esac
  LIST+=("$f")
done
echo "Menjalankan ${#LIST[@]} skrip uji (paralel ${JOBS:-2})"
printf '%s\n' "${LIST[@]}" | xargs -P "${JOBS:-2}" -I{} bash -c '
  f="{}"; log="$LOGS/$(echo "$f" | tr / _).log"
  if timeout 600 bash "$f" >"$log" 2>&1; then echo "OK $f" >> "$LOGS/hasil"; else echo "GAGAL $f" >> "$LOGS/hasil"; fi'
sort "$LOGS/hasil"
FAIL=$(grep -c '^GAGAL' "$LOGS/hasil" || true)
OKN=$(grep -c '^OK' "$LOGS/hasil" || true)
echo "Ringkasan: $OKN lulus, $FAIL gagal dari ${#LIST[@]} skrip"
if [ "$FAIL" -gt 0 ]; then
  grep '^GAGAL' "$LOGS/hasil" | sort | while read -r _ f; do
    echo "===== $f ====="; tail -30 "$LOGS/$(echo "$f" | tr / _).log"
  done
  exit 1
fi

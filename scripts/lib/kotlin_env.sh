# [KOTLIN-ENV] Lingkungan kotlinc bersama untuk skrip tes JVM. Dipakai dengan `source`, bukan dijalankan:
#   source "$ROOT/scripts/lib/kotlin_env.sh"
#   "$KC" -nowarn -d "$OUT" ...
#   java -cp "$OUT:$KSTD" NamaTestsKt
# Menyediakan:
#   KC    perintah kotlinc (override: KOTLINC=/jalur/kotlinc)
#   KSTD  jalur kotlin-stdlib.jar (override: KOTLIN_STDLIB=/jalur/kotlin-stdlib.jar)
# kotlinc sering berupa symlink (mis. /usr/local/bin/kotlinc -> /opt/kotlinc/bin/kotlinc), jadi jalur
# stdlib dihitung dari lokasi asli (readlink -f), lalu KOTLIN_HOME, lalu pencarian di /opt dan /usr/share.
# Gagal dengan pesan jelas (kode 127) bila kotlinc atau stdlib tidak ada.

KC="${KOTLINC:-kotlinc}"
if ! command -v "$KC" >/dev/null 2>&1; then
  echo "TIDAK BISA DIJALANKAN (kode 127): kotlinc tidak ditemukan (PATH atau KOTLINC=/jalur/kotlinc). Ini masalah lingkungan, bukan kegagalan tes." >&2
  return 127 2>/dev/null || exit 127
fi

KSTD="${KOTLIN_STDLIB:-}"
if [ -z "$KSTD" ]; then
  _kc_real="$(readlink -f "$(command -v "$KC")")"
  for _c in \
    "$(dirname "$_kc_real")/../lib/kotlin-stdlib.jar" \
    "${KOTLIN_HOME:-/nonexistent}/lib/kotlin-stdlib.jar" \
    /opt/kotlinc/lib/kotlin-stdlib.jar \
    /usr/share/kotlinc/lib/kotlin-stdlib.jar; do
    if [ -f "$_c" ]; then KSTD="$(readlink -f "$_c")"; break; fi
  done
  unset _kc_real _c
fi
if [ ! -f "$KSTD" ]; then
  echo "TIDAK BISA DIJALANKAN (kode 127): kotlin-stdlib.jar tidak ditemukan (set KOTLIN_STDLIB=/jalur/kotlin-stdlib.jar). Masalah lingkungan, bukan kegagalan tes." >&2
  return 127 2>/dev/null || exit 127
fi
export KC KSTD

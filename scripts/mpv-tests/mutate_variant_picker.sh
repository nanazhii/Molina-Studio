#!/usr/bin/env bash
# Uji mutasi VariantPicker: tiap mutan harus membuat tes GAGAL. Butuh kotlinc (>= 2.1).
set -uo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
M=feature/mpv/src/main/java/com/molina/cinema
SRC="$M/VariantPicker.kt"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mutants=(
  's/it.height <= maxHeight/it.height < maxHeight/'
  's/it.height <= maxHeight/it.height >= maxHeight/'
  's/maxByOrNull { it.height }/minByOrNull { it.height }/'
  's/variants.minByOrNull { it.height }/variants.maxByOrNull { it.height }/'
  's/variants.minByOrNull { it.height }/variants.firstOrNull()/'
  's/            ?: variants.minByOrNull { it.height }//'
)
killed=0; total=${#mutants[@]}
for i in "${!mutants[@]}"; do
  mkdir -p "$TMP/$i/src"
  if ! sed "${mutants[$i]}" "$SRC" > "$TMP/$i/src/VariantPicker.kt"; then echo "ERROR mutan $i: sed gagal"; continue; fi
  if cmp -s "$SRC" "$TMP/$i/src/VariantPicker.kt"; then echo "MUTAN $i TIDAK MENGUBAH BERKAS (pola salah)"; continue; fi
  if "$KC" -nowarn -d "$TMP/$i/out" $M/VideoQuality.kt "$TMP/$i/src/VariantPicker.kt" scripts/mpv-tests/VariantPickerTests.kt >/dev/null 2>&1 \
     && ! java -cp "$TMP/$i/out:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" VariantPickerTestsKt >/dev/null 2>&1; then
    echo "mati  mutan $i"; killed=$((killed+1))
  elif [ ! -d "$TMP/$i/out" ]; then
    echo "ERROR mutan $i: tidak terkompilasi (bukan hitungan mati)"
  else
    echo "HIDUP mutan $i: ${mutants[$i]}"
  fi
done
echo "Skor mutasi: $killed/$total"
[ "$killed" -eq "$total" ]

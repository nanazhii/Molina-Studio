#!/usr/bin/env bash
# Uji mutasi QualityRule: tiap mutan harus membuat tes GAGAL. Butuh kotlinc (>= 2.1).
set -uo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
M=feature/mpv/src/main/java/com/molina/cinema
SRC="$M/QualityRule.kt"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mutants=(
  's/forcedAudioOnly || useAudioUI/forcedAudioOnly \&\& useAudioUI/'
  's/forcedAudioOnly || useAudioUI/forcedAudioOnly/'
  's/forcedAudioOnly || useAudioUI/useAudioUI/'
  's/if (audioOnlyActive(forcedAudioOnly, useAudioUI)) audio else stored/if (audioOnlyActive(forcedAudioOnly, useAudioUI)) stored else audio/'
  's/if (audioOnlyActive(forcedAudioOnly, useAudioUI)) audio else stored/stored/'
  's/if (audioOnlyActive(forcedAudioOnly, useAudioUI)) audio else stored/audio/'
)
killed=0; total=${#mutants[@]}
for i in "${!mutants[@]}"; do
  mkdir -p "$TMP/$i/src"
  if ! sed "${mutants[$i]}" "$SRC" > "$TMP/$i/src/QualityRule.kt"; then echo "ERROR mutan $i: sed gagal"; continue; fi
  if cmp -s "$SRC" "$TMP/$i/src/QualityRule.kt"; then echo "MUTAN $i TIDAK MENGUBAH BERKAS (pola salah)"; continue; fi
  if "$KC" -nowarn -d "$TMP/$i/out" "$TMP/$i/src/QualityRule.kt" scripts/mpv-tests/QualityRuleTests.kt >/dev/null 2>&1 \
     && ! java -cp "$TMP/$i/out:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" QualityRuleTestsKt >/dev/null 2>&1; then
    echo "mati  mutan $i"; killed=$((killed+1))
  elif [ ! -d "$TMP/$i/out" ]; then
    echo "ERROR mutan $i: tidak terkompilasi (bukan hitungan mati)"
  else
    echo "HIDUP mutan $i: ${mutants[$i]}"
  fi
done
echo "Skor mutasi: $killed/$total"
[ "$killed" -eq "$total" ]

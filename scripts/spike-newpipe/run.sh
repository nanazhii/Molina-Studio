#!/usr/bin/env bash
# Spike 5d-0: cek NewPipeExtractor ter-resolve dari JitPack (host build-time saja) dan catat hash.
set -u
cd "$(dirname "$0")"
VER="${1:?versi tag, contoh v0.26.5}"
OUT="${2:?folder keluaran}"
[[ "$VER" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "::error::format versi salah: $VER" >&2; exit 1; }
mkdir -p "$OUT"
CANDS=("com.github.TeamNewPipe:NewPipeExtractor:$VER" "com.github.TeamNewPipe.NewPipeExtractor:extractor:$VER")
ok=""
for c in "${CANDS[@]}"; do
  for try in 1 2 3; do
    echo "== coba $c (percobaan $try)"
    if gradle -q --refresh-dependencies -Pcoord="$c" printResolved >"$OUT/resolved.txt" 2>"$OUT/err.txt"; then ok="$c"; break 2; fi
    tail -4 "$OUT/err.txt"; sleep 90
  done
done
if [ -z "$ok" ]; then echo "::error::tidak ada koordinat yang ter-resolve" >&2; exit 1; fi
echo "$ok" > "$OUT/coord.txt"
gradle -q -Pcoord="$ok" dependencies --configuration spike > "$OUT/tree.txt" 2>&1
gradle -q -Pcoord="$ok" --write-verification-metadata sha256 printResolved > /dev/null 2>&1
cp gradle/verification-metadata.xml "$OUT/" 2>/dev/null || echo "::warning::verification-metadata.xml tidak terbentuk"
{ echo "### Koordinat ter-resolve: $ok"; echo '~~~'; cat "$OUT/resolved.txt"; echo '~~~'; } >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
cat "$OUT/resolved.txt"

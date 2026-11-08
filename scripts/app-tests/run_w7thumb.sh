#!/usr/bin/env bash
# Uji JVM W7Thumb (poster sidecar didahulukan dari frame video) memakai stub Android ringan di scripts/app-tests/w7thumb-stubs:
# Bitmap/BitmapFactory berbasis ImageIO, MediaMetadataRetriever terkendali, LruCache, dan withContext inline.
# Yang diuji adalah logika W7Thumb; perilaku API Android asli tetap perlu CI dan uji perangkat.
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
C=core/common/src/main/kotlin/com/molina/core/common
A=app/src/main/java/com/molina/studio/cinema
S=scripts/app-tests/w7thumb-stubs
"$KC" -nowarn -d "$OUT" $S/*.kt \
  $C/MiniJson.kt $C/JsonCursor.kt $C/MediaSidecar.kt $C/ThumbMath.kt $A/W7Thumb.kt \
  scripts/app-tests/W7ThumbTests.kt
java -Djava.awt.headless=true -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" W7ThumbTestsKt

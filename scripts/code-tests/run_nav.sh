#!/usr/bin/env bash
# Uji JVM murni untuk tumpukan "Kembali" (JumpStack) dan resolusi rootfs (RootfsPaths + classifyDebian).
# Butuh kotlinc (>= 2.1) di PATH atau KOTLINC=/jalur/kotlinc.
set -euo pipefail
cd "$(dirname "$0")/../.."
KC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
F=feature/code/src/main/kotlin/com/molina/code
"$KC" -nowarn -d "$OUT" \
  $F/nav/JumpStack.kt $F/nav/RootfsPaths.kt \
  $F/lsp/LspProtocol.kt $F/lsp/LspFeatures.kt $F/lsp/LspClient.kt $F/lsp/LspSession.kt $F/lsp/LspDefinitionUi.kt \
  $F/lsp/LspTransport.kt $F/lsp/LspPathMap.kt \
  $F/check/Checkers.kt $F/format/JsonFormatter.kt $F/format/XmlFormatter.kt $F/preview/RunPlan.kt \
  scripts/code-tests/NavTests.kt
java -cp "$OUT:$(dirname "$(command -v "$KC")")/../lib/kotlin-stdlib.jar" NavTestsKt

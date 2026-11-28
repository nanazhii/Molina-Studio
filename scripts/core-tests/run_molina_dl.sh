#!/usr/bin/env bash
# Uji molina_dl.py: unit murni + integrasi nyata (yt-dlp/ffmpeg vs server HTTP lokal, tanpa internet).
# Integrasi dilewati otomatis bila yt-dlp/ffmpeg/ffprobe tidak ada. Tambahan: tes Kotlin lewat run.sh dan run_ui.sh.
set -euo pipefail
cd "$(dirname "$0")/../.."
python3 -m unittest -v scripts/core-tests/test_molina_dl.py

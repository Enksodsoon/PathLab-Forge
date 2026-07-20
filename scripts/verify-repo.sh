#!/usr/bin/env bash
set -euo pipefail

required=(
  README.md
  AGENTS.md
  CODEX_START_HERE.md
  docs/architecture/SYSTEM.md
  docs/plans/active/current.md
)

for path in "${required[@]}"; do
  [[ -f "$path" ]] || { echo "Missing required file: $path" >&2; exit 1; }
done

if find . -type f \( \
  -iname '*.vsi' -o -iname '*.ets' -o -iname '*.svs' -o -iname '*.ndpi' \
  -o -iname '*.mrxs' -o -iname '*.scn' -o -iname '*.czi' -o -iname '*.oir' \
  -o -iname '*.ome.tif' -o -iname '*.ome.tiff' -o -iname '*.dzi' \
  -o -iname '*.plslide' \
\) -print -quit | grep -q .; then
  echo "Repository contains a forbidden slide/generated file." >&2
  exit 1
fi

echo "Repository policy check passed."

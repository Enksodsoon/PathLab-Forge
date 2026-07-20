#!/usr/bin/env bash
set -euo pipefail

OWNER="${1:-Enksodsoon}"
REPOSITORY="${2:-PathLab-Forge}"

if [[ ! -d .git ]]; then
  git init
fi

git branch -M main
git add .
if [[ -n "$(git status --porcelain)" ]]; then
  git commit -m "chore: bootstrap PathLab Forge"
fi

if command -v gh >/dev/null 2>&1; then
  gh repo create "${OWNER}/${REPOSITORY}" --private --source . --remote origin --push
  echo "Created and pushed https://github.com/${OWNER}/${REPOSITORY}"
else
  cat <<EOF
GitHub CLI was not found.
Create a private empty repository named ${REPOSITORY}, then run:
  git remote add origin https://github.com/${OWNER}/${REPOSITORY}.git
  git push -u origin main
EOF
fi

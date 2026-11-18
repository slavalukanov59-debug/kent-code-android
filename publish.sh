#!/usr/bin/env bash
set -euo pipefail

# Creates a NEW repo only; doesn't touch existing GitHub projects.
cd "$(dirname "$0")"
if ! command -v gh >/dev/null 2>&1; then
  echo 'Установи: pkg install git gh -y'; exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
  echo 'Сначала: gh auth login -h github.com -p https -w'; exit 1
fi
if [ -e .git ]; then
  echo 'В папке уже существует .git — прекращаю, чтобы не затереть историю.'; exit 1
fi
OWNER="$(gh api user -q .login)"
REPO="kent-code-android"
if gh repo view "$OWNER/$REPO" >/dev/null 2>&1; then
  echo "Репозиторий $OWNER/$REPO уже существует; ничего не перезаписываю."; exit 1
fi

git init -b main
# Local-only identity (no edits to global Git settings)
git config user.name "$OWNER"
GH_UID="$(gh api user -q .id)"
git config user.email "${GH_UID}+${OWNER}@users.noreply.github.com"
git add .
git commit -m 'Initial KENT CODE Android MVP with GitHub Actions APK build'
gh repo create "$REPO" --private --source=. --remote=origin --push
printf '\nГотово. Репозиторий: https://github.com/%s/%s\n' "$OWNER" "$REPO"
printf 'Открой вкладку Actions, дождись результата и скачай artifact APK.\n'

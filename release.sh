#!/usr/bin/env bash
# publishes a vrfrida update the Quest app can install by itself (Log tab -> Updates)
#   ./release.sh "what changed"
# bumps version.txt, builds, and makes GitHub release v<version> in update_repo.txt (owner/repo, must be PUBLIC:
# the app downloads without a token) with vrfrida.apk + menu.js. Needs: gh auth login (once).
set -euo pipefail
cd "$(dirname "$0")"
REPO=$(tr -d '[:space:]' < update_repo.txt 2>/dev/null || true)
[ -n "$REPO" ] || { echo "put owner/repo into questapp/update_repo.txt first"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "run: gh auth login"; exit 1; }
[ "$(gh repo view "$REPO" --json visibility -q .visibility 2>/dev/null)" = "PUBLIC" ] || { echo "$REPO doesn't exist or isn't public (the app can't download from a private repo)"; exit 1; }
V=$(( $(cat version.txt 2>/dev/null || echo 1) + 1 ))
echo "$V" > version.txt
./build.sh
cp build/assets/menu.js build/menu.js
gh release create "v$V" vrfrida.apk build/menu.js --repo "$REPO" --title "vrfrida v$V" --notes "${1:-vrfrida v$V}"
echo "released v$V: the app picks it up on its next check (or Log tab -> Check for updates)"

#!/usr/bin/env bash
# Bundles the latest AMO-signed uBlock Origin XPI into the APK
# (app/src/main/assets/extensions/ubo.xpi). The app installs it on first launch.
set -euo pipefail

DEST="app/src/main/assets/extensions/ubo.xpi"
url=""
if command -v gh >/dev/null 2>&1; then
  url="$(gh api repos/gorhill/uBlock/releases/latest \
        --jq '.assets[] | select(.name | test("firefox.*signed.*\\.xpi$")) | .browser_download_url' 2>/dev/null | head -n1 || true)"
fi
[ -z "$url" ] && url="https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"

echo "Fetching uBlock Origin from: $url"
mkdir -p "$(dirname "$DEST")"
curl -fsSL --retry 3 -o "$DEST" "$url"
unzip -p "$DEST" manifest.json | grep -m1 '"version"'
unzip -l "$DEST" | grep -q "META-INF/mozilla.rsa" && echo "signed: yes" || echo "WARNING: XPI not signed"

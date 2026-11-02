#!/usr/bin/env bash
# Downloads the latest signed uBlock Origin for Firefox and unpacks it where
# GeckoView loads built-in extensions from (resource://android/assets/extensions/ubo/).
set -euo pipefail

DEST="app/src/main/assets/extensions/ubo"
TMP="$(mktemp -d)"
XPI="$TMP/ubo.xpi"

url=""
if command -v gh >/dev/null 2>&1; then
  url="$(gh api repos/gorhill/uBlock/releases/latest \
        --jq '.assets[] | select(.name | test("firefox.*\\.xpi$")) | .browser_download_url' 2>/dev/null | head -n1 || true)"
fi
if [ -z "$url" ]; then
  url="https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
fi

echo "Fetching uBlock Origin from: $url"
curl -fsSL --retry 3 -o "$XPI" "$url"

rm -rf "$DEST"
mkdir -p "$DEST"
unzip -q "$XPI" -d "$DEST"
test -f "$DEST/manifest.json"
echo "uBlock Origin version: $(grep -m1 '"version"' "$DEST/manifest.json")"

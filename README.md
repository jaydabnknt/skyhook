# Skyhook

An Android browser built on **GeckoView** (Firefox's engine) with:

- **The real uBlock Origin** bundled in — tap the red shield for the full dashboard (filter lists, custom filters, trusted sites), long-press it for the per-site panel (element picker, on/off for a site).
- **Video detector** — a built-in extension watches the network for HLS (`.m3u8`), DASH (`.mpd`) and plain video/audio files. The video icon in the address bar lights up with a count.
- **Downloader** — pick a quality; HLS (incl. AES-128) and DASH are fetched segment by segment. When video and audio come as separate tracks (most DASH and many HLS sites), they're **merged into one MP4 on the fly**.
- **Cloud-only downloads** — choose Google Drive, Dropbox or Yandex Disk and bytes stream straight from the website to the drive through an 8 MB memory buffer. **Nothing is written to the phone**, not even a temp file. Choose "This phone" to save to `Download/Skyhook` instead.

Ordinary file downloads (PDFs, APKs, zips…) and long-press → "Download link/video/image" use the same destination picker.

DRM-protected services (Netflix, Disney+, Spotify…) cannot be downloaded.

## Getting the APK

Every push to `main` runs **Actions → Build APK**, which:

1. downloads the latest signed uBlock Origin release and bundles it,
2. uses the newest GeckoView release,
3. builds an arm64 APK and publishes it under **Releases** (with the signing SHA-1 in the notes).

## Cloud setup (one time each, all doable from a phone browser)

All three are configured inside the app: **⋮ → Settings**.

### Google Drive
1. [console.cloud.google.com](https://console.cloud.google.com) → new project → **APIs & Services → Library → Google Drive API → Enable**.
2. **OAuth consent screen** → External → fill the basics → add your Gmail under **Test users**.
3. **Credentials → Create credentials → OAuth client ID → Android**
   - Package name: `com.jaydabnknt.skyhook`
   - SHA-1: from the release notes of any build
4. In the app: Settings → Google Drive → **Connect**.

Uses the `drive.file` scope: the app can only see files it created.

### Dropbox
1. [dropbox.com/developers/apps](https://www.dropbox.com/developers/apps) → Create app → Scoped access → **App folder**.
2. **Permissions** tab → tick `files.content.write` → Submit.
3. **Settings** tab → Redirect URIs → add `http://localhost/skyhook-auth`.
4. Copy the **App key** into Settings → Dropbox, then **Connect**.

### Yandex Disk
1. [oauth.yandex.com](https://oauth.yandex.com) → Create app → Web services → Redirect URI `https://oauth.yandex.ru/verification_code`.
2. Data access: Yandex.Disk REST API → write access anywhere + read.
3. Copy the **ClientID** into Settings → Yandex Disk, then **Connect**.

Optional: put the Dropbox key / Yandex ID in repo secrets `DROPBOX_APP_KEY` / `YANDEX_CLIENT_ID` to bake them into builds.

## Repo secrets

| Secret | Purpose |
| --- | --- |
| `KEYSTORE_B64` | base64 of the release keystore (keeps the SHA-1 stable for Google) |
| `KEYSTORE_PASSWORD` | its password |
| `DROPBOX_APP_KEY` | optional default |
| `YANDEX_CLIENT_ID` | optional default |

## How the pieces fit

```
page ──network──▶ detector extension (webRequest) ──▶ content script ──native msg──▶ app
                                                                                    │
            pick quality ◀── Hls/Dash parser ◀───────────────────────────────────────┘
                 │
                 ▼
     segment fetcher (4 in flight, AES-128 decrypt)
                 │
     fMP4 muxer (merges separate audio, fixes offsets)   ─ or raw TS / file passthrough
                 │
                 ▼
     UploadSink: LocalSink │ Drive resumable │ Dropbox upload session │ Yandex streaming PUT
```

Code map: `browser/` (GeckoView shell, tabs, prompts), `media/` (detector model, HLS/DASH parsers, MP4 muxer, picker UI), `download/` (engine, service), `cloud/` (sinks + sign-in), `assets/extensions/detector/`.

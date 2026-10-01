# FastPaste

> Ultra-lightweight clipboard sync between Windows PC and Android — powered by **Tauri (Rust)**.

## Features

- 🔗 **Real-time clipboard sync** between PC ↔ Android via WebSocket
- ☁️ **Google Drive cloud sync** — sign in with Google and merge clipboard history through Drive app data
- 🔍 **Auto-discovery** — Android automatically finds PC on the same network via UDP broadcast
- 📋 **Clipboard history** — syncs up to 1,000 recent items with timestamps and preserves pinned items
- 🔐 **QR pairing** — authenticated, encrypted LAN sessions; optional passphrase encryption for Drive
- 🖼️ **Images and rich text** — image previews and resumable transfers; PNG/JPEG/WebP/GIF/BMP and a single image copied in Explorer
- ⌨️ **Global hotkey** — toggle visibility with a customizable shortcut (default: `Ctrl+Alt+Z`)
- 🖥️ **System tray** — runs silently in the background, close-to-tray
- ⚡ **Ultra-lightweight** — ~6 MB RAM idle, ~10 MB binary (vs. ~150 MB Electron)

## Download

Latest builds are published on GitHub Releases:

- Windows portable: `FastPaste-Portable.exe`
- Windows installer: `FastPaste_*_x64-setup.exe`
- Android APK: `FastPaste-Android.apk`

Download page: https://github.com/sieuxuan/fast-paste/releases/latest

The desktop and Android apps check `update.json` on the `master` branch to detect new versions.

See [CHANGELOG.md](CHANGELOG.md) for release notes.

### Copying images

Update both Windows and Android to the same build. On Windows, copy an image
from an app or select one image file in Explorer and press `Ctrl+C`. On Android,
use **Share → FastPaste** from Gallery, or copy an image and reopen FastPaste.
The newest image downloads automatically when the devices reconnect; tapping an
image in history copies it again. General file transfer and multiple images per
copy are not supported.

Android 10+ only lets a focused app or the default keyboard read another app's
clipboard. The Gallery share action avoids relying on a background read.

## Google Drive Sync

FastPaste stores cloud data in Google Drive's app-specific data folder (`appDataFolder`), so the sync file is private to this app.

For users, Google sync is one-click: open FastPaste, choose **Đăng nhập Google**, and the app will auto-sync when it starts and when clipboard history changes.

For release maintainers:

1. In Google Cloud Console, enable the Google Drive API.
2. Create an Android OAuth client for package `com.fastpaste.app` using the release keystore SHA-1.
3. Create a Desktop OAuth client for the Windows app.
4. Add GitHub repository secrets:
   - `FASTPASTE_GOOGLE_DESKTOP_CLIENT_ID`
   - `FASTPASTE_GOOGLE_DESKTOP_CLIENT_SECRET` (optional)

GitHub Actions embeds the desktop OAuth client into release builds, so end users do not need a `google_oauth.json` file.

For local development, the desktop app can also read runtime environment variables:

```bash
FASTPASTE_GOOGLE_DESKTOP_CLIENT_ID=...
FASTPASTE_GOOGLE_DESKTOP_CLIENT_SECRET=...
```

Or copy `google_oauth.example.json` to `google_oauth.json` next to `FastPaste.exe`.

Android uses Google Play services authorization. After signing in, both desktop and Android merge recent unique clipboard items through auto-sync. The sync budget is 1,000 items; pinned items are retained. Android's local database is not currently pruned to that budget.

## Architecture

```
fast-paste/
├── src/               # Frontend (HTML/JS)
│   └── index.html
├── src-tauri/         # Backend (Rust)
│   ├── src/lib.rs     # Core logic: WebSocket, UDP, Clipboard, Tray
│   ├── Cargo.toml     # Rust dependencies
│   └── tauri.conf.json
├── android/           # Android app (Kotlin/Jetpack Compose)
└── assets/            # App icons
```

## Development

### Prerequisites

- [Node.js](https://nodejs.org/) (v18+)
- [Rust](https://rustup.rs/) toolchain
- [Tauri CLI](https://v2.tauri.app/start/prerequisites/)

### Setup

```bash
npm install
```

### Run (Dev)

```bash
npm run dev
```

### Build (Release)

```bash
npm run build
```

The portable executable will be at `src-tauri/target/release/fast-paste.exe` (renamed to `FastPaste-Portable.exe` for releases).

### Verification

```bash
cargo test --manifest-path src-tauri/Cargo.toml --locked
gradle -p android testDebugUnitTest lintDebug assembleDebug
```

### Android background connection

When discovery, pairing or reconnecting does not establish a secure connection within two minutes, the service stops and removes its notification. Retry from the app when needed. A secure background connection keeps the required foreground-service notification; use **Dừng đồng bộ** on the notification or **Ngắt/Dừng** in the app to stop it immediately.

Android 10+ restricts clipboard reading to the focused app or default keyboard. Use Android's Share action or reopen FastPaste for outgoing clipboard sync; a foreground service alone does not bypass this restriction.

## Release A New Version

1. Update versions in `package.json`, `package-lock.json`, `src-tauri/Cargo.toml`, `src-tauri/Cargo.lock`, `src-tauri/tauri.conf.json`, `src/index.html`, and `android/app/build.gradle.kts`.
2. Run the verification commands, commit and push to `master`; confirm CI passes.
3. Create and push a tag:

```bash
git tag v2.2.0
git push origin v2.2.0
```

GitHub Actions will build and publish Windows + Android artifacts to the tagged release.
After the release succeeds and its assets exist, update `update.json` with the published versions and download URLs, then commit and push the manifest. This avoids advertising downloads before they are available.

## Network Protocol

| Protocol  | Port | Purpose                          |
|-----------|------|----------------------------------|
| UDP       | 4568 | Discovery broadcast (`FASTPASTE:<hostname>:<port>`) |
| WebSocket | 4567 | Clipboard text sync              |

## Android App

The companion Android app is in the `android/` directory. Build with Android Studio or Gradle:

```bash
gradle -p android assembleDebug
```

## License

MIT

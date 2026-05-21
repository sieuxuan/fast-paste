# FastPaste

> Ultra-lightweight clipboard sync between Windows PC and Android — powered by **Tauri (Rust)**.

## Features

- 🔗 **Real-time clipboard sync** between PC ↔ Android via WebSocket
- 🔍 **Auto-discovery** — Android automatically finds PC on the same network via UDP broadcast
- 📋 **Clipboard history** — stores up to 100 recent items with timestamps
- ⌨️ **Global hotkey** — toggle visibility with a customizable shortcut (default: `Ctrl+Alt+Z`)
- 🖥️ **System tray** — runs silently in the background, close-to-tray
- ⚡ **Ultra-lightweight** — ~6 MB RAM idle, ~10 MB binary (vs. ~150 MB Electron)

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

The portable executable will be at `src-tauri/target/release/FastPaste.exe`.

## Network Protocol

| Protocol  | Port | Purpose                          |
|-----------|------|----------------------------------|
| UDP       | 4568 | Discovery broadcast (`FASTPASTE:<hostname>:<port>`) |
| WebSocket | 4567 | Clipboard text sync              |

## Android App

The companion Android app is in the `android/` directory. Build with Android Studio or `./gradlew assembleDebug`.

## License

MIT

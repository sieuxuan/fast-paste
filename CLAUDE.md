# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

FastPaste is a two-component clipboard sync tool:
- **Desktop app** (Windows): Tauri v2 (Rust backend + plain HTML/JS frontend)
- **Android companion app**: Kotlin + Jetpack Compose + Room + OkHttp WebSocket

## Commands

### Desktop (Tauri)

```bash
npm install          # First-time setup
npm run dev          # Dev mode (hot-reload frontend, recompiles Rust on changes)
npm run build        # Release build → src-tauri/target/release/FastPaste.exe
```

### Android

```bash
cd android
gradle assembleDebug    # Debug APK
gradle assembleRelease  # Release APK
```

There are no tests in either component.

## Desktop Architecture

The frontend is a **single plain HTML file** (`src/index.html`) — no bundler, no framework. Tauri serves it directly via `frontendDist: "../src"`. All UI state is driven by a single `update_state` event emitted from Rust.

The backend is split into modules under `src-tauri/src/`:

| Module | Contents |
|--------|----------|
| `lib.rs` | IPC commands, window/tray setup, cloud-sync orchestration, `run()` |
| `network.rs` | UDP discovery broadcaster + WebSocket server (per-client tasks) |
| `history.rs` | History/sync models, merge + dedup logic, deleted-marker tombstones |
| `hotkeys.rs` | Hotkey parsing/validation/registration, quick-paste slots |
| `state.rs` | `AppStateData`, persistence to `settings.json`, `update_state` broadcast |
| `cloud.rs` | Google Drive OAuth + sync |

It runs four concurrent async tasks:

| Task | Description |
|------|-------------|
| UDP broadcaster | Sends `FASTPASTE:<hostname>:4567` to `255.255.255.255:4568` every 2s; rebinds sockets when the interface list changes (sleep/wake, network switch) |
| WebSocket server | Listens on `0.0.0.0:4567`, handles per-client send/receive tasks; tolerates transient accept errors |
| Clipboard poller | Polls clipboard every 250ms, pushes new text to history and WS broadcast channel |
| Setup | Registers global hotkey, system tray, close-to-tray behavior |

**State** is a single `Arc<Mutex<AppStateData>>` containing settings, history (max 500 items), IPs, and connected client IPs. It is persisted to `settings.json` next to the executable on every mutation.

**IPC** — JS calls these Rust commands via `invoke()`:
- `save_hotkey(hotkey)` — re-registers global shortcut
- `save_autostart(autostart)` — toggles Windows autolaunch
- `copy_text(text)` — writes to clipboard, sends over WS, hides window
- `request_state()` — triggers `update_state` event broadcast

**Events** — Rust emits `update_state` with the full `AppStateData` payload whenever state changes.

**WS sync protocol** — history sync uses structured JSON:
```json
{"app": "fastpaste", "type": "history_sync", "entries": [{"text": "...", "timestamp": 1234567890, "source": "PC"}]}
```
Raw plain-text messages (no JSON wrapping) are treated as immediate clipboard pastes.

## Android Architecture

MVVM pattern:

- **`MainViewModel`** — holds `UiState` as `StateFlow`; starts/stops `ClipboardService` via intents; auto-connects on UDP discovery but defers to the service for targets it already manages (`ClipboardService.activeTarget`), with a 10s same-target throttle
- **`ClipboardService`** — foreground service; owns `WebSocketClient` (per-client `CoroutineScope`); listens to `ClipboardManager` for local changes and sends them over WS; applies incoming text to clipboard; while disconnected runs its own `ServiceDiscovery` so a PC returning on a new IP is found even when the UI is gone
- **`WebSocketClient`** — OkHttp-based WS with exponential backoff reconnect (retries indefinitely, delay cap 15s), 10s ping interval; `retryNow()` skips the current backoff delay
- **`ServiceDiscovery`** — UDP listener on port 4568; in cycle mode scans in 15s windows with growing pauses (5s doubling to 60s) until stopped; stops when connected (battery optimization)
- **Room database** — `ClipboardEntry` table accessed via `ClipboardDao`; reads capped at 500 most recent items

On connection, both sides immediately send a full history sync payload, then merge by deduplicating on `text` content and applying the newest incoming entry to the live clipboard if it's newer than the latest local entry.

## Key Constraints

- The desktop app targets **Windows only** (autostart and global shortcut plugins are excluded on mobile via `cfg` flag in `Cargo.toml`).
- `withGlobalTauri: true` in `tauri.conf.json` means `window.__TAURI__` is available globally in the frontend — no npm imports needed.
- Virtual/link-local network interfaces are filtered out of the displayed IP list (`169.254.x.x`, VMware, WSL, Hyper-V, etc.) in `get_local_ips()`.
- The `settings.json` file lives next to the `.exe`, not in `%APPDATA%`, making the app portable.

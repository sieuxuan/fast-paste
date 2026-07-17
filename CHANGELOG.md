# Changelog

## 2.2.6 - 2026-07-17

- Added image, file, and rich-text clipboard sync between Windows and Android, including history and Google Drive metadata.
- Increased retained history from 500 to 1,000 items while always preserving pinned entries.
- Added a private-app exclusion list so selected password managers, banking apps, or other sensitive Windows apps are synced live but never saved to history.
- Replaced source executable labels with real app icons, added scalable app/folder filters, and added delete-by-filter.
- Added delete confirmations and durable undo backups; bulk and filtered deletes never remove pinned items, while explicit single-item deletion still can.
- Added editing on desktop and Android, removed the desktop Add button, and made the empty connection panel more compact.
- Modernized the Android home/settings UI and added a scroll-to-top action for long histories.
- Reduced unnecessary Google Drive uploads, made merges deterministic, preserved metadata more reliably, and prevented overlapping sync jobs.

## 2.2.5 - 2026-07-16

- Fixed the desktop WebSocket server dying silently on transient accept errors; a stalled client can no longer block new connections (per-connection handshake with timeout).
- Fixed UDP discovery going stale after PC sleep/wake or network changes — broadcast sockets now rebind automatically and the displayed IP list stays current.
- Android reconnects indefinitely with capped backoff, keeps discovering in the background via the sync service, and automatically follows the PC to a new IP address.
- Fixed an Android 12+ crash when auto-connect fired from the background, and a discovery restart race that could silently stop UDP scanning.
- Battery: discovery scans in 15s windows with growing pauses (5s→60s); reconnect log spam reduced.
- Refactored the desktop backend into network/history/hotkeys/state modules.

## 2.2.4 - 2026-07-09

- Fixed quick paste hotkeys by moving the default slot combo to Ctrl+Alt/Command+Alt and sending paste after the clipboard update.
- Added Windows source app/window metadata for clipboard items, with sync, search, and filter support on desktop and Android.
- Redesigned Android settings as a separate screen and reduced count-heavy UI labels to avoid scroll/layout issues.

## 2.2.3 - 2026-07-09

- Added a desktop Always on top setting, enabled by default for hotkey opens.
- Added Android history filters for all, pinned, untagged, and folder/tag views.
- Added Android connection logs for discovery, reconnect attempts, and sync activity.
- Refined the Android settings sheet with clearer status sections and last-sync context.

## 2.2.2 - 2026-07-06

- Cập nhật cấu hình và bật đồng bộ Google Drive.

## 2.2.1 - 2026-06-25

- Polished Vietnamese labels and status messages across desktop and Android.
- Changed pinned palette item clicks to edit instead of copy, with edit and unpin actions on each pinned item.
- Added configurable pinned quick slots 1-9 instead of assigning slots only by pinned order.
- Improved desktop history/editor wording, format button labels, Google sync badges, and hotkey error messages.
- Improved Android menu, Google status labels, share toast, and background sync notification wording.

## 2.2.0 - 2026-06-24

- Added Google login cloud sync for desktop and Android using Google Drive app data.
- Added auto-sync after sign-in, startup sync, manual resync, and clearer Google sync status.
- Improved delete sync so removed clipboard items do not return from Drive or WebSocket history.
- Batched desktop Google Drive updates after repeated deletes to avoid many rapid upload requests.
- Added retry, longer timeout, newest-file selection, and fallback file creation for Drive uploads.
- Improved desktop UI with cleaner history controls, item delete, clear history, and resync actions.
- Improved Android UI by moving connection/version/login into a menu and adding clipboard search.
- Added GitHub update manifest/download links for desktop and Android release builds.

## 2.1.0 - 2026-06-18

- Added hidden autostart, cleaner desktop UI, improved Android connection UX, and update checks.

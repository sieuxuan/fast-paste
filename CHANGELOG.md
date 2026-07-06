# Changelog

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

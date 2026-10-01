# FastPaste 2.2.9 — reliability follow-up

Implements the remaining small, useful fixes from the earlier audit while keeping the existing user flow and settings. Includes the unreleased 2.2.8 image fixes.

## Implemented

- Windows saves keep a complete primary file until a flushed temporary file replaces it. Backup-copy or replace failures preserve dirty state and trigger retries with a visible error/recovery status. Invalid settings JSON falls back to the backup.
- Desktop image transfers have a four-transfer limit, 128 MB aggregate buffer limit, a two-minute inactivity expiry, and cancellation on exhausted retry attempts. Reconnect requests pending latest images even if a history cursor excludes them.
- Android history queries exclude full base64 image bodies from the UI flow. Previews fetch a selected body on an IO dispatcher and use sampled decoding. Room version 7 adds indexes, and repository merges run in transactions.
- Android cache maintenance removes abandoned transfer directories by age/count/size while leaving the history database alone. Malformed downloaded payloads reset staging so retries can start again.
- Desktop checks Drive every two minutes when idle, even if local history did not change. Blob listings use all pages. A schema-3 checkpoint records the verified blob encryption mode only after all existing app blobs have been migrated in place, including duplicate filenames. Interrupted migration can resume; no cloud image files are deleted.
- Rich text retains its inline HTML body through manifest normalization and uses an inline LAN message rather than a zero-size image offer.
- Desktop authentication generations prevent old login or refresh responses from recreating tokens after sign-out; stale sync completions do not change the signed-out UI.
- Release jobs run tests/lint before producing Windows installers and the Android APK signed with the existing repository signing secrets. The update manifest is published after release assets are available.

## Verification scope

Regression tests cover failed-save retry state, actual Windows file locking/atomic replacement/backup recovery, transfer quotas and stale buffers, remote polling deadlines, canonical/legacy image hashes, image-body hydration, malformed bounds, sampled decoding, cache cleanup, inline HTML readiness, encryption checkpoint detection, and rejection of obsolete token writes.

Build and release results are recorded in the task response and GitHub Actions. No phone is connected to ADB, so real-device notification lifecycle, two-way copy/paste across applications and OEM background restrictions still need device testing. OAuth and encryption migration are not exercised against a real Drive account locally.

Local verification before release: 57 Rust tests and 17 Android tests pass; Android debug assembly and lint complete (0 errors, 44 warnings). JavaScript syntax and Git diff whitespace checks pass. Clippy completes with the two pre-existing constant-size `chunks_exact` suggestions.

## Further work requiring a separate design

- Consistent revisions/tombstones for edits, pinning, folders, deletion and undo across both devices.
- Concurrent Drive manifest write conflicts and cloud garbage collection with a grace period.
- Optional storage quotas or expiry for saved images, with recovery/export. This release only cleans temporary transfer data and does not delete saved images to meet a quota.
- Profiling and actual-device integration tests before adding more features.

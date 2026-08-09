# FastPaste — Plan 2: sự kiện UI, trạng thái ghép đôi, và UX

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ngừng bơm toàn bộ lịch sử kèm ảnh qua mỗi sự kiện UI, và biến mọi thất bại đang diễn ra âm thầm — đặc biệt là ghép đôi — thành thứ người dùng nhìn thấy và xử lý được.

**Architecture:** Ba nhánh. (1) `update_state` khổng lồ tách thành ba sự kiện hẹp, có coalesce, và bỏ dữ liệu nhị phân ra khỏi payload — thumbnail chuyển sang tải lười theo mục đang hiển thị. (2) Ghép đôi chuyển từ fail-closed-im-lặng sang fail-visible: desktop chủ động báo `pair_required`, Android tách trạng thái "đã nối nhưng chưa ghép đôi" khỏi "đã nối và an toàn". (3) Dọn hai anti-pattern UX đang có: dùng kênh lỗi để truyền thông báo tiến độ, và chuỗi tiếng Việt từ backend hiển thị thẳng lên UI đã có i18n.

**Tech Stack:** Rust + Tauri v2, HTML/JS thuần (không bundler), Kotlin + Jetpack Compose.

## Global Constraints

- **Plan 1 (`2026-08-09-fastpaste-critical-path.md`) phải hoàn tất trước.** Plan này giả định `save_state()` không tham số, `watcher::latest_payload()` tồn tại, và `ClipboardPayload` không còn field `files`.
- Frontend là **một file HTML thuần**, không bundler, không framework. `withGlobalTauri: true` nên `window.__TAURI__` có sẵn toàn cục.
- Frontend đã có sẵn: bảng dịch `vi`/`en` (`index.html:1635`, `:1781`), 64 điểm neo `data-i18n`, cache render theo signature (`historyViewSignature`), và phân trang DOM qua `historyVisibleLimit`. **Không viết lại các cơ chế này** — chỉ cấp cho chúng dữ liệu nhỏ hơn.
- Mọi chuỗi mới hiển thị cho người dùng phải đi qua lớp i18n, không hardcode.
- Sau mỗi task, `cargo test` (trong `src-tauri/`) và `./gradlew testDebugUnitTest` (trong `android/`) phải xanh.

## File Structure

| File | Trách nhiệm sau plan này |
|---|---|
| `src-tauri/src/state.rs` | Ba hàm phát sự kiện hẹp thay cho `broadcast_state` đơn khối; coalesce theo thời gian |
| `src-tauri/src/status.rs` *(mới)* | `StatusMessage { code, text }` — mã ổn định để frontend dịch, kèm text tiếng Việt làm dự phòng |
| `src-tauri/src/lib.rs` | Command `get_history_thumbnail`; `copy_history_item` trả kết quả có cấu trúc thay vì `Err` mang nghĩa thông tin |
| `src-tauri/src/network.rs` | Gửi `pair_required` cho peer chưa ghép đôi; phát sự kiện `update_transfers` thay vì full state trong đường transfer |
| `src/index.html` | Nghe ba sự kiện riêng; skeleton lúc đang load; thumbnail lười; dịch theo `code` |
| `android/.../WebSocketClient.kt` | `ConnectionState` thêm `CONNECTED_UNPAIRED` |
| `android/.../ui/screens/HomeScreen.kt` | Hiển thị đúng ba trạng thái, có lối vào ghép đôi |

---

## Task 1: Bỏ dữ liệu nhị phân khỏi sự kiện UI

`broadcast_state` hiện clone toàn bộ state rồi emit cả 1000 mục. `sanitized_for_ui()` đã xoá `data`, nhưng **`thumbnail` thì không** — mà mỗi thumbnail tới 384KB (`MAX_THUMBNAIL_CHARS`). Frontend còn phải chạy `textFingerprint` trên từng thumbnail đó mỗi lần emit, trong `historyDataSignature` (`index.html:2322`).

**Files:**
- Modify: `src-tauri/src/clipboard.rs` (`sanitized_for_ui`)
- Modify: `src-tauri/src/lib.rs` (command mới)
- Modify: `src/index.html:2322`, `:2454-2546`

**Interfaces:**
- Produces:
  - `ClipboardPayload::sanitized_for_ui()` — xoá cả `data` lẫn `thumbnail`, thêm `hasThumbnail: bool`
  - IPC `get_history_thumbnail(id: String) -> Result<String, String>` — trả data-URI thumbnail của một mục

- [ ] **Step 1: Viết test (sẽ fail)**

Thêm vào `mod tests` trong `src-tauri/src/clipboard.rs`:

```rust
    #[test]
    fn ui_payload_carries_no_binary_data() {
        let payload = ClipboardPayload {
            kind: "image".to_string(),
            text: "[Hình ảnh]".to_string(),
            mime_type: "image/png".to_string(),
            data: STANDARD.encode(vec![9u8; 4096]),
            thumbnail: "data:image/png;base64,AAAA".to_string(),
            ..ClipboardPayload::default()
        };

        let ui = payload.sanitized_for_ui();

        assert!(ui.data.is_empty(), "data không được đi kèm sự kiện UI");
        assert!(ui.thumbnail.is_empty(), "thumbnail phải tải lười");
        assert!(ui.has_thumbnail, "nhưng UI vẫn phải biết là có thumbnail");
        assert_eq!(ui.text, "[Hình ảnh]");
        assert_eq!(ui.kind, "image");
    }

    #[test]
    fn ui_payload_without_thumbnail_says_so() {
        let payload = ClipboardPayload {
            kind: "text".to_string(),
            text: "xin chào".to_string(),
            ..ClipboardPayload::default()
        };
        assert!(!payload.sanitized_for_ui().has_thumbnail);
    }
```

- [ ] **Step 2: Chạy test để xác nhận fail**

```bash
cd src-tauri && cargo test --lib ui_payload 2>&1 | tail -12
```

Expected: FAIL — `no field has_thumbnail on type ClipboardPayload`.

- [ ] **Step 3: Implement**

Trong `src-tauri/src/clipboard.rs`, thêm field vào `ClipboardPayload`:

```rust
    /// Chỉ dùng cho sự kiện UI: cho frontend biết có thumbnail để tải lười,
    /// mà không phải gửi kèm 384KB base64 trong mỗi lần phát state.
    #[serde(default, rename = "hasThumbnail", skip_deserializing)]
    pub(crate) has_thumbnail: bool,
```

```rust
    pub(crate) fn sanitized_for_ui(&self) -> Self {
        let mut payload = self.sanitized_for_cloud();
        payload.has_thumbnail = !payload.thumbnail.is_empty();
        payload.thumbnail.clear();
        payload
    }

    /// Bỏ dữ liệu nhị phân nhưng GIỮ thumbnail. Manifest trên Drive cần
    /// thumbnail để máy khác xem trước được mà không phải tải blob.
    pub(crate) fn sanitized_for_cloud(&self) -> Self {
        let mut payload = self.clone();
        payload.data.clear();
        payload
    }
```

Vì `has_thumbnail` có `skip_deserializing`, nó không bao giờ đọc từ vault hay từ dây, chỉ sinh ra ở đường ra UI. `fingerprint()` không đụng tới nó nên danh tính clipboard không đổi.

**Quan trọng — `manifest_entries` phải đổi cùng lúc.** `cloud.rs:577` đang gọi `sanitized_for_ui()` để rút gọn entry trước khi upload lên Drive. Nếu chỉ sửa `sanitized_for_ui` mà không sửa chỗ này thì thumbnail sẽ **biến mất khỏi bản sync Drive**, và máy khác mất khả năng xem trước. Sửa `src-tauri/src/cloud.rs:577`:

```rust
                entry.payload = Some(payload.sanitized_for_cloud());
```

Rồi kiểm tra không còn chỗ nào khác dùng nhầm:

```bash
cd src-tauri && grep -rn "sanitized_for_ui" src/
```

Chỉ được xuất hiện trong `clipboard.rs` (định nghĩa) và `state.rs` (đường phát sự kiện UI).

- [ ] **Step 4: Chạy test để xác nhận xanh**

```bash
cd src-tauri && cargo test --lib 2>&1 | tail -12
```

Expected: PASS.

- [ ] **Step 5: Thêm command tải thumbnail lười**

Trong `src-tauri/src/lib.rs`, cạnh `get_history_image_preview`:

```rust
#[tauri::command]
fn get_history_thumbnail(id: String, state: State<'_, AppState>) -> Result<String, String> {
    let data = state.0.lock().unwrap();
    data.history
        .iter()
        .find(|item| item.id == id)
        .and_then(|item| item.payload.as_ref())
        .map(|payload| payload.thumbnail.clone())
        .filter(|thumbnail| !thumbnail.is_empty())
        .ok_or_else(|| "Mục này không có ảnh xem trước.".to_string())
}
```

Đăng ký trong `tauri::generate_handler![...]`, ngay sau `get_history_image_preview`.

- [ ] **Step 6: Frontend tải thumbnail lười**

Trong `src/index.html`, sửa `historyDataSignature` (dòng 2322) — không còn thumbnail để băm:

```javascript
        item.payload?.hasThumbnail ? 1 : 0,
```

Tìm `payloadThumbnail(item)` và đổi sang render một ô giữ chỗ, rồi nạp ảnh sau khi DOM đã dựng:

```javascript
    function payloadThumbnail(item) {
      if (!item.payload?.hasThumbnail) return '';
      return `<div class="hthumb" data-thumb-id="${escapeHtml(item.id)}"></div>`;
    }

    // Thumbnail tải theo mục đang hiển thị. Gửi kèm chúng trong mỗi lần phát
    // state đồng nghĩa với việc bơm tới 384KB base64 cho từng mục, cho cả
    // những mục người dùng không bao giờ cuộn tới.
    const thumbnailCache = new Map();

    async function hydrateVisibleThumbnails() {
      const slots = els.historyContainer.querySelectorAll('[data-thumb-id]:empty');
      for (const slot of slots) {
        const id = slot.dataset.thumbId;
        if (thumbnailCache.has(id)) {
          if (thumbnailCache.get(id)) slot.innerHTML = `<img src="${thumbnailCache.get(id)}" alt="">`;
          continue;
        }
        try {
          const uri = await invoke('get_history_thumbnail', { id });
          thumbnailCache.set(id, uri);
          slot.innerHTML = `<img src="${uri}" alt="">`;
        } catch (error) {
          thumbnailCache.set(id, '');
        }
      }
    }
```

Gọi `hydrateVisibleThumbnails()` ở cuối `renderHistory`, sau khối `els.historyContainer.innerHTML = ...`, ngay trước phần khôi phục `scrollTop`.

- [ ] **Step 7: Build và kiểm tra bằng tay**

```bash
cd src-tauri && cargo build && cargo test --lib
```

Chạy app với lịch sử có nhiều ảnh: thumbnail vẫn hiện, cuộn danh sách vẫn mượt, và mở DevTools kiểm tra payload `update_state` — không còn chuỗi base64 dài trong đó.

- [ ] **Step 8: Commit**

```bash
git add src-tauri/src/clipboard.rs src-tauri/src/cloud.rs src-tauri/src/lib.rs src/index.html
git commit -m "perf: keep thumbnails out of the UI state event

sanitized_for_ui cleared data but left thumbnail, so every state emit
shipped up to 384KB of base64 per image entry — and the frontend then
fingerprinted each one to decide whether to re-render.

The event now carries a hasThumbnail flag; the frontend pulls the actual
image per visible row through get_history_thumbnail.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: Tách `update_state` thành ba sự kiện có coalesce

Trong lúc truyền blob, `broadcast_state` bị gọi hai lần cho mỗi chunk 48KB (`network.rs:520`, `:524`) — một ảnh 10MB sinh ~400 lần phát toàn bộ state.

**Files:**
- Modify: `src-tauri/src/state.rs`
- Modify: `src-tauri/src/network.rs:305-330`, `:447-460`, `:469-481`, `:487-525`

**Interfaces:**
- Produces:
  - `state::broadcast_state(app)` — giữ nguyên tên và ý nghĩa (phát tất cả), nhưng có coalesce
  - `state::broadcast_transfers(app)` — chỉ phát `update_transfers`
  - Sự kiện Tauri: `update_state` (như cũ, cho settings/history/devices) và `update_transfers` (mới)

- [ ] **Step 1: Viết test cho logic coalesce (sẽ fail)**

Logic thời gian phải tách thành hàm thuần để test được. Thêm vào `src-tauri/src/state.rs`:

```rust
#[cfg(test)]
mod coalesce_tests {
    use super::*;

    #[test]
    fn first_emit_always_passes() {
        assert!(should_emit(0, 1_000));
    }

    #[test]
    fn emits_inside_the_window_are_dropped() {
        assert!(!should_emit(1_000, 1_050));
    }

    #[test]
    fn emits_after_the_window_pass() {
        assert!(should_emit(1_000, 1_000 + BROADCAST_COALESCE_MS as i64));
    }
}
```

- [ ] **Step 2: Chạy test để xác nhận fail**

```bash
cd src-tauri && cargo test --lib coalesce 2>&1 | tail -12
```

Expected: FAIL — `cannot find function should_emit`.

- [ ] **Step 3: Implement coalesce**

Trong `src-tauri/src/state.rs`:

```rust
use std::sync::atomic::AtomicI64;

/// Cửa sổ gộp sự kiện UI. Đường truyền blob gọi phát state cho từng chunk
/// 48KB; không gộp thì một ảnh 10MB sinh hàng trăm lần dựng lại UI.
const BROADCAST_COALESCE_MS: u64 = 100;
static LAST_BROADCAST_AT: AtomicI64 = AtomicI64::new(0);

fn should_emit(last_at: i64, now: i64) -> bool {
    now - last_at >= BROADCAST_COALESCE_MS as i64
}

pub(crate) fn broadcast_state(app: &AppHandle) {
    let now = chrono::Utc::now().timestamp_millis();
    let last_at = LAST_BROADCAST_AT.load(Ordering::Acquire);
    if !should_emit(last_at, now) {
        return;
    }
    LAST_BROADCAST_AT.store(now, Ordering::Release);
    broadcast_state_now(app);
}

/// Bỏ qua coalesce. Dùng cho những thay đổi người dùng phải thấy ngay,
/// ví dụ kết quả một thao tác họ vừa bấm.
pub(crate) fn broadcast_state_now(app: &AppHandle) {
    let state = app.state::<AppState>();
    let mut data = state.0.lock().unwrap().clone();
    for item in &mut data.history {
        if let Some(payload) = &item.payload {
            item.payload = Some(payload.sanitized_for_ui());
        }
    }
    if let Some(backup) = &mut data.history_backup {
        for item in &mut backup.items {
            if let Some(payload) = &item.payload {
                item.payload = Some(payload.sanitized_for_ui());
            }
        }
    }
    LAST_BROADCAST_AT.store(chrono::Utc::now().timestamp_millis(), Ordering::Release);
    let _ = app.emit("update_state", data);
}

/// Chỉ tiến độ transfer. Đường truyền blob dùng cái này thay vì phát lại
/// toàn bộ lịch sử cho từng chunk.
pub(crate) fn broadcast_transfers(app: &AppHandle) {
    let state = app.state::<AppState>();
    let transfers = state.0.lock().unwrap().transfers.clone();
    let _ = app.emit("update_transfers", transfers);
}
```

- [ ] **Step 4: Chạy test để xác nhận xanh**

```bash
cd src-tauri && cargo test --lib coalesce 2>&1 | tail -10
```

Expected: PASS (3 test).

- [ ] **Step 5: Đổi đường transfer sang sự kiện hẹp**

Trong `src-tauri/src/network.rs`, thay `broadcast_state(&app)` bằng `broadcast_transfers(&app)` tại các vị trí **chỉ** đổi tiến độ transfer:

- dòng 323 (sau `update_transfer` trong nhánh binary chunk) — nhưng **giữ** `broadcast_state` nếu nhánh đó có `promote_or_insert_payload` thành công
- dòng 455 (sau `update_transfer` trong nhánh `blob_request`)
- dòng 479 (nhánh `blob_complete`)
- dòng 524 (lần gọi thứ hai trong nhánh `blob_chunk` — **xoá hẳn** dòng này, vì dòng 520 phía trên đã phát khi lịch sử thực sự đổi)

Cập nhật import ở đầu file:

```rust
use crate::state::{
    broadcast_state, broadcast_transfers, queue_cloud_sync, save_state, AppStateData,
    TransferUiState,
};
```

- [ ] **Step 6: Dùng `broadcast_state_now` cho thao tác người dùng vừa bấm**

Coalesce 100ms sẽ nuốt phản hồi tức thời của một cú bấm. Trong `src-tauri/src/lib.rs`, đổi `broadcast_state(&app)` thành `broadcast_state_now(&app)` trong các IPC command sau — đây là những chỗ người dùng vừa thao tác và đang chờ thấy kết quả:

`save_quick_slot_hotkey`, `save_autostart`, `save_always_on_top`, `set_app_excluded`, `set_e2ee_passphrase`, `set_e2ee_enabled`, `copy_history_item`, `update_history_item`, `toggle_history_pin`, `set_pinned_slot`, `delete_history_item`, `clear_history`, `delete_history_items`, `undo_history_delete`, `dismiss_history_backup`, `add_history_item`, `request_state`, `google_sign_in`, `google_sync_now`, `google_sign_out`.

Các chỗ gọi từ đường nền (poller clipboard, `network.rs`, loader thread) giữ `broadcast_state` để hưởng coalesce.

- [ ] **Step 7: Frontend nghe sự kiện mới**

Trong `src/index.html`, cạnh `listen('update_state', ...)` (dòng 3615):

```javascript
    listen('update_transfers', event => {
      if (state.data) state.data.transfers = event.payload || [];
      renderTransfers();
    });
```

- [ ] **Step 8: Build và kiểm tra**

```bash
cd src-tauri && cargo test --lib && cargo build
```

Kiểm tra bằng tay: copy một ảnh 5MB trên Android, xem thanh tiến độ trên PC chạy mượt và danh sách lịch sử không nhấp nháy.

- [ ] **Step 9: Commit**

```bash
git add src-tauri/src/state.rs src-tauri/src/network.rs src-tauri/src/lib.rs src/index.html
git commit -m "perf: coalesce UI events and give transfers their own channel

The blob path called broadcast_state twice per 48KB chunk, so a 10MB
image meant roughly 400 full-state emits. Progress now rides its own
update_transfers event, and background emits coalesce into a 100ms
window while direct user actions still emit immediately.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: Skeleton lúc đang load state

Plan 1 Task 7 làm cửa sổ mở ra với state rỗng. Nếu không có gì báo hiệu, người dùng có lịch sử lớn sẽ thấy danh sách trống và tưởng dữ liệu bị xoá.

**Files:**
- Modify: `src-tauri/src/lib.rs` (cờ đã-load-xong)
- Modify: `src/index.html`

**Interfaces:**
- Produces: sự kiện Tauri `state_loaded` (không payload), phát từ loader thread sau khi state thật đã vào bộ nhớ

- [ ] **Step 1: Phát sự kiện khi load xong**

Trong `src-tauri/src/lib.rs`, trong loader thread (do Plan 1 Task 7 tạo), ngay sau `broadcast_state(&load_handle);` lần đầu:

```rust
                    let _ = load_handle.emit("state_loaded", ());
```

- [ ] **Step 2: Frontend hiện skeleton**

Trong `src/index.html`, thêm vào object `state` khởi tạo:

```javascript
      stateLoaded: false,
```

Trong `renderHistory`, thay khối rỗng (dòng 2486-2489):

```javascript
      if (!allItems.length) {
        els.historyContainer.innerHTML = state.stateLoaded
          ? `<div class="empty"><div><strong>${escapeHtml(t('noClipboardTitle'))}</strong><span>${escapeHtml(t('noClipboardDesc'))}</span></div></div>`
          : `<div class="history-skeleton" aria-busy="true" aria-live="polite">
               <span class="sr-only">${escapeHtml(t('loadingHistory'))}</span>
               ${'<div class="skeleton-row"></div>'.repeat(5)}
             </div>`;
        return;
      }
```

Thêm listener cạnh các listener khác:

```javascript
    listen('state_loaded', () => {
      state.stateLoaded = true;
      render({ forceHistory: true });
    });
```

- [ ] **Step 3: Thêm chuỗi dịch**

Trong bảng `vi` (`index.html:1635`):

```javascript
      loadingHistory: 'Đang tải lịch sử clipboard…',
```

Trong bảng `en` (`index.html:1781`):

```javascript
      loadingHistory: 'Loading clipboard history…',
```

- [ ] **Step 4: Thêm CSS**

Cạnh các quy tắc `.empty` trong khối `<style>`:

```css
    .history-skeleton { display: flex; flex-direction: column; gap: 8px; padding: 12px; }
    .skeleton-row { height: 56px; border-radius: 10px; background: var(--surface-2, #2a2a30); animation: fpSkeleton 1.2s ease-in-out infinite; }
    .skeleton-row:nth-child(3) { animation-delay: .1s; }
    .skeleton-row:nth-child(4) { animation-delay: .2s; }
    .skeleton-row:nth-child(5) { animation-delay: .3s; }
    @keyframes fpSkeleton { 0%, 100% { opacity: .35; } 50% { opacity: .7; } }
    .sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
    @media (prefers-reduced-motion: reduce) { .skeleton-row { animation: none; opacity: .5; } }
```

- [ ] **Step 5: Kiểm tra bằng tay**

```bash
cd src-tauri && cargo build --release
```

Chạy `.exe` với lịch sử lớn: phải thấy skeleton trước, rồi lịch sử thật thay vào. Với lịch sử trống thật thì sau khi `state_loaded` phải hiện đúng thông báo "chưa có clipboard", không kẹt ở skeleton.

- [ ] **Step 6: Commit**

```bash
git add src-tauri/src/lib.rs src/index.html
git commit -m "feat: show a loading skeleton until state finishes loading

Background state loading means the window opens on an empty list. Without
a signal, a user with a large history sees what looks like wiped data.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: Ngừng dùng kênh lỗi để truyền thông báo tiến độ

`copy_history_item` trả `Err("Đang tải ảnh/tệp … MB; …")` (`lib.rs:289-292`) cho một trường hợp **thành công**. Frontend phải đoán ngược lại bằng cách so chuỗi: `showToast(message, !message.includes('Đang tải'))` (`index.html:2445`). Cách này hỏng ngay khi đổi câu chữ, và hỏng hoàn toàn ở chế độ tiếng Anh.

**Files:**
- Create: `src-tauri/src/status.rs`
- Modify: `src-tauri/src/lib.rs:244-324`
- Modify: `src/index.html:2437-2450`, `:2700-2712`

**Interfaces:**
- Produces:
  - `status::StatusMessage { code: String, text: String, tone: String }` — `tone` là `"ok"` | `"info"` | `"error"`
  - `status::info(code, text)`, `status::ok(code, text)` — hàm dựng
  - IPC `copy_history_item(id) -> Result<StatusMessage, StatusMessage>`

- [ ] **Step 1: Tạo `status.rs`**

```rust
use serde::Serialize;

/// Thông điệp gửi lên UI. `code` là định danh ổn định để frontend tra bảng
/// dịch; `text` là bản tiếng Việt dự phòng khi frontend chưa biết mã đó.
/// Trước đây backend chỉ gửi chuỗi tiếng Việt trần, nên UI phải so chuỗi
/// để đoán đó là lỗi hay thông báo, và người dùng tiếng Anh vẫn thấy tiếng Việt.
#[derive(Clone, Serialize)]
pub(crate) struct StatusMessage {
    pub(crate) code: String,
    pub(crate) text: String,
    pub(crate) tone: String,
}

pub(crate) fn ok(code: &str, text: impl Into<String>) -> StatusMessage {
    StatusMessage { code: code.into(), text: text.into(), tone: "ok".into() }
}

pub(crate) fn info(code: &str, text: impl Into<String>) -> StatusMessage {
    StatusMessage { code: code.into(), text: text.into(), tone: "info".into() }
}

pub(crate) fn error(code: &str, text: impl Into<String>) -> StatusMessage {
    StatusMessage { code: code.into(), text: text.into(), tone: "error".into() }
}
```

Khai báo `mod status;` trong `src-tauri/src/lib.rs`.

- [ ] **Step 2: Đổi `copy_history_item`**

Trong `src-tauri/src/lib.rs`, đổi chữ ký và ba đường trả về:

```rust
#[tauri::command]
async fn copy_history_item(
    id: String,
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<status::StatusMessage, status::StatusMessage> {
```

Đường "đang tải blob" (dòng 289-292) — đây là **tiến độ**, không phải lỗi, nên trả `Ok`:

```rust
        broadcast_state_now(&app);
        return Ok(status::info(
            "blobDownloading",
            format!(
                "Đang tải ảnh {:.1} MB; FastPaste sẽ xác thực rồi tự chép vào clipboard.",
                blob_size as f64 / 1_048_576.0
            ),
        ));
```

Đường không tìm thấy mục (dòng 296-297):

```rust
        let Some(index) = data.history.iter().position(|item| item.id == id) else {
            return Err(status::error("historyItemMissing", "Không tìm thấy mục clipboard."));
        };
```

Đường thành công (cuối hàm, dòng 323):

```rust
    Ok(status::ok("copySuccess", "Đã sao chép vào clipboard."))
```

Hai chỗ `.map_err(|error| error.to_string())?` bên trong đổi thành:

```rust
        crate::clipboard::write_clipboard(&payload)
            .map_err(|error| status::error("clipboardWriteFailed", error))?;
```

```rust
        app.clipboard()
            .write_text(text.clone())
            .map_err(|error| status::error("clipboardWriteFailed", error.to_string()))?;
```

- [ ] **Step 3: Frontend dịch theo `code`**

Trong `src/index.html`, thêm helper cạnh `showToast`:

```javascript
    // Backend gửi { code, text, tone }. Ưu tiên bản dịch theo code; nếu chưa
    // có mã đó trong bảng dịch thì dùng text tiếng Việt backend gửi kèm.
    function showStatus(status) {
      if (!status) return;
      if (typeof status === 'string') {
        showToast(status, true);
        return;
      }
      const translated = translations[state.language]?.[status.code];
      showToast(translated || status.text || '', status.tone === 'error');
    }
```

Sửa `openImagePreview`-adjacent call site và `copy` handler. Thay `index.html:2445`:

```javascript
        showStatus(error);
```

và chỗ gọi `copy_history_item` (quanh dòng 2700-2712):

```javascript
      try {
        const status = await invoke('copy_history_item', { id: entry.id });
        showStatus(status);
      } catch (error) {
        showStatus(error);
      }
```

- [ ] **Step 4: Thêm chuỗi dịch**

Bảng `vi`:

```javascript
      blobDownloading: 'Đang tải ảnh; FastPaste sẽ tự chép vào clipboard khi xong.',
      historyItemMissing: 'Không tìm thấy mục clipboard.',
      clipboardWriteFailed: 'Không ghi được vào clipboard.',
```

Bảng `en`:

```javascript
      blobDownloading: 'Downloading image; FastPaste will copy it once verified.',
      historyItemMissing: 'Clipboard entry not found.',
      clipboardWriteFailed: 'Could not write to the clipboard.',
```

`copySuccess` đã có sẵn trong cả hai bảng.

- [ ] **Step 5: Bỏ chuỗi tiếng Việt hardcode cạnh lời gọi `t()`**

`index.html:2706` đang là `showToast(entry.payload?.kind === 'image' ? 'Đã sao chép ảnh vào clipboard' : t('copySuccess'))` — một nửa dịch, một nửa hardcode. Sau Step 3 chỗ này đã dùng `showStatus`, nên xoá nhánh hardcode đó.

- [ ] **Step 6: Build và kiểm tra**

```bash
cd src-tauri && cargo test --lib && cargo build
```

Kiểm tra bằng tay: đổi ngôn ngữ sang EN, bấm copy một mục ảnh chưa tải xong → toast phải là tiếng Anh và **không** có màu lỗi.

- [ ] **Step 7: Commit**

```bash
git add src-tauri/src/status.rs src-tauri/src/lib.rs src/index.html
git commit -m "fix: stop delivering progress messages through the error channel

copy_history_item returned Err for a successful blob download, so the
frontend had to substring-match Vietnamese text to decide whether to
paint a toast red — which never worked in English mode.

Backend now sends { code, text, tone } and the UI translates by code.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: Desktop báo cho thiết bị chưa ghép đôi

`network.rs:406-409` bỏ mọi message từ peer chưa ghép đôi, và `protect_for_client` trả `None` nên không gửi lại gì. Comment ở `network.rs:226-228` còn ghi ngược lại điều code làm.

**Files:**
- Modify: `src-tauri/src/network.rs:224-228`, `:400-410`
- Modify: `src-tauri/src/lib.rs` (sự kiện cho UI)
- Modify: `src/index.html`

**Interfaces:**
- Produces:
  - Message giao thức mới, plaintext, không kèm dữ liệu: `{"app":"fastpaste","type":"pair_required","version":2,"desktopId":"..."}`
  - Sự kiện Tauri `pairing_required` với payload `{ ip: String }`

- [ ] **Step 1: Gửi `pair_required` và sửa comment sai**

Trong `src-tauri/src/network.rs`, thay khối comment ở dòng 224-228:

```rust
    // Kết nối chưa ghép đôi không nhận được dữ liệu nào — nhưng phải được
    // BÁO là vì sao, chứ không im lặng. Trước đây peer chưa ghép đôi kết nối
    // thành công rồi ngồi im, nên Android hiện "Đã kết nối" trong khi không
    // có gì đồng bộ và không có lỗi nào để người dùng lần ra.
```

Thay khối `network.rs:406-409`:

```rust
        if session.lock().unwrap().is_none() {
            // Peer chưa ghép đôi chỉ được gửi message pairing/session ở trên.
            // Trả lời một lần để phía kia biết cần quét QR.
            if !pair_required_sent {
                pair_required_sent = true;
                let notice = serde_json::json!({
                    "app": "fastpaste",
                    "type": "pair_required",
                    "version": 2,
                    "desktopId": pairing::desktop_id(),
                })
                .to_string();
                let _ = direct_tx.send(DirectMessage::Plain(notice));
                let _ = app.emit("pairing_required", serde_json::json!({ "ip": ip.clone() }));
            }
            continue;
        }
```

Khai báo cờ cạnh `let mut registered = false;` (dòng 225):

```rust
    let mut pair_required_sent = false;
```

Thêm `use tauri::Emitter;` vào đầu `network.rs` nếu chưa có.

- [ ] **Step 2: Desktop hiện banner**

Trong `src/index.html`, thêm listener cạnh các listener khác:

```javascript
    listen('pairing_required', event => {
      const ip = event.payload?.ip || '';
      els.pairingBanner.hidden = false;
      els.pairingBannerText.textContent = t('pairingRequiredBanner', ip);
    });
```

Thêm markup ngay dưới header của panel Thiết bị:

```html
      <div id="pairing-banner" class="pairing-banner" role="status" aria-live="polite" hidden>
        <svg><use href="#i-phone"></use></svg>
        <span id="pairing-banner-text"></span>
        <button id="pairing-banner-action" class="btn" type="button" data-i18n="pairNow">Ghép đôi ngay</button>
      </div>
```

Đăng ký hai element trong object `els`, và nối nút vào luồng ghép đôi sẵn có trong `bindEvents()`:

```javascript
      els.pairingBannerAction.addEventListener('click', () => {
        els.pairingBanner.hidden = true;
        beginPairing();
      });
```

- [ ] **Step 3: Thêm chuỗi dịch**

Bảng `vi`:

```javascript
      pairingRequiredBanner: ip => `Thiết bị ${ip} đang chờ ghép đôi. Chưa ghép đôi thì không có dữ liệu nào được đồng bộ.`,
      pairNow: 'Ghép đôi ngay',
```

Bảng `en`:

```javascript
      pairingRequiredBanner: ip => `Device ${ip} is waiting to be paired. Nothing syncs until pairing completes.`,
      pairNow: 'Pair now',
```

Kiểm tra `t()` có hỗ trợ tham số hàm không (`t('moreHistory', remaining)` ở dòng 2538 cho thấy là có). Nếu `t` chỉ nhận chuỗi, dùng đúng khuôn mẫu mà `moreHistory` đang dùng.

- [ ] **Step 4: Build và kiểm tra**

```bash
cd src-tauri && cargo test --lib && cargo build
```

Kiểm tra bằng tay: xoá dữ liệu app Android (hoặc bấm "Quên" thiết bị trên PC), kết nối lại → banner phải hiện trên PC kèm IP đúng.

- [ ] **Step 5: Commit**

```bash
git add src-tauri/src/network.rs src/index.html
git commit -m "fix: tell unpaired devices why nothing is syncing

The server dropped every message from an unpaired peer and sent nothing
back, so an Android install that had not been paired connected fine and
then sat silent. The comment above that code claimed the opposite.

Unpaired peers now get one pair_required notice, and the desktop raises
a banner with a direct route into the QR flow.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 6: Android tách trạng thái "đã nối nhưng chưa ghép đôi"

`HomeScreen.kt:1687-1688` hiện `"Đã kết nối"` cho `ConnectionState.CONNECTED`, kể cả khi `secureChannel` chưa có session và không có gì đồng bộ được.

**Files:**
- Modify: `android/.../websocket/WebSocketClient.kt:14-16`, `:63-138`
- Modify: `android/.../service/ClipboardService.kt:177-196`
- Modify: `android/.../ui/screens/HomeScreen.kt:1685-1695`

**Interfaces:**
- Consumes: `SecureChannel.requiresPairing` (`SecureChannel.kt:40`), `SecureChannel.isSecure` (`:39`)
- Produces: `enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED_UNPAIRED, CONNECTED_SECURE }`

- [ ] **Step 1: Mở rộng enum**

Trong `android/.../websocket/WebSocketClient.kt`:

```kotlin
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,

    /** Socket đã mở nhưng chưa ghép đôi: không dữ liệu nào đi qua được. */
    CONNECTED_UNPAIRED,

    /** Đã ghép đôi và có session key: đồng bộ hoạt động. */
    CONNECTED_SECURE
}
```

- [ ] **Step 2: Phát trạng thái đúng**

Trong `onOpen` (`WebSocketClient.kt:76-88`), thay nhánh cuối:

```kotlin
                if (awaitingSecurity) {
                    _events.tryEmit(
                        if (secureChannel?.requiresPairing == true) {
                            "PC yêu cầu ghép đôi: mở Cài đặt và quét QR trên PC"
                        } else {
                            "Đang xác thực thiết bị và tạo session key mới"
                        }
                    )
                    _state.value = if (secureChannel?.requiresPairing == true) {
                        ConnectionState.CONNECTED_UNPAIRED
                    } else {
                        ConnectionState.CONNECTING
                    }
                } else {
                    _events.tryEmit("Kết nối chưa ghép đôi; chưa có dữ liệu nào được đồng bộ")
                    _state.value = ConnectionState.CONNECTED_UNPAIRED
                }
```

Trong `onMessage` (dòng 105-107):

```kotlin
                if (event.connected) {
                    _state.value = ConnectionState.CONNECTED_SECURE
                }
```

Xử lý `pair_required` từ Task 5 — thêm vào đầu `onMessage`, trước khi đưa cho `secureChannel`:

```kotlin
                if (runCatching { JSONObject(text).optString("type") }.getOrNull() == "pair_required") {
                    _events.tryEmit("PC chưa ghép đôi thiết bị này. Quét QR trên PC để bật đồng bộ.")
                    _state.value = ConnectionState.CONNECTED_UNPAIRED
                    return
                }
```

Thêm `import org.json.JSONObject`.

- [ ] **Step 3: Cập nhật `ClipboardService`**

Trong `android/.../service/ClipboardService.kt`, khối `client.state.collectLatest` (dòng 178-195):

```kotlin
                client.state.collectLatest { state ->
                    connectionState.value = state
                    val status = when (state) {
                        ConnectionState.CONNECTED_SECURE -> {
                            stopBackgroundDiscovery()
                            sendHistorySync(client)
                            Log.d(TAG, "Connected; exchanging clipboard history")
                            connectionEvents.tryEmit("Đã kết nối tới $host:$port")
                            "Đã kết nối tới $host"
                        }
                        ConnectionState.CONNECTED_UNPAIRED -> {
                            stopBackgroundDiscovery()
                            "Chưa ghép đôi với $host — quét QR trên PC"
                        }
                        ConnectionState.CONNECTING -> "Đang kết nối tới $host..."
                        ConnectionState.DISCONNECTED -> {
                            ensureBackgroundDiscovery()
                            "Đã ngắt kết nối, đang thử lại..."
                        }
                    }
                    updateNotification(status)
                }
```

- [ ] **Step 4: Sửa các so sánh `== ConnectionState.CONNECTED` còn lại**

```bash
cd android && grep -rn "ConnectionState.CONNECTED\b" app/src/
```

Mỗi chỗ tìm được phải quyết định rõ nó muốn nói "socket mở" hay "đồng bộ chạy được":
- `MainViewModel.kt:164` (`== ConnectionState.DISCONNECTED`) giữ nguyên
- `ClipboardService.kt:228` (`!= ConnectionState.CONNECTED`) đổi thành `!= ConnectionState.CONNECTED_SECURE`
- `HomeScreen.kt:710` (`== ConnectionState.CONNECTED`) đổi thành `== ConnectionState.CONNECTED_SECURE`

- [ ] **Step 5: Cập nhật UI**

Trong `android/.../ui/screens/HomeScreen.kt`, khối `when` quanh dòng 1685:

```kotlin
        ConnectionState.CONNECTED_SECURE -> ConnectionUi(
            title = "Đã kết nối",
            ...
        )
        ConnectionState.CONNECTED_UNPAIRED -> ConnectionUi(
            title = "Chưa ghép đôi",
            subtitle = "Đã tìm thấy PC nhưng chưa có khoá riêng. Mở FastPaste trên PC, bấm Ghép đôi, rồi quét QR — chưa ghép đôi thì không có clipboard nào được đồng bộ.",
            ...
        )
```

Giữ nguyên các tham số khác của `ConnectionUi` theo đúng khuôn mẫu các nhánh sẵn có trong file. Trạng thái `CONNECTED_UNPAIRED` phải dùng màu cảnh báo, không dùng màu thành công.

- [ ] **Step 6: Build và test**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL. Nếu `when` báo thiếu nhánh, đó chính là mục đích — trình biên dịch đang chỉ ra mọi chỗ từng gộp hai trạng thái làm một.

- [ ] **Step 7: Kiểm tra bằng tay**

Xoá dữ liệu app Android, mở lại: phải hiện "Chưa ghép đôi" với hướng dẫn, **không** hiện "Đã kết nối". Sau khi quét QR trên PC thì chuyển sang "Đã kết nối" và clipboard đồng bộ được.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/fastpaste/app/websocket/WebSocketClient.kt \
        android/app/src/main/java/com/fastpaste/app/service/ClipboardService.kt \
        android/app/src/main/java/com/fastpaste/app/ui/screens/HomeScreen.kt
git commit -m "fix: distinguish connected-but-unpaired from connected-and-syncing

CONNECTED covered both cases, so HomeScreen showed 'Đã kết nối' to users
whose device had never been paired and whose clipboard was not syncing
at all. Splitting the enum makes the compiler point at every place that
conflated the two.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 7: Chuỗi trạng thái cloud không còn điều khiển luồng

`state.rs:271-280` quyết định logic bằng `status.contains("chưa được bật")` — so khớp chuỗi tiếng Việt để điều khiển chương trình. Đổi câu chữ là hỏng, và người dùng tiếng Anh vẫn thấy tiếng Việt.

**Files:**
- Modify: `src-tauri/src/cloud.rs` (`CloudUiState`)
- Modify: `src-tauri/src/state.rs:263-283`
- Modify: `src/index.html` (`renderCloudSettings`)

**Interfaces:**
- Produces: `CloudUiState` thêm field `status_code: String`, giá trị: `"notConfigured"` | `"signedInIdle"` | `"needsSignIn"` | `"syncing"` | `"syncError"` | `"synced"`

- [ ] **Step 1: Viết test (sẽ fail)**

Thêm vào `src-tauri/src/state.rs`:

```rust
#[cfg(test)]
mod cloud_status_tests {
    use super::*;

    #[test]
    fn unconfigured_build_reports_a_stable_code() {
        let mut cloud = cloud::CloudUiState::default();
        cloud.configured = false;
        apply_cloud_status(&mut cloud);
        assert_eq!(cloud.status_code, "notConfigured");
    }

    #[test]
    fn signed_out_asks_for_sign_in_regardless_of_previous_text() {
        let mut cloud = cloud::CloudUiState::default();
        cloud.configured = true;
        cloud.signed_in = false;
        cloud.status = "một câu bất kỳ do lần chạy trước để lại".into();
        apply_cloud_status(&mut cloud);
        assert_eq!(cloud.status_code, "needsSignIn");
    }

    #[test]
    fn signed_in_idle_does_not_clobber_a_fresh_sync_result() {
        let mut cloud = cloud::CloudUiState::default();
        cloud.configured = true;
        cloud.signed_in = true;
        cloud.status_code = "synced".into();
        cloud.status = "Đã đồng bộ 12 mục.".into();
        apply_cloud_status(&mut cloud);
        assert_eq!(cloud.status_code, "synced", "kết quả sync vừa xong phải được giữ");
        assert_eq!(cloud.status, "Đã đồng bộ 12 mục.");
    }
}
```

- [ ] **Step 2: Chạy test để xác nhận fail**

```bash
cd src-tauri && cargo test --lib cloud_status 2>&1 | tail -12
```

Expected: FAIL — `no field status_code`, `cannot find function apply_cloud_status`.

- [ ] **Step 3: Implement**

Trong `src-tauri/src/cloud.rs`, thêm vào `CloudUiState`:

```rust
    #[serde(default, rename = "statusCode", alias = "status_code")]
    pub status_code: String,
```

Trong `src-tauri/src/state.rs`, thay `refresh_cloud_state` bằng hai hàm — một lấy trạng thái đăng nhập, một quyết định thông điệp:

```rust
pub(crate) fn refresh_cloud_state(cloud_state: &mut cloud::CloudUiState) {
    cloud_state.configured = cloud::is_configured();
    cloud_state.signed_in = cloud::is_signed_in();
    cloud_state.account_email = cloud::signed_in_email();
    apply_cloud_status(cloud_state);
}

/// Quyết định mã trạng thái từ dữ liệu thật. Trước đây hàm này so khớp
/// chuỗi tiếng Việt trong `status` để đoán mình đang ở trạng thái nào —
/// đổi câu chữ là hỏng logic.
fn apply_cloud_status(cloud_state: &mut cloud::CloudUiState) {
    const RESULT_CODES: [&str; 3] = ["synced", "syncError", "syncing"];

    if !cloud_state.configured {
        cloud_state.status_code = "notConfigured".into();
        cloud_state.status = "Chưa bật đồng bộ Google trong bản build này.".into();
        return;
    }
    // Kết quả của một lần sync vừa xong là thông tin cụ thể hơn trạng thái
    // nhàn rỗi, nên không ghi đè lên nó.
    if RESULT_CODES.contains(&cloud_state.status_code.as_str()) {
        return;
    }
    if cloud_state.signed_in {
        cloud_state.status_code = "signedInIdle".into();
        cloud_state.status = "Tự đồng bộ Google Drive đang bật.".into();
    } else {
        cloud_state.status_code = "needsSignIn".into();
        cloud_state.status = "Đăng nhập Google để bật tự đồng bộ.".into();
    }
}
```

- [ ] **Step 4: Đặt `status_code` ở mọi chỗ đang đặt `status`**

Trong `src-tauri/src/lib.rs`, mỗi chỗ gán `data.cloud.status = ...` phải gán kèm mã. Ví dụ trong `sync_google_drive`:

```rust
                data.cloud.status_code = "synced".into();
                data.cloud.status = format!(
                    "Tự đồng bộ Google Drive: {} mục, tải về {} mục mới.",
                    result.merged_count, inserted
                );
```

```rust
            data.cloud.status_code = "syncError".into();
            data.cloud.status = format!("Đồng bộ Google lỗi: {error}");
```

```rust
        data.cloud.status_code = "syncing".into();
        data.cloud.status = "Đang đồng bộ Google Drive...".to_string();
```

Tìm hết các chỗ còn lại:

```bash
cd src-tauri && grep -rn "cloud.status = " src/
```

Mỗi kết quả phải có một dòng `status_code` đi kèm ngay trên nó. Các thông báo "đã sửa/xoá mục, Drive sẽ cập nhật sau vài giây" dùng mã `"syncing"`.

- [ ] **Step 5: Chạy test để xác nhận xanh**

```bash
cd src-tauri && cargo test --lib cloud_status 2>&1 | tail -10
```

Expected: PASS (3 test).

- [ ] **Step 6: Frontend dịch theo mã**

Trong `src/index.html`, `renderCloudSettings` (dòng 2568-2573):

```javascript
      const statusText = translations[state.language]?.[cloud.statusCode] || cloud.status || '';
```

Thêm chuỗi vào bảng `vi`:

```javascript
      notConfigured: 'Chưa bật đồng bộ Google trong bản build này.',
      needsSignIn: 'Đăng nhập Google để bật tự đồng bộ.',
      signedInIdle: 'Tự đồng bộ Google Drive đang bật.',
      syncing: 'Đang đồng bộ Google Drive…',
```

Bảng `en`:

```javascript
      notConfigured: 'Google sync is not enabled in this build.',
      needsSignIn: 'Sign in with Google to enable auto-sync.',
      signedInIdle: 'Google Drive auto-sync is on.',
      syncing: 'Syncing with Google Drive…',
```

`synced` và `syncError` mang số liệu cụ thể nên để rơi về `cloud.status` — không thêm vào bảng dịch.

- [ ] **Step 7: Build và kiểm tra**

```bash
cd src-tauri && cargo test --lib && cargo build
```

Kiểm tra bằng tay: đổi ngôn ngữ sang EN, mở panel Thiết bị → trạng thái cloud ở trạng thái nhàn rỗi phải là tiếng Anh.

- [ ] **Step 8: Commit**

```bash
git add src-tauri/src/cloud.rs src-tauri/src/state.rs src-tauri/src/lib.rs src/index.html
git commit -m "refactor: drive cloud status from a code, not Vietnamese substrings

refresh_cloud_state decided which state it was in by calling
status.contains(\"chưa được bật\") — rewording the message silently broke
the logic, and English users saw Vietnamese either way.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Self-review

**Spec coverage (GĐ 3, GĐ 5, và UX):**

| Yêu cầu | Task |
|---|---|
| §5.4 bỏ dữ liệu nhị phân khỏi sự kiện UI | Task 1 |
| §5.4 thumbnail tải lười | Task 1 (step 5-6) |
| §5.4 gộp `broadcast_state` trong đường transfer | Task 2 |
| §5.4 sự kiện `update_transfers` riêng | Task 2 |
| §5.6 desktop báo `pair_required` + banner | Task 5 |
| §5.6 sửa comment sai ở `network.rs:226` | Task 5 (step 1) |
| §5.6 Android ba trạng thái kết nối | Task 6 |
| UX: skeleton lúc load (hệ quả của Plan 1 Task 7) | Task 3 |
| UX: ngừng dùng kênh lỗi cho thông báo tiến độ | Task 4 |
| UX: i18n cho chuỗi từ backend | Task 4 + Task 7 |

**Chỉnh so với spec:** §5.4 viết *"frontend `render()` dựng lại toàn bộ, không diff, không virtualize"*. Đọc kỹ `index.html` thì **không đúng** — frontend đã có cache theo `historyViewSignature` (`:2478`) và đã phân trang DOM qua `historyVisibleLimit` (`:2465`). Nút thắt thật là kích thước payload IPC và việc `historyDataSignature` phải băm thumbnail của cả 1000 mục mỗi lần phát. Plan này vì thế **không** viết lại lớp render — chỉ thu nhỏ dữ liệu đi vào nó.

**Chưa làm trong plan này, chuyển sang Plan 3:** chunk PC→Android hai chiều và `ws_tx` dùng `Arc` thay `String` (§5.5).

**Type consistency:** `StatusMessage { code, text, tone }` do Task 4 định nghĩa, dùng trong `copy_history_item`. `showStatus()` (Task 4) dùng lại ở Task 5. `CONNECTED_SECURE` / `CONNECTED_UNPAIRED` (Task 6) thay mọi `ConnectionState.CONNECTED`. `broadcast_state_now` (Task 2) dùng ở Task 4. `status_code` (Task 7) đọc dưới tên `cloud.statusCode` ở frontend, khớp `rename` trong serde.

# FastPaste — Plan 3: cloud sync, dọn Android, và tài liệu

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Đưa đồng bộ Google Drive về đúng độ trễ đã hứa, gỡ race condition và cấu trúc chồng chéo còn lại trên Android, và cập nhật tài liệu cho khớp kiến trúc thật.

**Architecture:** Bốn nhánh. (1) Debounce cloud đổi từ "khởi động lại mỗi khi có request" sang deadline cố định, và bỏ qua hẳn lần sync nếu không có gì đổi. (2) Payload lớn PC→Android chuyển sang dùng chính đường chunk đã có, thay vì một frame khổng lồ. (3) Android: tách `lastSyncedFingerprint` theo chiều, gom quyền sở hữu `ServiceDiscovery` về một nơi, và tách hai file quá lớn. (4) `CLAUDE.md` viết lại cho khớp code.

**Tech Stack:** Rust + Tauri v2, tokio, reqwest; Kotlin + Jetpack Compose + Room + OkHttp.

## Global Constraints

- **Plan 1 và Plan 2 phải hoàn tất trước.** Plan này giả định `save_state()` không tham số, `broadcast_transfers` tồn tại, `sanitized_for_cloud()` tồn tại, và `ConnectionState` đã có `CONNECTED_SECURE` / `CONNECTED_UNPAIRED`.
- Không đổi định dạng file trên Google Drive. Người dùng có thể đang chạy nhiều máy ở phiên bản khác nhau, nên manifest phải giữ nguyên hình dạng.
- Không đổi schema Room nếu tránh được; nếu buộc phải đổi thì bump version kèm `Migration` tường minh.
- Sau mỗi task, `cargo test` (trong `src-tauri/`) và `./gradlew testDebugUnitTest` (trong `android/`) phải xanh.

## File Structure

| File | Trách nhiệm sau plan này |
|---|---|
| `src-tauri/src/lib.rs` | Vòng debounce cloud theo deadline; snapshot state không giữ lock lâu |
| `src-tauri/src/history.rs` | `history_revision()` — chữ ký nội dung để bỏ qua sync thừa |
| `src-tauri/src/network.rs` | Chào blob cho payload lớn PC→Android; `ws_tx` mang `Arc` |
| `android/.../service/ClipboardService.kt` | Chỉ còn vòng đời service, clipboard, và điều phối (~490 dòng) |
| `android/.../sync/BlobTransferManager.kt` *(mới)* | Toàn bộ chunk/ack/resume hai chiều |
| `android/.../sync/HistorySyncMerger.kt` *(mới)* | Dựng và trộn payload history |
| `android/.../ui/screens/*.kt` | `HomeScreen` tách theo section |
| `CLAUDE.md` | Khớp module, hằng số, và luồng ghép đôi thật |

---

## Task 1: Debounce cloud theo deadline cố định

`lib.rs:1046-1057` ngủ 3 giây rồi kiểm tra xem có request mới không; nếu có thì ngủ **thêm 3 giây nữa**. Mỗi lần copy đều tự gia hạn, nên copy liên tục thì Drive không bao giờ tới lượt.

**Files:**
- Modify: `src-tauri/src/lib.rs:1040-1079`

**Interfaces:**
- Produces: `next_sync_deadline(first_request_at: i64, now: i64) -> i64` — hàm thuần, testable

- [ ] **Step 1: Viết test (sẽ fail)**

Thêm vào cuối `src-tauri/src/lib.rs`:

```rust
#[cfg(test)]
mod cloud_debounce_tests {
    use super::*;

    #[test]
    fn deadline_is_fixed_from_the_first_request() {
        let first = 1_000i64;
        assert_eq!(next_sync_deadline(first, first), first + CLOUD_SYNC_DEBOUNCE_MS as i64);
    }

    #[test]
    fn later_requests_inside_the_window_do_not_extend_it() {
        let first = 1_000i64;
        let deadline = next_sync_deadline(first, first);
        // Một request nữa tới ở 2_500 — vẫn cùng cửa sổ, deadline không đổi.
        assert_eq!(next_sync_deadline(first, 2_500), deadline);
    }

    #[test]
    fn a_request_after_the_deadline_opens_a_new_window() {
        let first = 1_000i64;
        let late = first + CLOUD_SYNC_DEBOUNCE_MS as i64 + 1;
        assert_eq!(
            next_sync_deadline(late, late),
            late + CLOUD_SYNC_DEBOUNCE_MS as i64
        );
    }
}
```

- [ ] **Step 2: Chạy test để xác nhận fail**

```bash
cd src-tauri && cargo test --lib cloud_debounce 2>&1 | tail -12
```

Expected: FAIL — `cannot find function next_sync_deadline`.

- [ ] **Step 3: Implement**

Thêm cạnh `CLOUD_SYNC_DEBOUNCE_MS` trong `src-tauri/src/lib.rs`:

```rust
/// Deadline tính từ request ĐẦU TIÊN của cửa sổ, không phải request gần nhất.
/// Vòng debounce cũ ngủ lại trọn 3 giây mỗi khi có thêm request, nên chỉ cần
/// copy đều đặn là Drive sync bị đói vô hạn.
fn next_sync_deadline(first_request_at: i64, _now: i64) -> i64 {
    first_request_at + CLOUD_SYNC_DEBOUNCE_MS as i64
}
```

Thay vòng debounce (`lib.rs:1045-1079`):

```rust
            tauri::async_runtime::spawn(async move {
                while cloud_sync_rx.recv().await.is_some() {
                    let first_request_at = chrono::Utc::now().timestamp_millis();
                    let deadline = next_sync_deadline(first_request_at, first_request_at);

                    // Gom mọi request rơi vào cửa sổ này. Chúng KHÔNG đẩy
                    // deadline ra xa thêm.
                    loop {
                        let remaining = deadline - chrono::Utc::now().timestamp_millis();
                        if remaining <= 0 {
                            break;
                        }
                        let drained = tokio::time::timeout(
                            Duration::from_millis(remaining as u64),
                            cloud_sync_rx.recv(),
                        )
                        .await;
                        if matches!(drained, Ok(None)) {
                            return;
                        }
                    }

                    loop {
                        let (cloud_ready, syncing) = {
                            let data = data_cloud.lock().unwrap();
                            (
                                data.cloud.configured && data.cloud.signed_in,
                                data.cloud.syncing,
                            )
                        };

                        if !cloud_ready {
                            break;
                        }

                        if !syncing {
                            let _ = sync_google_drive(app_cloud.clone(), data_cloud.clone()).await;
                            break;
                        }

                        sleep(Duration::from_secs(1)).await;
                    }
                }
            });
```

- [ ] **Step 4: Chạy test để xác nhận xanh**

```bash
cd src-tauri && cargo test --lib cloud_debounce 2>&1 | tail -10
```

Expected: PASS (3 test).

- [ ] **Step 5: Kiểm tra bằng tay**

Chạy app đã đăng nhập Drive. Copy liên tục 20 chuỗi trong 15 giây. Trạng thái cloud phải chuyển sang "Đang đồng bộ" trong vòng ~3 giây kể từ chuỗi đầu tiên, chứ không đợi tới khi bạn ngừng copy.

- [ ] **Step 6: Commit**

```bash
git add src-tauri/src/lib.rs
git commit -m "fix: give cloud sync a fixed debounce deadline

The loop slept the full 3s window again on every incoming request, so a
steady stream of copies pushed the deadline out indefinitely and Drive
sync never ran. The deadline is now anchored to the first request in the
window.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: Bỏ qua lần sync khi không có gì đổi

`sync_google_drive` luôn clone toàn bộ history thành `CloudEntry` (kèm base64), tải file remote về, rồi mới phát hiện là không có gì đổi (`cloud.rs:236` so `merged_manifest != normalized_remote`). Round trip mạng và ba lần clone đó đều thừa.

**Files:**
- Modify: `src-tauri/src/history.rs`
- Modify: `src-tauri/src/lib.rs:871-953`

**Interfaces:**
- Produces: `history::history_revision(history: &[HistoryItem], markers: &[DeletedMarker], clear_at: Option<i64>) -> u64` — chữ ký rẻ, không đụng dữ liệu nhị phân

- [ ] **Step 1: Viết test (sẽ fail)**

Thêm vào `mod history_maintenance_tests` trong `src-tauri/src/history.rs`:

```rust
    #[test]
    fn revision_is_stable_for_unchanged_history() {
        let history = vec![make_history_item("một", "PC"), make_history_item("hai", "PC")];
        let first = history_revision(&history, &[], None);
        let second = history_revision(&history, &[], None);
        assert_eq!(first, second);
    }

    #[test]
    fn revision_changes_when_an_entry_is_added() {
        let history = vec![make_history_item("một", "PC")];
        let before = history_revision(&history, &[], None);
        let mut after_history = history.clone();
        after_history.push(make_history_item("hai", "PC"));
        assert_ne!(before, history_revision(&after_history, &[], None));
    }

    #[test]
    fn revision_ignores_binary_payload_bytes() {
        // Chỉ blob_id mới định danh nội dung nhị phân; base64 thô không được
        // kéo vào chữ ký, nếu không việc tính revision lại đắt đúng bằng thứ
        // ta đang cố tránh.
        let mut with_data = make_history_item("ảnh", "PC");
        with_data.blob_id = "abc".into();
        with_data.payload = Some(crate::clipboard::ClipboardPayload {
            kind: "image".into(),
            text: "ảnh".into(),
            data: "AAAAAAAA".into(),
            ..Default::default()
        });
        let mut without_data = with_data.clone();
        without_data.payload.as_mut().unwrap().data = "BBBBBBBB".into();

        assert_eq!(
            history_revision(&[with_data], &[], None),
            history_revision(&[without_data], &[], None)
        );
    }

    #[test]
    fn revision_changes_when_a_delete_marker_appears() {
        let history = vec![make_history_item("một", "PC")];
        let before = history_revision(&history, &[], None);
        let markers = vec![DeletedMarker {
            text_hash: "hash".into(),
            deleted_at: 42,
            include_pinned: true,
        }];
        assert_ne!(before, history_revision(&history, &markers, None));
    }
```

- [ ] **Step 2: Chạy test để xác nhận fail**

```bash
cd src-tauri && cargo test --lib revision 2>&1 | tail -12
```

Expected: FAIL — `cannot find function history_revision`.

- [ ] **Step 3: Implement**

Trong `src-tauri/src/history.rs`:

```rust
/// Chữ ký nội dung của những thứ ảnh hưởng tới bản trên Drive. Cố ý bỏ qua
/// base64 trong payload: blob_id đã định danh nội dung nhị phân rồi, và băm
/// hàng trăm MB mỗi lần chỉ để quyết định "có cần sync không" là tự chuốc lại
/// đúng cái chi phí đang muốn tránh.
pub(crate) fn history_revision(
    history: &[HistoryItem],
    markers: &[DeletedMarker],
    clear_at: Option<i64>,
) -> u64 {
    use std::hash::{Hash, Hasher};
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    for item in history {
        item.text.hash(&mut hasher);
        item.timestamp.hash(&mut hasher);
        item.source.hash(&mut hasher);
        item.folder.hash(&mut hasher);
        item.pinned.hash(&mut hasher);
        item.blob_id.hash(&mut hasher);
    }
    for marker in markers {
        marker.text_hash.hash(&mut hasher);
        marker.deleted_at.hash(&mut hasher);
        marker.include_pinned.hash(&mut hasher);
    }
    clear_at.hash(&mut hasher);
    hasher.finish()
}
```

- [ ] **Step 4: Chạy test để xác nhận xanh**

```bash
cd src-tauri && cargo test --lib revision 2>&1 | tail -10
```

Expected: PASS (4 test).

- [ ] **Step 5: Dùng revision để bỏ qua sync thừa**

Trong `src-tauri/src/lib.rs`, thêm cạnh các hằng:

```rust
use std::sync::atomic::{AtomicU64, Ordering};

/// Revision của lần đồng bộ Drive thành công gần nhất.
static LAST_SYNCED_REVISION: AtomicU64 = AtomicU64::new(0);
```

Trong `sync_google_drive`, bên trong khối lock đầu tiên, sau khi đã kiểm tra `configured` và `signed_in`, và **trước** khi gọi `history_to_cloud_entries`:

```rust
        let revision = history_revision(&data.history, &data.deleted_markers, data.clear_history_at);
        if revision == LAST_SYNCED_REVISION.load(Ordering::Acquire) && revision != 0 {
            // Không có gì đổi kể từ lần sync thành công trước. Bỏ qua cả round
            // trip mạng lẫn ba lần clone toàn bộ history.
            return Ok(());
        }
```

Nhớ mang `revision` ra khỏi khối lock — thêm nó vào tuple trả về của khối (`lib.rs:875`, `:902-914`):

```rust
    let (entries, deleted_markers, clear_history_at, e2ee_enabled, revision) = {
```

Trong nhánh `Ok(result)` của `sync_pruned`, sau khi cập nhật state thành công:

```rust
                LAST_SYNCED_REVISION.store(revision, Ordering::Release);
```

- [ ] **Step 6: Giảm thời gian giữ lock**

`history_to_cloud_entries(&data.history)` (`lib.rs:903`) clone toàn bộ payload base64 **trong lúc đang giữ mutex** — chặn poller clipboard và mọi task WS suốt thời gian đó. Vì `manifest_entries` sẽ vứt bỏ `data` ngay sau đó, việc clone nó là hoàn toàn thừa. Trong `src-tauri/src/history.rs`:

```rust
pub(crate) fn history_to_cloud_entries(history: &[HistoryItem]) -> Vec<cloud::CloudEntry> {
    history
        .iter()
        .map(|item| cloud::CloudEntry {
            text: item.text.clone(),
            timestamp: timestamp_to_millis(&item.timestamp),
            source: item.source.clone(),
            source_app: item.source_app.clone(),
            source_title: item.source_title.clone(),
            source_icon: item.source_icon.clone(),
            pinned: item.pinned,
            folder: item.folder.clone(),
            // Manifest không mang dữ liệu nhị phân — upload_missing_blobs lo
            // phần đó riêng. Clone base64 ở đây chỉ để vứt đi ngay sau, mà lại
            // clone trong lúc đang giữ mutex của state.
            payload: item.payload.as_ref().map(|payload| payload.sanitized_for_cloud()),
            blob_id: item.blob_id.clone(),
            blob_size: item.blob_size,
            blob_ready: item.blob_ready,
        })
        .collect()
}
```

**Lưu ý:** `upload_missing_blobs` (`cloud.rs:585`) cần dữ liệu nhị phân thật để upload. Kiểm tra xem nó lấy từ đâu:

```bash
cd src-tauri && sed -n '585,620p' src/cloud.rs
```

Nếu nó đọc `entry.payload.data` thì thay đổi trên sẽ phá chức năng upload blob. Trong trường hợp đó, **giữ nguyên `history_to_cloud_entries`** và chỉ áp dụng Step 5 (bỏ qua sync thừa) — ghi lại lý do vào commit message. Đừng đoán; hãy đọc rồi quyết.

- [ ] **Step 7: Build, test, kiểm tra**

```bash
cd src-tauri && cargo test --lib && cargo build
```

Kiểm tra bằng tay: bấm "Đồng bộ ngay" hai lần liên tiếp mà không copy gì ở giữa — lần thứ hai phải trả về tức thì. Sau đó copy một chuỗi rồi bấm lại: lần này phải thật sự sync.

- [ ] **Step 8: Commit**

```bash
git add src-tauri/src/history.rs src-tauri/src/lib.rs
git commit -m "perf: skip Drive sync when nothing changed since the last one

Every sync cloned the full history into CloudEntry, downloaded the remote
file, and only then discovered the manifest was identical. A cheap
content revision now short-circuits that before any of it happens.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: Chunk payload lớn theo cả hai chiều

Android→PC dùng `clipboard_blob_offer` + chunk. PC→Android thì nhét cả base64 vào một text frame qua `protocol_json()`. Một ảnh 10MB thành frame ~13MB, Android phải nhận và giải mã trọn gói.

**Files:**
- Modify: `src-tauri/src/lib.rs` (poller clipboard, do Plan 1 Task 5 tạo)
- Modify: `src-tauri/src/network.rs`

**Interfaces:**
- Produces: `network::outgoing_clipboard_message(payload: &ClipboardPayload) -> String` — trả text thô cho text, `clipboard_payload` cho payload nhỏ, `clipboard_blob_offer` cho payload lớn

- [ ] **Step 1: Viết test (sẽ fail)**

Thêm vào `src-tauri/src/network.rs`:

```rust
#[cfg(test)]
mod outgoing_tests {
    use super::*;
    use base64::{engine::general_purpose::STANDARD, Engine as _};

    #[test]
    fn text_goes_out_raw() {
        let payload = ClipboardPayload::text("xin chào".into());
        assert_eq!(outgoing_clipboard_message(&payload), "xin chào");
    }

    #[test]
    fn small_image_is_inlined() {
        let payload = ClipboardPayload {
            kind: "image".into(),
            text: "[Hình ảnh]".into(),
            data: STANDARD.encode(vec![1u8; 1024]),
            ..ClipboardPayload::default()
        };
        let wire: serde_json::Value =
            serde_json::from_str(&outgoing_clipboard_message(&payload)).unwrap();
        assert_eq!(wire["type"], "clipboard_payload");
    }

    #[test]
    fn large_image_is_offered_as_a_blob() {
        let payload = ClipboardPayload {
            kind: "image".into(),
            text: "[Hình ảnh lớn]".into(),
            data: STANDARD.encode(vec![1u8; BLOB_OFFER_THRESHOLD_BYTES + 1]),
            ..ClipboardPayload::default()
        };
        let wire: serde_json::Value =
            serde_json::from_str(&outgoing_clipboard_message(&payload)).unwrap();
        assert_eq!(wire["type"], "clipboard_blob_offer");
        assert_eq!(wire["blobId"], payload.fingerprint());
        assert_eq!(wire["text"], "[Hình ảnh lớn]");
        // Lời chào không được mang theo dữ liệu nhị phân.
        assert!(wire["payload"]["data"].as_str().unwrap_or_default().is_empty());
    }
}
```

- [ ] **Step 2: Chạy test để xác nhận fail**

```bash
cd src-tauri && cargo test --lib outgoing_ 2>&1 | tail -12
```

Expected: FAIL — `cannot find function outgoing_clipboard_message`.

- [ ] **Step 3: Implement**

Trong `src-tauri/src/network.rs`:

```rust
/// Trên ngưỡng này thì gửi lời chào blob thay vì nhét cả payload vào một
/// frame. Đường Android→PC đã chunk từ trước; chiều ngược lại thì chưa, nên
/// một ảnh 10MB đi ra thành một text frame ~13MB.
pub(crate) const BLOB_OFFER_THRESHOLD_BYTES: usize = 256 * 1024;

pub(crate) fn outgoing_clipboard_message(payload: &ClipboardPayload) -> String {
    if payload.kind == "text" {
        return payload.text.clone();
    }
    if payload.encoded_size() <= BLOB_OFFER_THRESHOLD_BYTES {
        return payload.protocol_json();
    }
    serde_json::json!({
        "app": "fastpaste",
        "type": "clipboard_blob_offer",
        "version": 2,
        "text": payload.text,
        "timestamp": chrono::Utc::now().timestamp_millis(),
        "source": "PC",
        "blobId": payload.fingerprint(),
        "blobSize": payload.encoded_size(),
        "payload": payload.sanitized_for_cloud(),
    })
    .to_string()
}
```

- [ ] **Step 4: Chạy test để xác nhận xanh**

```bash
cd src-tauri && cargo test --lib outgoing_ 2>&1 | tail -10
```

Expected: PASS (3 test).

- [ ] **Step 5: Dùng ở đường phát clipboard**

Trong `src-tauri/src/lib.rs`, trong watcher consumer (Plan 1 Task 5 Step 3), thay khối gửi:

```rust
                    let _ = ws_tx.send(crate::network::outgoing_clipboard_message(&payload));
```

- [ ] **Step 6: Đảm bảo PC phục vụ được `blob_request` cho payload của chính nó**

Nhánh `blob_request` (`network.rs:419-462`) tìm payload theo `item.blob_id == request.blob_id && item.blob_ready`. Mục vừa do PC copy phải có `blob_id` đặt sẵn thì Android mới tải về được. Kiểm tra `promote_or_insert_payload` có đặt `blob_id`/`blob_ready` cho mục nguồn PC không:

```bash
cd src-tauri && sed -n '496,560p' src/history.rs
```

Nếu chưa đặt, thêm vào nhánh tạo mục mới trong `promote_or_insert_payload`:

```rust
    item.blob_id = payload.fingerprint();
    item.blob_size = payload.encoded_size();
    item.blob_ready = true;
```

Viết test khẳng định điều này trong `mod history_maintenance_tests`:

```rust
    #[test]
    fn pc_image_entries_are_servable_as_blobs() {
        let mut data = crate::state::empty_state();
        let payload = ClipboardPayload {
            kind: "image".into(),
            text: "[Hình ảnh]".into(),
            data: "QUJDRA==".into(),
            ..ClipboardPayload::default()
        };
        assert!(promote_or_insert_payload(&mut data, &payload, "PC"));
        let item = &data.history[0];
        assert_eq!(item.blob_id, payload.fingerprint());
        assert!(item.blob_ready, "Android không tải được blob nếu chưa ready");
    }
```

- [ ] **Step 7: Build và kiểm tra**

```bash
cd src-tauri && cargo test --lib && cargo build
```

Kiểm tra bằng tay: copy một ảnh chụp màn hình lớn (>1MB) trên PC. Android phải hiện thanh tiến độ rồi mới có ảnh trong clipboard, chứ không đứng im một lúc lâu rồi mới nhảy xong.

- [ ] **Step 8: Commit**

```bash
git add src-tauri/src/network.rs src-tauri/src/lib.rs src-tauri/src/history.rs
git commit -m "perf: chunk large PC-to-Android payloads instead of one giant frame

Android-to-PC already used blob offers and chunking; the reverse
direction inlined the whole base64 in a single text frame, so a 10MB
image became a ~13MB message Android had to buffer and decrypt whole.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: `ws_tx` ngừng clone chuỗi lớn cho từng client

`broadcast::Sender<String>` clone toàn bộ chuỗi cho mỗi subscriber. Task 3 giảm kích thước thường gặp, nhưng payload dưới ngưỡng vẫn tới 256KB × số thiết bị.

**Files:**
- Modify: `src-tauri/src/lib.rs:961`, `src-tauri/src/network.rs`

**Interfaces:**
- Produces: kênh đổi thành `tokio::sync::broadcast::Sender<Arc<String>>`

- [ ] **Step 1: Đổi kiểu kênh**

Trong `src-tauri/src/lib.rs`:

```rust
    let (ws_tx, _ws_rx) = tokio::sync::broadcast::channel::<Arc<String>>(100);
```

- [ ] **Step 2: Cập nhật mọi nơi gửi**

```bash
cd src-tauri && grep -rn "tokio::sync::broadcast::Sender<String>\|ws_tx.send(\|tx.send(" src/
```

Mỗi `try_state::<tokio::sync::broadcast::Sender<String>>()` đổi thành `try_state::<tokio::sync::broadcast::Sender<Arc<String>>>()`, và mỗi `send(x)` đổi thành `send(Arc::new(x))`. Các vị trí: `lib.rs:236`, `:285`, `:316`, `:410`, `:686`, và watcher consumer.

- [ ] **Step 3: Cập nhật phía nhận**

Trong `src-tauri/src/network.rs`, chữ ký `spawn_ws_server` và `handle_client`:

```rust
pub(crate) fn spawn_ws_server(
    app: &AppHandle,
    data: Arc<Mutex<AppStateData>>,
    ws_tx: broadcast::Sender<Arc<String>>,
) {
```

```rust
    mut rx: broadcast::Receiver<Arc<String>>,
```

Trong sender task, nhánh `rx.recv()`:

```rust
                received = rx.recv() => match received {
                    Ok(message) => {
                        if let Some(message) =
                            protect_for_client(&data_sync, &sender_session, message.as_str())
                        {
                            if write.send(Message::Text(message.into())).await.is_err() { break; }
                        }
                    }
```

`protect_for_client` đổi tham số sang `&str`:

```rust
fn protect_for_client(
    _data: &Mutex<AppStateData>,
    session: &Mutex<Option<SessionCipher>>,
    message: &str,
) -> Option<String> {
    session
        .lock()
        .unwrap()
        .as_mut()
        .and_then(|cipher| cipher.encrypt(message).ok())
}
```

Nhánh `DirectMessage::App(message)` gọi `protect_for_client(&data_sync, &sender_session, &message)`.

- [ ] **Step 4: Build và test**

```bash
cd src-tauri && cargo test --lib && cargo build 2>&1 | tail -10
```

Expected: compile sạch, test xanh.

- [ ] **Step 5: Commit**

```bash
git add src-tauri/src/lib.rs src-tauri/src/network.rs
git commit -m "perf: broadcast Arc<String> so payloads are not cloned per client

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: Android tách fingerprint theo chiều

`lastSyncedFingerprint` (`ClipboardService.kt:60`) là một `var` không `@Volatile`, dùng chung cho cả gửi lẫn nhận, bị ghi từ 7 vị trí trên nhiều coroutine `Dispatchers.IO`. Vừa race, vừa khiến việc copy lại một mục vừa nhận về bị nuốt im lặng.

**Files:**
- Modify: `android/.../service/ClipboardService.kt:60`, `:84-96`, `:331-340`, `:373`, `:545`, `:716`, `:789`

**Interfaces:**
- Produces: hai field thay cho một —
  - `@Volatile private var lastSentFingerprint: String` — chặn gửi lặp cùng nội dung
  - `@Volatile private var lastAppliedFingerprint: String` — chặn echo nội dung vừa nhận

- [ ] **Step 1: Khai báo hai field**

```kotlin
    // Hai chiều phải có bộ nhớ riêng. Trước đây dùng chung một biến, nên khi
    // người dùng chủ động copy lại đúng nội dung PC vừa gửi sang thì thao tác
    // đó bị nuốt im lặng.
    @Volatile
    private var lastSentFingerprint = ""

    @Volatile
    private var lastAppliedFingerprint = ""
```

Xoá `private var lastSyncedFingerprint = ""`.

- [ ] **Step 2: Chiều gửi**

`syncCurrentClipboard` (dòng 84-96):

```kotlin
    private fun syncCurrentClipboard() {
        val clip = runCatching { clipboardManager.primaryClip }.getOrNull()
        scope.launch {
            val payload = AndroidClipboardCodec.read(this@ClipboardService, clip)
                ?: return@launch
            val fingerprint = payload.fingerprint()
            // Bỏ qua nếu đây chính là thứ ta vừa ghi vào clipboard từ PC —
            // không thì mỗi lần nhận sẽ vọng ngược lại một lần gửi.
            if (fingerprint == lastAppliedFingerprint) return@launch
            if (fingerprint == lastSentFingerprint) return@launch
            lastSentFingerprint = fingerprint
            sendClipboardPayload(payload)
            saveToHistory(payload, "LOCAL")
            Log.d(TAG, "Sent ${payload.kind}: ${payload.text.take(60)}")
        }
    }
```

- [ ] **Step 3: Chiều nhận**

`receiveClipboardPayload` (dòng 331-340):

```kotlin
    private suspend fun receiveClipboardPayload(payload: ClipboardPayload) {
        if (payload.text.isEmpty() || !payload.isWithinLimit()) return
        val fingerprint = payload.fingerprint()
        if (fingerprint == lastAppliedFingerprint) return
        lastAppliedFingerprint = fingerprint
        withContext(Dispatchers.Main) {
            clipboardManager.setPrimaryClip(AndroidClipboardCodec.write(this@ClipboardService, payload))
        }
        saveToHistory(payload, "REMOTE")
        Log.d(TAG, "Received ${payload.kind}: ${payload.text.take(60)}")
    }
```

Bốn chỗ còn lại đang gán `lastSyncedFingerprint` trước khi `setPrimaryClip` — tất cả đều là chiều **áp dụng vào clipboard**, nên đổi thành `lastAppliedFingerprint`:

- dòng 373 (trong `copyHistoryItem`) — nhưng chỗ này người dùng chủ động bấm copy, nên **cũng** phải đặt `lastSentFingerprint` vì ngay dưới nó có `sendClipboardPayload(payload)`:
  ```kotlin
        lastAppliedFingerprint = payload.fingerprint()
        lastSentFingerprint = payload.fingerprint()
  ```
- dòng 545 (trong `mergeHistorySync`) → `lastAppliedFingerprint`
- dòng 716 (trong `completeIncomingBlob`) → `lastAppliedFingerprint`
- dòng 789 (trong `handleIncomingBlobChunk`) → `lastAppliedFingerprint`

- [ ] **Step 4: Kiểm tra không còn tham chiếu cũ**

```bash
cd android && grep -rn "lastSyncedFingerprint" app/src/
```

Expected: không còn kết quả.

- [ ] **Step 5: Build và test**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Kiểm tra bằng tay**

Copy chuỗi "abc" trên PC → Android nhận. Trên Android, chọn lại đúng chuỗi "abc" và copy thủ công → PC phải nhận được, chứ không bị nuốt.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/fastpaste/app/service/ClipboardService.kt
git commit -m "fix: track send and apply clipboard fingerprints separately

One non-volatile var covered both directions and was written from seven
places across IO coroutines. Besides the race, it meant deliberately
re-copying content the PC had just sent was silently swallowed.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 6: Một chủ sở hữu duy nhất cho `ServiceDiscovery`

`MainViewModel.kt:72` và `ClipboardService.kt:211` mỗi bên tạo một `ServiceDiscovery`. Hai bên cùng bind cổng UDP 4568 và cùng có thể kích hoạt kết nối.

**Files:**
- Modify: `android/.../MainViewModel.kt:72`, `:110-172`
- Modify: `android/.../service/ClipboardService.kt:210-242`

**Interfaces:**
- Produces: `ClipboardService.discoveredServers: MutableStateFlow<List<DiscoveredServer>>` — nguồn sự thật duy nhất; ViewModel chỉ quan sát

- [ ] **Step 1: Service công bố danh sách tìm được**

Trong `android/.../service/ClipboardService.kt`, trong `companion object` (cạnh `activeTarget`, dòng 980):

```kotlin
        /** Nguồn sự thật duy nhất cho danh sách PC tìm được. Trước đây
         *  ViewModel chạy một ServiceDiscovery riêng, nên có hai bên cùng
         *  bind UDP 4568 và cùng có thể kích hoạt kết nối. */
        val discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
        val isScanning = MutableStateFlow(false)
```

Trong `ensureBackgroundDiscovery`, trong collector, thêm ngay đầu:

```kotlin
                d.servers.collect { servers ->
                    discoveredServers.value = servers
```

Và nối `isScanning`:

```kotlin
                scope.launch { d.isScanning.collect { isScanning.value = it } }
```

- [ ] **Step 2: Discovery phải chạy cả khi chưa từng kết nối**

`ensureBackgroundDiscovery` hiện chỉ được gọi từ nhánh `DISCONNECTED` của state collector, tức là chỉ sau khi `startSync` đã chạy. Gọi thêm trong `onCreate` để lần chạy đầu vẫn quét:

```kotlin
    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ensureBackgroundDiscovery()
    }
```

- [ ] **Step 3: ViewModel bỏ discovery riêng**

Trong `android/.../MainViewModel.kt`, xoá `private val discovery = ServiceDiscovery(application)` (dòng 72) và `discovery.startDiscovery(cycle = true)` (dòng 111).

Đổi hai collector (dòng 122-180) sang đọc từ Service:

```kotlin
        viewModelScope.launch {
            ClipboardService.discoveredServers.collect { servers ->
```

```kotlin
        viewModelScope.launch {
            ClipboardService.isScanning.collect { scanning ->
                _uiState.update { it.copy(isScanning = scanning) }
            }
        }
```

Phần thân của collector đầu tiên (log, rebind pairing, auto-connect) giữ nguyên.

- [ ] **Step 4: Đảm bảo Service đang chạy khi UI mở**

ViewModel giờ phụ thuộc Service để có discovery, nên Service phải được khởi động ngay cả khi chưa có target. Trong `init` của `MainViewModel`, thay chỗ `discovery.startDiscovery` đã xoá:

```kotlin
        // Discovery giờ sống trong Service. Đánh thức nó để lần chạy đầu vẫn
        // tìm được PC dù chưa có target nào.
        ContextCompat.startForegroundService(
            application,
            Intent(application, ClipboardService::class.java).setAction(ClipboardService.ACTION_START_DISCOVERY)
        )
```

Thêm action tương ứng trong `ClipboardService`:

```kotlin
            ACTION_START_DISCOVERY -> {
                startForeground(NOTIFICATION_ID, buildNotification("Đang tìm PC cùng mạng…"))
                ensureBackgroundDiscovery()
            }
```

và hằng trong `companion object`:

```kotlin
        const val ACTION_START_DISCOVERY = "com.fastpaste.app.START_DISCOVERY"
```

- [ ] **Step 5: Kiểm tra không còn tham chiếu cũ**

```bash
cd android && grep -rn "ServiceDiscovery(" app/src/
```

Expected: chỉ còn một kết quả, trong `ClipboardService.kt`.

- [ ] **Step 6: Build và kiểm tra**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest 2>&1 | tail -15
```

Kiểm tra bằng tay: mở app lần đầu sau khi xoá dữ liệu → vẫn tìm thấy PC. Đóng UI (không kill app) → Service vẫn tìm thấy PC khi PC đổi IP.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/fastpaste/app/MainViewModel.kt \
        android/app/src/main/java/com/fastpaste/app/service/ClipboardService.kt
git commit -m "refactor: give ServiceDiscovery a single owner

The ViewModel and the Service each ran their own instance, so two
listeners bound UDP 4568 and both could trigger connections. The Service
now owns it and publishes the result; the ViewModel observes.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 7: Tách `ClipboardService`

982 dòng với ba trách nhiệm trộn vào nhau: vòng đời service, truyền blob, và trộn history.

**Files:**
- Create: `android/.../sync/BlobTransferManager.kt`
- Create: `android/.../sync/HistorySyncMerger.kt`
- Modify: `android/.../service/ClipboardService.kt`

**Interfaces:**
- Produces:
  - `BlobTransferManager(scope, transferStore, dao, send: (String) -> Boolean, sendBinary: (ByteArray) -> Boolean, onProgress: (TransferProgress) -> Unit, onComplete: suspend (ClipboardPayload, Boolean) -> Unit)`
    - `suspend fun handleOffer(json: JSONObject)`
    - `suspend fun handleRequest(json: JSONObject)`
    - `suspend fun handleChunk(json: JSONObject)`
    - `suspend fun handleBinaryChunk(frame: ByteArray)`
    - `fun handleComplete(json: JSONObject)`
    - `fun registerOutgoing(blobId: String, payload: ClipboardPayload)`
  - `HistorySyncMerger(dao, deletedHistoryStore)`
    - `suspend fun buildSyncPayload(cursor: Long): String`
    - `suspend fun merge(entries: JSONArray, cursor: Long): ClipboardPayload?` — trả payload cần áp vào clipboard, hoặc `null`

- [ ] **Step 1: Chuyển khối blob transfer**

Chuyển nguyên văn các hàm sau từ `ClipboardService.kt` sang `BlobTransferManager.kt`, giữ nguyên thân, chỉ đổi các lời gọi tới state của service thành tham số constructor:

`handleBlobOffer` (560), `requestBlob` (577), `sendBlobRequest` (608), `sendOutgoingBlobChunk` (625), `handleIncomingBinaryChunk` (658), `completeIncomingBlob` (700), `handleIncomingBlobChunk` (732), `sendBlobComplete` (805), `handleBlobComplete` (817), `setTransferProgress` (831), `encodeBinaryChunk` (846), `decodeBinaryChunk` (874), `sha256Hex` (892).

Kèm theo: `data class IncomingTransfer` (70-78), `outgoingPayloads`, `incomingTransfers`, và các hằng `DEFAULT_WINDOW_SIZE` / kích thước chunk.

- [ ] **Step 2: Chuyển khối history sync**

Chuyển `sendHistorySync` (398) và `mergeHistorySync` (490) sang `HistorySyncMerger.kt`.

`mergeHistorySync` hiện tự gọi `setPrimaryClip` (dòng 546-548). Tách trách nhiệm đó ra: merger **trả về** payload cần áp, service mới là bên ghi clipboard — như vậy `lastAppliedFingerprint` (Task 5) chỉ được đặt ở một nơi duy nhất.

```kotlin
    /** Trả payload cần ghi vào clipboard, hoặc null nếu không có gì mới hơn. */
    suspend fun merge(entries: JSONArray, cursor: Long): ClipboardPayload? {
        // …logic trộn/dedup giữ nguyên…
        return if (newestIncomingTimestamp > latestLocalTimestamp && newestIncomingReady) {
            newestIncomingPayload
        } else {
            null
        }
    }
```

Phía service:

```kotlin
            "history_sync" -> {
                historySyncMerger.merge(json.optJSONArray("entries") ?: JSONArray(), 0L)
                    ?.let { receiveClipboardPayload(it) }
            }
```

- [ ] **Step 3: Nối dây trong service**

```kotlin
    private val blobTransfers by lazy {
        BlobTransferManager(
            scope = scope,
            transferStore = transferStore,
            dao = dao,
            send = ::sendWire,
            sendBinary = { frame -> wsClient?.sendBinary(frame) == true },
            onProgress = ::publishTransferProgress,
            onComplete = { payload, applyToClipboard ->
                if (applyToClipboard) receiveClipboardPayload(payload)
            }
        )
    }

    private val historySyncMerger by lazy { HistorySyncMerger(dao, deletedHistoryStore) }
```

Các nhánh trong `handleDecodedMessage` rút gọn thành lời gọi ủy quyền.

- [ ] **Step 4: Kiểm tra kích thước**

```bash
cd android && wc -l app/src/main/java/com/fastpaste/app/service/ClipboardService.kt \
                    app/src/main/java/com/fastpaste/app/sync/BlobTransferManager.kt \
                    app/src/main/java/com/fastpaste/app/sync/HistorySyncMerger.kt
```

Expected: `ClipboardService.kt` dưới 500 dòng; hai file mới mỗi file dưới 400 dòng.

- [ ] **Step 5: Build và test**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest 2>&1 | tail -15
```

- [ ] **Step 6: Kiểm tra bằng tay**

Truyền một ảnh lớn theo cả hai chiều: thanh tiến độ chạy, ảnh tới nơi, và nội dung khớp. Đây là hồi quy quan trọng nhất của task này — refactor thuần nhưng đụng vào toàn bộ đường truyền blob.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/fastpaste/app/sync/BlobTransferManager.kt \
        android/app/src/main/java/com/fastpaste/app/sync/HistorySyncMerger.kt \
        android/app/src/main/java/com/fastpaste/app/service/ClipboardService.kt
git commit -m "refactor: split blob transfer and history merge out of ClipboardService

982 lines mixed service lifecycle, chunked transfer, and history merging.
Merging also wrote the clipboard itself, which is why the apply-side
fingerprint was set in five different places.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 8: Tách `HomeScreen`

69KB trong một file Composable.

**Files:**
- Modify: `android/.../ui/screens/HomeScreen.kt`
- Create: `android/.../ui/screens/sections/ConnectionSection.kt`, `HistorySection.kt`, `SettingsSection.kt`, `PairingSection.kt`

**Interfaces:**
- Produces: mỗi file xuất một `@Composable` nhận `UiState` cùng các lambda callback nó cần; `HomeScreen` chỉ còn lắp ráp

- [ ] **Step 1: Xác định ranh giới section**

```bash
cd android && grep -n "^@Composable\|^private fun\|^fun " app/src/main/java/com/fastpaste/app/ui/screens/HomeScreen.kt
```

Nhóm các Composable theo section mà chúng dựng. `ConnectionUi` (dòng ~1685) và các Composable trạng thái kết nối thuộc `ConnectionSection.kt`.

- [ ] **Step 2: Chuyển từng section một, build sau mỗi lần**

Mỗi section: chuyển các Composable sang file mới, đổi `private` thành `internal` cho những hàm bị gọi chéo file, rồi build ngay:

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -10
```

Không chuyển hai section cùng lúc — nếu hỏng thì không biết do cái nào.

- [ ] **Step 3: Kiểm tra kích thước**

```bash
cd android && wc -l app/src/main/java/com/fastpaste/app/ui/screens/HomeScreen.kt \
                    app/src/main/java/com/fastpaste/app/ui/screens/sections/*.kt
```

Expected: mỗi file dưới 500 dòng.

- [ ] **Step 4: Kiểm tra bằng tay**

Mở app, đi qua từng màn: kết nối, lịch sử, cài đặt, ghép đôi. Refactor UI thuần — mọi thứ phải trông và hoạt động y hệt trước.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/fastpaste/app/ui/screens/
git commit -m "refactor: split HomeScreen into per-section files

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 9: Cập nhật `CLAUDE.md`

Tài liệu hiện mô tả kiến trúc trước commit `5e07d29`.

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Sửa các sai lệch đã biết**

| Chỗ sai | Sự thật |
|---|---|
| "history (max 500 items)" | `MAX_HISTORY_ITEMS = 1_000` (`history.rs:8`), thêm `MAX_INLINE_PAYLOADS = 50` |
| Bảng module thiếu | `clipboard.rs`, `crypto.rs`, `pairing.rs`, `transfer.rs`, `vault.rs`, `watcher.rs`, `status.rs` |
| "Clipboard poller — polls clipboard every 250ms" | Watcher chạy trên OS thread riêng, dùng `GetClipboardSequenceNumber`, chu kỳ 200ms, chỉ đọc khi sequence đổi |
| "persisted to `settings.json` … on every mutation" | `save_state()` đánh dấu dirty; writer thread flush mỗi 500ms và khi thoát. Dữ liệu nhạy cảm nằm trong `history.vault` mã hoá DPAPI |
| "Raw plain-text messages … treated as immediate clipboard pastes" | Chỉ đúng sau khi session đã xác thực. Peer chưa ghép đôi nhận `pair_required` và không có dữ liệu nào đi qua |
| Mục IPC thiếu | `get_history_thumbnail`, `begin_device_pairing`, `list_paired_devices`, `forget_paired_device`, `set_e2ee_passphrase`, `set_e2ee_enabled` |
| "Events — Rust emits `update_state`" | Thêm `update_transfers`, `state_loaded`, `pairing_required` |
| "There are no tests in either component" | Có test Rust trong `clipboard.rs`, `transfer.rs`, `vault.rs`, `history.rs`, `state.rs`, `lib.rs`, `network.rs`; test Android trong `SqlCipherMigrationTest`, `PairingProtocolTest`. CI chạy cả hai |

- [ ] **Step 2: Thêm mục về giao thức ghép đôi**

Viết một mục ngắn mô tả: QR mang secret dùng một lần → `pair_request`/`pair_accept` tạo root key lưu trong vault → mỗi lần reconnect làm ECDH P-256 tươi qua `session_hello`/`session_accept` → chưa có session thì không dữ liệu nào đi qua, và peer nhận `pair_required`.

- [ ] **Step 3: Thêm mục về bất biến hiệu năng**

Ghi rõ các ràng buộc mà người sửa sau dễ vô tình phá:

```markdown
## Performance Invariants

Bốn quy tắc này là kết quả của việc sửa một đợt treo và rớt kết nối. Phá
chúng thì lỗi quay lại y nguyên:

1. **Không chạy công việc blocking trên async runtime.** Win32 clipboard,
   DPAPI, ghi file, encode ảnh đều thuộc về OS thread riêng hoặc
   `spawn_blocking`. Block worker của tokio là làm trễ pong, và OkHttp bên
   Android sẽ cắt kết nối sau 10 giây.
2. **Đường LAN đi trước đĩa.** Trong mọi đường xử lý clipboard, `ws_tx.send()`
   phải đứng trước `save_state()` và `broadcast_state()`.
3. **Dữ liệu nhị phân không đi qua sự kiện UI.** `sanitized_for_ui()` xoá cả
   `data` lẫn `thumbnail`; frontend tải thumbnail lười theo mục hiển thị.
4. **Không đọc lại nội dung clipboard trong vòng lặp.** Hỏi
   `GetClipboardSequenceNumber()` trước; chỉ đọc khi số đổi và không phải do
   chính ta ghi.
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: bring CLAUDE.md in line with the current architecture

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Self-review

**Spec coverage (GĐ 6, 7, 8 + phần còn lại của §5.5):**

| Yêu cầu | Task |
|---|---|
| §5.7 debounce theo deadline cố định | Task 1 |
| §5.7 không sync khi không có gì đổi | Task 2 |
| §5.7 không giữ mutex lâu khi clone history | Task 2 (step 6) |
| §5.5 chunk PC→Android hai chiều | Task 3 |
| §5.5 `ws_tx` dùng `Arc` | Task 4 |
| §5.8 tách fingerprint hai chiều | Task 5 |
| §5.8 một chủ `ServiceDiscovery` | Task 6 |
| §5.8 tách `ClipboardService.kt` | Task 7 |
| §5.8 tách `HomeScreen.kt` | Task 8 |
| GĐ 8 cập nhật `CLAUDE.md` | Task 9 |

**Chỉnh so với spec:** §5.7 viết *"delta sync: gửi các mục thay đổi kể từ revision cuối"*. Đọc `cloud.rs` thì delta thật đòi đổi định dạng file trên Drive — rủi ro cao với người dùng đang chạy nhiều máy khác phiên bản, và Global Constraints của plan này cấm điều đó. Task 2 vì thế lấy phần lớn lợi ích bằng cách rẻ và an toàn hơn: bỏ qua hẳn round trip khi không có gì đổi, cộng với việc thôi clone base64 dưới mutex. Delta sync thật, nếu vẫn cần, phải có spec riêng kèm kế hoạch migration.

**Điểm cần đọc-trước-khi-sửa:** Task 2 Step 6 có một nhánh điều kiện — nếu `upload_missing_blobs` đọc `entry.payload.data` thì không được rút gọn `history_to_cloud_entries`. Plan yêu cầu đọc code rồi mới quyết, không đoán.

**Type consistency:** `sanitized_for_cloud()` do Plan 2 Task 1 tạo, dùng ở Task 2 và Task 3. `history_revision` nhận `(&[HistoryItem], &[DeletedMarker], Option<i64>)` nhất quán giữa test và call site. `BLOB_OFFER_THRESHOLD_BYTES` khai báo `pub(crate)` để test dùng được. `ClipboardService.discoveredServers` / `.isScanning` (Task 6) là `MutableStateFlow` trong `companion object`, khớp khuôn mẫu `activeTarget` / `connectionState` sẵn có.

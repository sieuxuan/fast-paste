# FastPaste — Đường tới hạn: hết treo, gỡ copy file, sync LAN nhanh

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Loại bỏ nguyên nhân treo khi khởi động, gỡ hẳn tính năng copy file, và đưa độ trễ đồng bộ LAN về mức tức thời, bằng cách chuyển mọi công việc blocking ra khỏi async runtime.

**Architecture:** Ba thay đổi cấu trúc. (1) Clipboard chuyển từ "đọc lại nội dung mỗi 250ms" sang "hỏi `GetClipboardSequenceNumber`, chỉ đọc khi số đổi", chạy trên một OS thread riêng thay vì trên tokio. (2) Ghi state chuyển từ đồng bộ-mỗi-mutation sang một writer thread có debounce. (3) Khởi động không còn chặn: cửa sổ hiện trước, state load nền. Kèm theo đó, thứ tự trong đường xử lý clipboard đảo lại thành LAN-trước-đĩa.

**Tech Stack:** Rust + Tauri v2, tokio, tokio-tungstenite 0.29, windows-sys 0.59, arboard 3.6, image 0.25; Kotlin + Jetpack Compose + OkHttp (Android).

## Global Constraints

- Desktop chỉ chạy Windows. Mọi code Win32 phải nằm sau `#[cfg(windows)]` và có bản `#[cfg(not(windows))]` tương ứng, theo đúng khuôn mẫu hiện có trong `clipboard.rs`.
- Mọi chuỗi hiển thị cho người dùng viết bằng tiếng Việt, khớp giọng văn hiện có.
- `settings.json` và `history.vault` nằm cạnh file `.exe` (app portable) — không đổi vị trí.
- Không được phá dữ liệu cũ: `serde` mặc định bỏ qua field lạ, nên vault cũ vẫn deserialize được sau khi gỡ field `files`.
- `MAX_HISTORY_ITEMS = 1_000` (`history.rs:8`) — giữ nguyên trong plan này.
- Sau mỗi task, `cargo check --tests` và `cargo test` (trong `src-tauri/`) phải xanh.
- Commit sau mỗi task. Không commit khi test đỏ.

## File Structure

| File | Trách nhiệm sau plan này |
|---|---|
| `src-tauri/src/clipboard.rs` | Đọc/ghi clipboard Windows (text/html/image — **không còn file**), phát hiện thay đổi bằng sequence number, chống echo |
| `src-tauri/src/watcher.rs` *(mới)* | OS thread theo dõi clipboard, đẩy `ClipboardPayload` qua channel; giữ cache payload mới nhất |
| `src-tauri/src/state.rs` | `save_state()` = đánh dấu dirty; `flush_state_now()` = ghi thật; writer thread có debounce |
| `src-tauri/src/lib.rs` | Khởi động không chặn, tiêu thụ channel của watcher, thứ tự LAN-trước-đĩa |
| `src-tauri/src/network.rs` | Server-side ping, dùng cache clipboard thay vì đọc trực tiếp, đẩy blocking ra `spawn_blocking` |
| `src-tauri/src/history.rs` | Thêm migration gỡ payload `kind == "files"` |
| `src-tauri/src/transfer.rs` | Sửa 2 test đang gãy |
| `android/.../AndroidClipboardCodec.kt` | Chỉ còn text/html/image; gỡ nhánh nhiều-file |
| `android/.../ClipboardPayload.kt` | Gỡ `files` / `ClipboardFilePayload` |
| `.github/workflows/ci.yml` *(mới)* | Chạy `cargo test` + `gradle test` trên mỗi push/PR |

---

## Task 1: Sửa test đang gãy và dựng CI gate

Test suite hiện không compile. Không sửa cái này trước thì mọi task sau đều không có lưới an toàn.

**Files:**
- Modify: `src-tauri/src/transfer.rs:409-438`
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `transfer::BlobRequest`, `transfer::make_chunk`, `transfer::receive_chunk`, `transfer::ReceiveOutcome`, `transfer::DEFAULT_WINDOW_SIZE` (đã tồn tại)
- Produces: không có API mới; chỉ đảm bảo `cargo test` chạy được

- [ ] **Step 1: Chạy test để thấy nó gãy**

```bash
cd src-tauri && cargo check --tests --message-format=short 2>&1 | grep -E "^src|^error"
```

Expected: FAIL — `transfer.rs:422` thiếu field `window_size` trong `BlobRequest`, `transfer.rs:435` dùng `Option<String>` như `&str`.

- [ ] **Step 2: Sửa test**

Thay toàn bộ thân `chunk_ack_resume_and_hash_verification` trong `src-tauri/src/transfer.rs`:

```rust
    #[test]
    fn chunk_ack_resume_and_hash_verification() {
        let payload = ClipboardPayload {
            kind: "image".into(),
            text: "ảnh".into(),
            mime_type: "image/png".into(),
            data: base64::engine::general_purpose::STANDARD.encode(vec![5u8; 140_000]),
            ..ClipboardPayload::default()
        };
        let blob_id = payload.fingerprint();
        let mut offset = 0;
        let transfer_id = "transfer-test".to_string();
        loop {
            let request = BlobRequest {
                transfer_id: transfer_id.clone(),
                blob_id: blob_id.clone(),
                next_offset: offset,
                chunk_size: 32 * 1024,
                window_size: DEFAULT_WINDOW_SIZE,
            };
            let wire = make_chunk(&request, &payload).unwrap();
            let chunk: BlobChunk = serde_json::from_str(&wire).unwrap();
            let result = receive_chunk(chunk).unwrap();
            if let Some(received) = result.payload {
                assert!(received == payload);
                break;
            }
            let control = result.control.expect("chunk chưa đủ thì phải có ACK");
            let ack: serde_json::Value = serde_json::from_str(&control).unwrap();
            offset = ack["nextOffset"].as_u64().unwrap() as usize;
        }
    }
```

- [ ] **Step 3: Chạy test để xác nhận xanh**

```bash
cd src-tauri && cargo test --lib 2>&1 | tail -20
```

Expected: PASS, toàn bộ test trong `clipboard.rs`, `transfer.rs`, `vault.rs` đều chạy.

- [ ] **Step 4: Tạo CI gate**

Tạo `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [master]
  pull_request:

jobs:
  desktop:
    runs-on: windows-latest
    defaults:
      run:
        working-directory: src-tauri
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@stable
      - uses: Swatinem/rust-cache@v2
        with:
          workspaces: src-tauri
      - name: Check (including tests)
        run: cargo check --tests --locked
      - name: Test
        run: cargo test --locked

  android:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: android
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
      - name: Unit tests
        run: ./gradlew testDebugUnitTest
```

- [ ] **Step 5: Commit**

```bash
git add src-tauri/src/transfer.rs .github/workflows/ci.yml
git commit -m "test: fix broken transfer tests and add CI gate

The transfer tests have not compiled since 5e07d29 — BlobRequest gained
window_size and ReceiveOutcome::control became Option<String>, but the
test was never updated because cargo check without --tests still passed.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: Gỡ copy file khỏi desktop

`read_files()` là nhánh đầu tiên của `read_clipboard()` và chạy mỗi 250ms — với một file trên clipboard, nó đọc lại tới 64MB từ đĩa và base64-encode 4 lần/giây, vô hạn.

**Files:**
- Modify: `src-tauri/src/clipboard.rs` (gỡ `ClipboardFile`, `read_files`, `write_files`, `mime_for_name`, `sanitize_file_name`, field `files`)
- Modify: `src-tauri/src/history.rs` (thêm migration)
- Modify: `src-tauri/src/state.rs:222` (gọi migration trong `load_state`)

**Interfaces:**
- Produces: `history::drop_file_payloads(data: &mut AppStateData) -> bool` — trả `true` nếu có mục bị đổi, để `load_state` biết cần ghi lại.
- Sau task này `ClipboardPayload` không còn field `files`; `is_within_limit()` và `encoded_size()` chỉ tính `data`.

- [ ] **Step 1: Viết test migration (sẽ fail)**

Thêm vào cuối `src-tauri/src/history.rs`, trong `mod tests` (tạo mới nếu chưa có):

```rust
#[cfg(test)]
mod file_migration_tests {
    use super::*;
    use crate::clipboard::ClipboardPayload;

    fn state_with(items: Vec<HistoryItem>) -> AppStateData {
        let mut data = crate::state::empty_state();
        data.history = items;
        data
    }

    #[test]
    fn file_payloads_are_dropped_but_label_survives() {
        let mut item = make_history_item("[2 tệp · a.txt, b.txt · abcd1234]", "PC");
        item.payload = Some(ClipboardPayload {
            kind: "files".into(),
            text: "[2 tệp · a.txt, b.txt · abcd1234]".into(),
            ..ClipboardPayload::default()
        });
        item.blob_id = "abcd".into();
        item.blob_size = 4096;
        item.blob_ready = true;
        let mut data = state_with(vec![item]);

        assert!(drop_file_payloads(&mut data));

        let migrated = &data.history[0];
        assert_eq!(migrated.text, "[2 tệp · a.txt, b.txt · abcd1234]");
        assert!(migrated.payload.is_none());
        assert!(migrated.blob_id.is_empty());
        assert_eq!(migrated.blob_size, 0);
        assert!(!migrated.blob_ready);
    }

    #[test]
    fn image_payloads_are_left_alone() {
        let mut item = make_history_item("[Hình ảnh 800×600 · deadbeef]", "PC");
        item.payload = Some(ClipboardPayload {
            kind: "image".into(),
            text: "[Hình ảnh 800×600 · deadbeef]".into(),
            data: "AAAA".into(),
            ..ClipboardPayload::default()
        });
        let mut data = state_with(vec![item]);

        assert!(!drop_file_payloads(&mut data));
        assert!(data.history[0].payload.is_some());
    }
}
```

- [ ] **Step 2: Chạy test để xác nhận fail**

```bash
cd src-tauri && cargo test --lib file_migration 2>&1 | tail -15
```

Expected: FAIL — `cannot find function drop_file_payloads`, `cannot find function empty_state`.

- [ ] **Step 3: Thêm `empty_state()` vào `state.rs`**

Trong `src-tauri/src/state.rs`, tách phần khởi tạo mặc định (đang inline ở `load_state:233-244`) thành hàm dùng chung:

```rust
pub(crate) fn empty_state() -> AppStateData {
    AppStateData {
        settings: default_settings(),
        history: vec![],
        ips: vec![],
        clients: vec![],
        deleted_markers: vec![],
        clear_history_at: None,
        app_icons: std::collections::HashMap::new(),
        history_backup: None,
        cloud: cloud::CloudUiState::default(),
        transfers: vec![],
    }
}
```

Rồi thay khối `let mut data = AppStateData { ... };` ở `load_state:233-244` bằng `let mut data = empty_state();`.

- [ ] **Step 4: Thêm migration vào `history.rs`**

```rust
/// Tính năng copy file đã bị gỡ. Mục cũ giữ lại nhãn text để người dùng
/// vẫn thấy mình từng copy gì, nhưng dữ liệu nhị phân thì bỏ đi.
pub(crate) fn drop_file_payloads(data: &mut AppStateData) -> bool {
    let mut changed = false;
    for item in &mut data.history {
        let is_file_payload = item
            .payload
            .as_ref()
            .is_some_and(|payload| payload.kind == "files");
        if is_file_payload {
            item.payload = None;
            item.blob_id.clear();
            item.blob_size = 0;
            item.blob_ready = false;
            changed = true;
        }
    }
    changed
}
```

- [ ] **Step 5: Chạy test để xác nhận xanh**

```bash
cd src-tauri && cargo test --lib file_migration 2>&1 | tail -10
```

Expected: PASS (2 test).

- [ ] **Step 6: Gỡ code file khỏi `clipboard.rs`**

Xoá hẳn khỏi `src-tauri/src/clipboard.rs`:
- `struct ClipboardFile` (dòng 8-15)
- field `files` trong `ClipboardPayload` (dòng 31-32)
- `fn read_files` (dòng 216-294) và `fn write_files` (dòng 296-371)
- `fn sanitize_file_name` (dòng 522-538) và `fn mime_for_name` (dòng 540-560)
- test `received_file_names_are_sanitized` (dòng 610-614)

Sửa các chỗ còn tham chiếu:

```rust
#[cfg(windows)]
pub(crate) fn read_clipboard() -> Option<ClipboardPayload> {
    read_image().or_else(read_html).or_else(read_text)
}
```

```rust
#[cfg(windows)]
pub(crate) fn write_clipboard(payload: &ClipboardPayload) -> Result<(), String> {
    if !payload.is_within_limit() {
        return Err("Clipboard vượt giới hạn 64 MB.".to_string());
    }
    match payload.kind.as_str() {
        "image" => write_image(payload),
        "html" => write_html(payload),
        _ => arboard::Clipboard::new()
            .and_then(|mut clipboard| clipboard.set_text(payload.text.clone()))
            .map_err(|error| error.to_string()),
    }
}
```

```rust
    pub(crate) fn fingerprint(&self) -> String {
        // Preview có thể được tái tạo bằng codec khác trên Android và
        // Windows. Nó là metadata, không phải danh tính clipboard.
        #[derive(Serialize)]
        struct ClipboardIdentity<'a> {
            kind: &'a str,
            text: &'a str,
            html: &'a str,
            #[serde(rename = "mimeType")]
            mime_type: &'a str,
            data: &'a str,
        }
        let bytes = serde_json::to_vec(&ClipboardIdentity {
            kind: &self.kind,
            text: &self.text,
            html: &self.html,
            mime_type: &self.mime_type,
            data: &self.data,
        })
        .unwrap_or_else(|_| self.text.as_bytes().to_vec());
        format!("{:x}", Sha256::digest(bytes))
    }
```

```rust
    pub(crate) fn is_within_limit(&self) -> bool {
        self.encoded_size() <= MAX_PAYLOAD_BYTES && self.thumbnail.len() <= MAX_THUMBNAIL_CHARS
    }

    pub(crate) fn encoded_size(&self) -> usize {
        let padding = if self.data.ends_with("==") {
            2
        } else if self.data.ends_with('=') {
            1
        } else {
            0
        };
        (self.data.len() / 4).saturating_mul(3).saturating_sub(padding)
    }

    pub(crate) fn sanitized_for_ui(&self) -> Self {
        let mut payload = self.clone();
        payload.data.clear();
        payload
    }
```

Test `thumbnail_does_not_change_clipboard_identity` có hardcode hash `8dcaf5bb...`. Bỏ field `files` khỏi `ClipboardIdentity` làm hash đổi — xoá dòng assert hash cố định đó, giữ lại assert hai fingerprint bằng nhau:

```rust
    #[test]
    fn thumbnail_does_not_change_clipboard_identity() {
        let payload = ClipboardPayload {
            kind: "image".to_string(),
            text: "image".to_string(),
            mime_type: "image/png".to_string(),
            data: STANDARD.encode(b"same-image"),
            thumbnail: "data:image/png;base64,AAAA".to_string(),
            ..ClipboardPayload::default()
        };
        let mut other_codec_preview = payload.clone();
        other_codec_preview.thumbnail = "data:image/jpeg;base64,BBBB".to_string();
        assert_eq!(payload.fingerprint(), other_codec_preview.fingerprint());
    }
```

Test `payload_limit_counts_base64_data` giữ nguyên, vẫn đúng.

- [ ] **Step 7: Gọi migration trong `load_state`**

Trong `src-tauri/src/state.rs`, ngay sau `normalize_deleted_markers(&mut data);` (dòng 222), thêm:

```rust
            let files_migrated = crate::history::drop_file_payloads(&mut data);
```

và đưa `files_migrated` vào điều kiện ghi lại ở dòng 225-227:

```rust
            if VAULT_WRITABLE.load(Ordering::Acquire)
                && (migrated_from_plaintext || settings_changed || icons_changed || files_migrated)
            {
                save_state(&data);
            }
```

- [ ] **Step 8: Build và test**

```bash
cd src-tauri && cargo test --lib 2>&1 | tail -20
```

Expected: PASS. Nếu còn lỗi compile về `files`, tìm chỗ sót:

```bash
cd src-tauri && grep -rn "ClipboardFile\|\.files\|\"files\"" src/
```

Chỉ còn được phép xuất hiện trong `history.rs` (chuỗi `"files"` trong migration).

- [ ] **Step 9: Commit**

```bash
git add src-tauri/src/clipboard.rs src-tauri/src/history.rs src-tauri/src/state.rs
git commit -m "feat!: remove file clipboard support from desktop

read_files ran first in read_clipboard, which the 250ms poller called
unconditionally — a single file on the clipboard meant re-reading up to
64MB from disk and base64-encoding it four times a second, forever.

Existing history entries keep their text label; the binary payload and
blob metadata are dropped by a one-shot migration on load.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: Gỡ copy file khỏi Android

Cẩn thận: trên Android, ảnh **cũng** đến qua đường URI (`readUris`). Chỉ gỡ nhánh nhiều-file, giữ nguyên nhánh một-ảnh.

**Files:**
- Modify: `android/app/src/main/java/com/fastpaste/app/data/ClipboardPayload.kt`
- Modify: `android/app/src/main/java/com/fastpaste/app/sync/AndroidClipboardCodec.kt`

**Interfaces:**
- Consumes: `ClipboardPayload.KIND_IMAGE`, `KIND_HTML`, `KIND_TEXT` (giữ nguyên)
- Produces: `ClipboardPayload` không còn `files` / `filesJson()`; `KIND_FILES` bị xoá. `AndroidClipboardCodec.write()` chỉ còn xử lý text/html/image.

- [ ] **Step 1: Gỡ `files` khỏi `ClipboardPayload.kt`**

Xoá: `data class ClipboardFilePayload`, field `val files: List<ClipboardFilePayload>`, `fun filesJson()`, hằng `KIND_FILES`, và mọi `.put("files", ...)` trong `toJson()` / `metadataJson()` / `protocolJson()`.

Sửa `isWithinLimit()` và `encodedSize()` để chỉ tính `data`:

```kotlin
    fun isWithinLimit(): Boolean =
        encodedSize() <= MAX_PAYLOAD_BYTES && thumbnail.length <= MAX_THUMBNAIL_CHARS

    fun encodedSize(): Long = decodedSize(data)
```

Trong `fromJson()`, xoá khối đọc `filesJson` (dòng 102-122) và bỏ `files = files` khỏi constructor.

`fingerprint()` phải khớp với desktop sau Task 2 — nghĩa là không còn `files` trong chuỗi identity. Kiểm tra thứ tự field trong `fingerprint()` khớp đúng `ClipboardIdentity` bên Rust: `kind`, `text`, `html`, `mimeType`, `data`.

- [ ] **Step 2: Thu hẹp `AndroidClipboardCodec` về ảnh đơn**

Trong `readUris()`, giữ nhánh một-ảnh (dòng 90-100), thay phần còn lại:

```kotlin
    private fun readUris(context: Context, uris: List<Uri>): ClipboardPayload? {
        val resolver = context.contentResolver
        // Copy file đã bị gỡ. Chỉ còn nhận một ảnh đơn từ clipboard.
        val uri = uris.firstOrNull() ?: return null
        val mime = resolver.getType(uri).orEmpty()
        if (!mime.startsWith("image/")) return null
        val bytes = resolver.openInputStream(uri)?.use { input ->
            readLimited(input, ClipboardPayload.MAX_PAYLOAD_BYTES)
        } ?: return null
        val hash = sha256(bytes).take(8)
        return ClipboardPayload(
            kind = ClipboardPayload.KIND_IMAGE,
            text = "[Hình ảnh · $hash]",
            mimeType = mime,
            data = ClipboardPayload.encode(bytes),
            thumbnail = createImageThumbnail(bytes)
        )
    }
```

Đổi `writeFiles` thành `writeImage` (chỉ còn một đường vào):

```kotlin
    fun write(context: Context, payload: ClipboardPayload): ClipData {
        return when (payload.kind) {
            ClipboardPayload.KIND_HTML -> ClipData.newHtmlText(
                "Fast Paste",
                payload.text,
                payload.html.ifBlank { payload.text }
            )

            ClipboardPayload.KIND_IMAGE -> writeImage(context, payload)

            else -> ClipData.newPlainText("Fast Paste", payload.text)
        }
    }

    private fun writeImage(context: Context, payload: ClipboardPayload): ClipData {
        val root = File(context.cacheDir, "clipboard").also { it.mkdirs() }
        cleanupOldClipboardFiles(root)
        val folder = File(root, payload.fingerprint()).also { it.mkdirs() }
        val target = File(folder, "clipboard-image.${extensionForMime(payload.mimeType)}")
        target.writeBytes(ClipboardPayload.decode(payload.data))
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            target
        )
        return ClipData.newUri(context.contentResolver, "Fast Paste", uri)
    }
```

Xoá `displayName()`, `sanitizeFileName()`, hằng `MAX_FILES`, và import `ClipboardFilePayload`, `OpenableColumns`, `Cursor` nếu không còn dùng.

`readLimited` đổi tham số `remaining: Int` giữ nguyên; gọi với `MAX_PAYLOAD_BYTES` trực tiếp.

- [ ] **Step 3: Dọn tham chiếu trong `ClipboardService.kt`**

```bash
cd android && grep -rn "KIND_FILES\|filesJson\|ClipboardFilePayload" app/src/
```

Xoá mọi nhánh `KIND_FILES` tìm được. Cột `filesJson` trong Room entity (`ClipboardEntry.kt`) nếu có: giữ cột lại nhưng ngừng ghi, để tránh phải viết migration Room — Room chỉ báo lỗi khi schema **thiếu** cột so với entity, nên nếu gỡ field khỏi entity thì phải bump version + migration. Chọn cách rẻ: giữ field trong entity, luôn ghi `"[]"`.

- [ ] **Step 4: Build và chạy unit test**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/fastpaste/app/data/ClipboardPayload.kt \
        android/app/src/main/java/com/fastpaste/app/sync/AndroidClipboardCodec.kt \
        android/app/src/main/java/com/fastpaste/app/service/ClipboardService.kt
git commit -m "feat!: remove file clipboard support from Android

Mirrors the desktop change. Single-image URIs still work — on Android
images arrive through the same URI path files did, so readUris keeps
the image branch and drops only the multi-file one.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: Phát hiện thay đổi clipboard bằng sequence number

Thay "đọc lại toàn bộ nội dung mỗi 250ms" bằng "hỏi một số nguyên; chỉ đọc khi số đổi". Đây cũng là cơ chế chống echo.

**Files:**
- Modify: `src-tauri/src/clipboard.rs`

**Interfaces:**
- Produces:
  - `clipboard::clipboard_sequence() -> u32` — giá trị `GetClipboardSequenceNumber()`
  - `clipboard::should_read(current: u32, last_seen: u32, self_write: u32) -> bool` — quyết định thuần, testable
  - `clipboard::mark_self_write()` — ghi lại sequence hiện tại sau khi chính FastPaste ghi clipboard
  - `clipboard::self_write_sequence() -> u32`
  - `write_clipboard()` tự gọi `mark_self_write()` khi thành công

- [ ] **Step 1: Viết test cho logic quyết định (sẽ fail)**

Thêm vào `mod tests` trong `src-tauri/src/clipboard.rs`:

```rust
    #[test]
    fn poller_skips_when_sequence_unchanged() {
        assert!(!should_read(7, 7, 0));
    }

    #[test]
    fn poller_reads_when_sequence_advances() {
        assert!(should_read(8, 7, 0));
    }

    #[test]
    fn poller_skips_its_own_write() {
        // FastPaste vừa ghi clipboard ở sequence 8; poller không được coi
        // đó là một lần copy mới rồi phát ngược lại cho thiết bị kia.
        assert!(!should_read(8, 7, 8));
    }

    #[test]
    fn poller_resumes_after_a_real_copy_follows_its_own_write() {
        assert!(should_read(9, 8, 8));
    }
```

- [ ] **Step 2: Chạy test để xác nhận fail**

```bash
cd src-tauri && cargo test --lib poller_ 2>&1 | tail -15
```

Expected: FAIL — `cannot find function should_read`.

- [ ] **Step 3: Implement**

Thêm đầu `src-tauri/src/clipboard.rs`, sau các `use`:

```rust
use std::sync::atomic::{AtomicU32, Ordering};

/// Sequence number tại thời điểm FastPaste tự ghi clipboard lần cuối.
/// Poller bỏ qua đúng giá trị này để không phát ngược nội dung vừa nhận
/// từ thiết bị khác — nếu không sẽ thành vòng lặp echo.
static SELF_WRITE_SEQUENCE: AtomicU32 = AtomicU32::new(0);

#[cfg(windows)]
pub(crate) fn clipboard_sequence() -> u32 {
    use windows_sys::Win32::System::DataExchange::GetClipboardSequenceNumber;
    unsafe { GetClipboardSequenceNumber() }
}

#[cfg(not(windows))]
pub(crate) fn clipboard_sequence() -> u32 {
    0
}

/// Đánh dấu rằng chính FastPaste vừa ghi clipboard.
pub(crate) fn mark_self_write() {
    SELF_WRITE_SEQUENCE.store(clipboard_sequence(), Ordering::Release);
}

pub(crate) fn self_write_sequence() -> u32 {
    SELF_WRITE_SEQUENCE.load(Ordering::Acquire)
}

/// Poller chỉ được đọc nội dung clipboard khi sequence đã đổi so với lần
/// trước VÀ thay đổi đó không phải do chính FastPaste gây ra.
pub(crate) fn should_read(current: u32, last_seen: u32, self_write: u32) -> bool {
    current != last_seen && current != self_write
}
```

Gọi `mark_self_write()` ở cuối mọi nhánh ghi thành công trong `write_clipboard`:

```rust
#[cfg(windows)]
pub(crate) fn write_clipboard(payload: &ClipboardPayload) -> Result<(), String> {
    if !payload.is_within_limit() {
        return Err("Clipboard vượt giới hạn 64 MB.".to_string());
    }
    let result = match payload.kind.as_str() {
        "image" => write_image(payload),
        "html" => write_html(payload),
        _ => arboard::Clipboard::new()
            .and_then(|mut clipboard| clipboard.set_text(payload.text.clone()))
            .map_err(|error| error.to_string()),
    };
    if result.is_ok() {
        mark_self_write();
    }
    result
}
```

- [ ] **Step 4: Chạy test để xác nhận xanh**

```bash
cd src-tauri && cargo test --lib 2>&1 | tail -15
```

Expected: PASS (4 test mới).

- [ ] **Step 5: Commit**

```bash
git add src-tauri/src/clipboard.rs
git commit -m "feat: detect clipboard changes via sequence number

GetClipboardSequenceNumber is a cheap integer read that needs no
OpenClipboard and never touches contents, so the poller can skip the
read entirely when nothing changed. Recording the sequence after our
own writes is also what breaks the PC-side echo loop.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: Watcher thread + thứ tự LAN-trước-đĩa

Poller rời tokio sang OS thread riêng, và gói tin LAN được gửi trước khi chạm đĩa.

**Files:**
- Create: `src-tauri/src/watcher.rs`
- Modify: `src-tauri/src/lib.rs:1-10` (khai báo mod), `src-tauri/src/lib.rs:1103-1135` (thay poller)

**Interfaces:**
- Consumes: `clipboard::clipboard_sequence`, `clipboard::should_read`, `clipboard::self_write_sequence`, `clipboard::read_clipboard`
- Produces:
  - `watcher::spawn_clipboard_watcher() -> tokio::sync::mpsc::UnboundedReceiver<ClipboardPayload>`
  - `watcher::latest_payload() -> Option<ClipboardPayload>` — cache dùng cho `session_hello` ở Task 9

- [ ] **Step 1: Tạo `watcher.rs`**

```rust
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use crate::clipboard::{self, ClipboardPayload};

const POLL_INTERVAL_MS: u64 = 200;

/// Payload clipboard mới nhất đã đọc được. Cho phép các đường xử lý khác
/// (ví dụ session_hello) lấy clipboard hiện tại mà không phải gọi Win32
/// đồng bộ ngay trên async runtime.
fn cache() -> &'static Mutex<Option<ClipboardPayload>> {
    static CACHE: OnceLock<Mutex<Option<ClipboardPayload>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(None))
}

pub(crate) fn latest_payload() -> Option<ClipboardPayload> {
    cache().lock().unwrap().clone()
}

/// Theo dõi clipboard trên OS thread riêng. Mọi thứ ở đây đều blocking —
/// Win32, decode ảnh, encode PNG — nên nó tuyệt đối không được chạy trên
/// worker thread của tokio.
pub(crate) fn spawn_clipboard_watcher() -> tokio::sync::mpsc::UnboundedReceiver<ClipboardPayload> {
    let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
    std::thread::Builder::new()
        .name("fastpaste-clipboard".into())
        .spawn(move || {
            let mut last_seen = clipboard::clipboard_sequence();
            if let Some(payload) = clipboard::read_clipboard() {
                *cache().lock().unwrap() = Some(payload);
            }
            loop {
                std::thread::sleep(Duration::from_millis(POLL_INTERVAL_MS));
                let current = clipboard::clipboard_sequence();
                if !clipboard::should_read(current, last_seen, clipboard::self_write_sequence()) {
                    continue;
                }
                last_seen = current;
                let Some(payload) = clipboard::read_clipboard() else {
                    continue;
                };
                if payload.text.is_empty() {
                    continue;
                }
                *cache().lock().unwrap() = Some(payload.clone());
                if tx.send(payload).is_err() {
                    break;
                }
            }
        })
        .expect("không tạo được clipboard watcher thread");
    rx
}
```

- [ ] **Step 2: Khai báo module**

Trong `src-tauri/src/lib.rs`, thêm vào danh sách `mod` (dòng 1-10), giữ thứ tự alphabet:

```rust
mod watcher;
```

- [ ] **Step 3: Thay poller trong `lib.rs`**

Thay toàn bộ khối "── Clipboard Poller ──" (`lib.rs:1103-1135`) bằng:

```rust
            // ── Clipboard watcher ──
            // Thứ tự ở đây quan trọng: gói tin LAN đi TRƯỚC, rồi mới tới đĩa,
            // UI và cloud. Trước đây save_state() chạy trước ws_tx.send() nên
            // mỗi lần copy phải chờ DPAPI + ghi file xong mới được gửi đi.
            let data_clip = data_arc.clone();
            let mut clipboard_rx = watcher::spawn_clipboard_watcher();
            tauri::async_runtime::spawn(async move {
                while let Some(payload) = clipboard_rx.recv().await {
                    let _ = ws_tx.send(if payload.kind == "text" {
                        payload.text.clone()
                    } else {
                        payload.protocol_json()
                    });

                    let history_changed = {
                        let mut d = data_clip.lock().unwrap();
                        let changed = promote_or_insert_payload(&mut d, &payload, "PC");
                        if changed {
                            save_state(&d);
                        }
                        changed
                    };
                    if history_changed {
                        broadcast_state(&app_handle_clip);
                        queue_cloud_sync(&app_handle_clip);
                    }
                }
            });
```

- [ ] **Step 4: Đánh dấu self-write ở các đường ghi clipboard qua Tauri**

`write_clipboard()` đã tự gọi `mark_self_write()` sau Task 4, nhưng các chỗ dùng `app.clipboard().write_text()` thì chưa. Thêm `crate::clipboard::mark_self_write();` ngay sau mỗi lần gọi tại:

- `lib.rs:223` (trong `copy_text`)
- `lib.rs:311-313` (trong `copy_history_item`)
- `lib.rs:405-407` (trong `update_history_item`)
- `lib.rs:685` (trong `add_history_item`)
- `network.rs:668` (nhánh plain text)
- `network.rs:750` (trong `handle_history_sync`)

Ví dụ tại `network.rs:668`:

```rust
        let _ = app.clipboard().write_text(text.clone());
        crate::clipboard::mark_self_write();
```

- [ ] **Step 5: Build và kiểm tra bằng tay**

```bash
cd src-tauri && cargo test --lib && cargo build
```

Chạy app, rồi kiểm tra hai điều:

1. Copy một file lớn trong Explorer → mở Task Manager, CPU của FastPaste phải về ~0%. Trước đây nó sẽ chạy liên tục.
2. Copy text trên Android → PC nhận. Nhìn lịch sử PC: nội dung đó chỉ được thêm **một** mục với `source` là `ANDROID`, không sinh thêm mục `PC` trùng nội dung 200ms sau.

- [ ] **Step 6: Commit**

```bash
git add src-tauri/src/watcher.rs src-tauri/src/lib.rs src-tauri/src/network.rs
git commit -m "perf: move clipboard polling off the async runtime, send LAN first

The poller ran read_clipboard() inside tauri::async_runtime::spawn, so
Win32 calls and PNG encoding blocked a tokio worker. When workers block,
the WS read half is not polled, pongs go out late, and OkHttp's 10s ping
deadline drops the Android connection.

It now runs on its own OS thread and only reads when the clipboard
sequence number actually changed.

The LAN send also moves ahead of save_state(), which previously made
every copy wait on DPAPI plus three file writes before reaching the wire.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 6: Writer thread có debounce cho state

`save_state()` đang chạy đồng bộ trên 39 call site, mỗi lần là clone toàn bộ history + 2 lần serialize + DPAPI + 5 thao tác file.

**Files:**
- Modify: `src-tauri/src/state.rs:143-178`
- Modify: `src-tauri/src/lib.rs` (khởi động writer, flush khi thoát)

**Interfaces:**
- Produces:
  - `state::save_state()` — **đổi signature, không còn tham số**; chỉ đánh dấu dirty
  - `state::flush_state_now(data: &AppStateData)` — ghi thật, chỉ writer thread và đường thoát app gọi
  - `state::spawn_state_writer(data: Arc<Mutex<AppStateData>>)`
  - `state::flush_on_exit(data: &Mutex<AppStateData>)`

- [ ] **Step 1: Đổi `save_state` thành debounce marker**

Trong `src-tauri/src/state.rs`, đổi tên hàm ghi hiện tại thành `flush_state_now` và thêm cơ chế dirty:

```rust
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};

const STATE_FLUSH_INTERVAL_MS: u64 = 500;
static DIRTY: AtomicBool = AtomicBool::new(false);

/// Đánh dấu state cần ghi. Không chạm đĩa — writer thread lo việc đó.
/// Trước đây hàm này ghi đồng bộ ngay tại chỗ, kể cả khi được gọi từ
/// trong task WS hay từ poller clipboard.
pub(crate) fn save_state() {
    DIRTY.store(true, Ordering::Release);
}

pub(crate) fn spawn_state_writer(data: Arc<Mutex<AppStateData>>) {
    std::thread::Builder::new()
        .name("fastpaste-state-writer".into())
        .spawn(move || loop {
            std::thread::sleep(std::time::Duration::from_millis(STATE_FLUSH_INTERVAL_MS));
            if !DIRTY.swap(false, Ordering::AcqRel) {
                continue;
            }
            let snapshot = data.lock().unwrap().clone();
            flush_state_now(&snapshot);
        })
        .expect("không tạo được state writer thread");
}

/// Ghi cưỡng bức trước khi thoát, để không mất thay đổi trong cửa sổ debounce.
pub(crate) fn flush_on_exit(data: &Mutex<AppStateData>) {
    if DIRTY.swap(false, Ordering::AcqRel) {
        let snapshot = data.lock().unwrap().clone();
        flush_state_now(&snapshot);
    }
}
```

Hàm ghi thật giữ nguyên thân cũ, chỉ đổi tên và **bỏ dòng `std::fs::copy` thừa**:

```rust
pub(crate) fn flush_state_now(data: &AppStateData) {
    if !VAULT_WRITABLE.load(Ordering::Acquire) {
        eprintln!("FastPaste: history vault đang bị khoá vì lần giải mã trước thất bại; không ghi đè dữ liệu.");
        return;
    }

    let mut persisted = data.clone();
    persisted.clients.clear();
    persisted.ips.clear();
    persisted.cloud.syncing = false;
    persisted.transfers.clear();
    let sensitive = SensitiveState::from_state(&persisted);
    persisted.history.clear();
    persisted.deleted_markers.clear();
    persisted.clear_history_at = None;
    persisted.app_icons.clear();
    persisted.history_backup = None;

    let result = (|| -> Result<(), String> {
        let vault_json = serde_json::to_vec(&sensitive).map_err(|error| error.to_string())?;
        // Ghi bản mã hoá trước. Nếu bước này hỏng thì settings.json còn
        // nguyên, bản cài plaintext cũ vẫn khôi phục được.
        vault::write_protected_atomic(&get_history_vault_path(), &vault_json)?;
        let public_json = serde_json::to_vec(&persisted).map_err(|error| error.to_string())?;
        // write_atomic đã tự tạo .bak trước khi thay file, nên không cần
        // copy thêm lần nữa ở đây.
        vault::write_atomic(&get_settings_path(), &public_json)
    })();
    if let Err(error) = result {
        eprintln!("FastPaste: không lưu được state an toàn: {error}");
    }
}
```

- [ ] **Step 2: Cập nhật 39 call site**

```bash
cd src-tauri && sed -i 's/save_state(&data)/save_state()/g; s/save_state(&d)/save_state()/g; s/save_state(&self\.data)/save_state()/g' src/*.rs
cd src-tauri && grep -rn "save_state(&" src/
```

Expected: không còn kết quả nào ngoài `state.rs` (chỗ `load_state` gọi — xử lý ở step tiếp theo).

- [ ] **Step 3: `load_state` phải ghi ngay, không debounce**

Trong `load_state`, hai chỗ gọi `save_state(&data)` (dòng 228, 258) là ghi kết quả migration — phải ghi thật ngay chứ không đợi writer thread (writer chưa chạy lúc này). Đổi thành `flush_state_now(&data);`.

- [ ] **Step 4: Khởi động writer và flush khi thoát**

Trong `src-tauri/src/lib.rs`, trong `setup`, ngay sau `app.manage(AppState(data_arc.clone()));`:

```rust
            state::spawn_state_writer(data_arc.clone());
```

Trong handler menu tray (`lib.rs:998-1002`), đường `"quit"` đang gọi `std::process::exit(0)` — bỏ qua cả destructor lẫn writer thread, nên thay đổi trong cửa sổ 500ms sẽ mất:

```rust
                .on_menu_event(move |app, event| match event.id().as_ref() {
                    "show" => show_window(&quit_handle),
                    "quit" => {
                        if let Some(state) = app.try_state::<AppState>() {
                            state::flush_on_exit(&state.0);
                        }
                        std::process::exit(0);
                    }
                    _ => {}
                })
```

Lưu ý: closure hiện đang nhận `_app`; đổi thành `app` để dùng được.

- [ ] **Step 5: Build và test**

```bash
cd src-tauri && cargo test --lib && cargo build 2>&1 | tail -10
```

Kiểm tra bằng tay: chạy app, copy vài chuỗi liên tiếp, thoát qua tray → mở lại, lịch sử phải còn đủ.

- [ ] **Step 6: Commit**

```bash
git add src-tauri/src/state.rs src-tauri/src/lib.rs src-tauri/src/network.rs src-tauri/src/hotkeys.rs
git commit -m "perf: debounce state persistence onto a writer thread

save_state ran synchronously at 39 call sites — clone of the full
history, two serializations, DPAPI, and five file operations, some of
them from inside WS tasks and the clipboard poller.

It now marks dirty and returns; a dedicated thread flushes at most every
500ms and on exit. Also drops the redundant fs::copy to settings.bak,
which write_atomic already produces.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 7: Khởi động không chặn

`load_state()` chạy trước `tauri::Builder`, nên với lịch sử lớn thì không có cửa sổ nào xuất hiện cho tới khi nó xong.

**Files:**
- Modify: `src-tauri/src/lib.rs:957-1145`
- Modify: `src-tauri/src/state.rs` (tách `hydrate_running_app_icons` khỏi `load_state`)

**Interfaces:**
- Consumes: `state::empty_state()` (từ Task 2), `state::load_state()`, `history::hydrate_running_app_icons`
- Produces: `state::hydrate_icons_later(data: &Arc<Mutex<AppStateData>>) -> bool` — chạy sau khi UI đã hiện

- [ ] **Step 1: Gỡ hydrate icon khỏi `load_state`**

Trong `src-tauri/src/state.rs`, xoá dòng `let icons_changed = hydrate_running_app_icons(&mut data);` (dòng 223) và bỏ `icons_changed` khỏi điều kiện ghi ở dòng 226. Thêm hàm mới:

```rust
/// Enumerate toàn bộ process đang chạy để lấy icon — đắt, và không có lý do
/// gì phải chặn lần hiện cửa sổ đầu tiên vì nó.
pub(crate) fn hydrate_icons_later(data: &Mutex<AppStateData>) -> bool {
    let mut guard = data.lock().unwrap();
    let changed = hydrate_running_app_icons(&mut guard);
    if changed {
        save_state();
    }
    changed
}
```

- [ ] **Step 2: Đổi `run()` sang khởi động state rỗng**

Trong `src-tauri/src/lib.rs`, thay dòng 959-960:

```rust
pub fn run() {
    // Khởi động với state rỗng để cửa sổ hiện ra ngay. State thật load trên
    // thread nền rồi phát về UI qua update_state. Trước đây load_state()
    // chạy đồng bộ ở đây — giải mã DPAPI, deserialize tới 1000 mục kèm ảnh
    // base64, enumerate process — nên app có thể không hiện gì rất lâu.
    let data_arc = Arc::new(Mutex::new(state::empty_state()));
    let (ws_tx, _ws_rx) = tokio::sync::broadcast::channel::<String>(100);
```

- [ ] **Step 3: Load state trên thread nền**

Trong `setup`, thay khối đăng ký hotkey ở cuối (`lib.rs:1137-1142`) bằng loader thread. Đặt nó ngay trước `Ok(())`:

```rust
            // ── Load state nền ──
            // Hotkey phải đăng ký sau khi có settings thật, và phải chạy trên
            // main thread vì global-shortcut của Windows yêu cầu vậy.
            let load_handle = app.handle().clone();
            let load_data = data_arc.clone();
            std::thread::Builder::new()
                .name("fastpaste-state-loader".into())
                .spawn(move || {
                    let loaded = load_state();
                    let settings = loaded.settings.clone();
                    {
                        let mut guard = load_data.lock().unwrap();
                        let ips = std::mem::take(&mut guard.ips);
                        *guard = loaded;
                        guard.ips = ips;
                    }

                    let hotkey_handle = load_handle.clone();
                    let _ = load_handle.run_on_main_thread(move || {
                        register_initial_hotkeys(&hotkey_handle, &settings);
                    });

                    broadcast_state(&load_handle);
                    state::hydrate_icons_later(&load_data);
                    broadcast_state(&load_handle);

                    if cloud::is_configured() && cloud::is_signed_in() {
                        queue_cloud_sync(&load_handle);
                    }
                })
                .expect("không tạo được state loader thread");

            Ok(())
```

- [ ] **Step 4: Gỡ startup cloud sync cũ**

Khối `if cloud::is_configured() && cloud::is_signed_in() { ... sleep(STARTUP_CLOUD_SYNC_DELAY_MS) ... }` (`lib.rs:1081-1087`) giờ thừa — loader thread đã gọi `queue_cloud_sync` sau khi load xong, và bản thân cloud sync đã có debounce. Xoá khối đó và hằng `STARTUP_CLOUD_SYNC_DELAY_MS` (`lib.rs:27`).

- [ ] **Step 5: Xử lý autostart đọc settings sớm**

Khối `should_refresh_autostart` (`lib.rs:1089-1097`) đọc `data.settings.auto_start` — lúc này state còn rỗng nên luôn đọc ra `false`. Chuyển nó vào trong loader thread, sau khi state đã load:

```rust
                    if settings.auto_start {
                        let autostart_handle = load_handle.clone();
                        let _ = load_handle.run_on_main_thread(move || {
                            use tauri_plugin_autostart::ManagerExt;
                            let manager = autostart_handle.autolaunch();
                            let _ = manager.disable();
                            let _ = manager.enable();
                        });
                    }
```

Xoá khối cũ ở dòng 1089-1097.

- [ ] **Step 6: Build và đo**

```bash
cd src-tauri && cargo test --lib && cargo build --release 2>&1 | tail -5
```

Kiểm tra bằng tay: chạy `src-tauri/target/release/FastPaste.exe`, bấm đồng hồ từ lúc chạy tới lúc cửa sổ hiện. Phải dưới 1 giây kể cả khi `history.vault` lớn. Sau đó lịch sử phải tự xuất hiện trong danh sách vài trăm ms sau đó, và hotkey toàn cục phải hoạt động.

- [ ] **Step 7: Commit**

```bash
git add src-tauri/src/lib.rs src-tauri/src/state.rs
git commit -m "perf: load state in the background instead of before the window

load_state ran synchronously before tauri::Builder — DPAPI decryption,
deserializing up to 1000 entries with inline base64 images, and a full
process enumeration for app icons — so a large history meant no window
appeared at all. Combined with the single_instance plugin, relaunching
just called show_window on the stuck process.

The window now opens on empty state; the real one arrives via
update_state, with hotkeys registered on the main thread once settings
are known.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 8: Chặn state file phình vô hạn

Ảnh vẫn lưu inline base64 (quyết định trong spec), nên phải giới hạn số mục mang payload đầy đủ, nếu không mọi lần flush lại là hàng trăm MB.

**Files:**
- Modify: `src-tauri/src/history.rs`
- Modify: `src-tauri/src/state.rs` (gọi khi flush)

**Interfaces:**
- Produces: `history::trim_inline_payloads(history: &mut [HistoryItem]) -> bool` — giữ `data` đầy đủ cho `MAX_INLINE_PAYLOADS` mục ảnh mới nhất, các mục cũ hơn chỉ còn `text` + `thumbnail`

- [ ] **Step 1: Viết test (sẽ fail)**

Thêm vào `mod file_migration_tests` trong `src-tauri/src/history.rs` (đổi tên mod thành `history_maintenance_tests`):

```rust
    fn image_item(label: &str, millis: i64) -> HistoryItem {
        let mut item = make_history_item_at(label, "PC", millis, SourceMetadata::default());
        item.payload = Some(ClipboardPayload {
            kind: "image".into(),
            text: label.into(),
            data: "QUJDRA==".into(),
            thumbnail: "data:image/png;base64,AAAA".into(),
            ..ClipboardPayload::default()
        });
        item
    }

    #[test]
    fn newest_images_keep_full_data() {
        let mut history: Vec<HistoryItem> = (0..MAX_INLINE_PAYLOADS)
            .map(|index| image_item(&format!("ảnh {index}"), 1_000 + index as i64))
            .collect();

        assert!(!trim_inline_payloads(&mut history));
        assert!(history
            .iter()
            .all(|item| !item.payload.as_ref().unwrap().data.is_empty()));
    }

    #[test]
    fn images_past_the_cap_keep_only_thumbnail_and_label() {
        let mut history: Vec<HistoryItem> = (0..MAX_INLINE_PAYLOADS + 3)
            .map(|index| image_item(&format!("ảnh {index}"), 1_000 + index as i64))
            .collect();
        // Mới nhất đứng đầu, đúng như trim_history sắp xếp.
        history.sort_by_key(|item| std::cmp::Reverse(timestamp_to_millis(&item.timestamp)));

        assert!(trim_inline_payloads(&mut history));

        let kept = &history[0].payload.as_ref().unwrap();
        assert!(!kept.data.is_empty());

        let dropped_index = MAX_INLINE_PAYLOADS + 1;
        let dropped = history[dropped_index].payload.as_ref().unwrap();
        assert!(dropped.data.is_empty(), "ảnh cũ phải bỏ data");
        assert!(!dropped.thumbnail.is_empty(), "nhưng vẫn xem được preview");
        assert_eq!(history[dropped_index].text, format!("ảnh {}", MAX_INLINE_PAYLOADS + 3 - 1 - dropped_index));
    }
```

- [ ] **Step 2: Chạy test để xác nhận fail**

```bash
cd src-tauri && cargo test --lib history_maintenance 2>&1 | tail -15
```

Expected: FAIL — `cannot find function trim_inline_payloads`, `cannot find value MAX_INLINE_PAYLOADS`.

- [ ] **Step 3: Implement**

Thêm vào `src-tauri/src/history.rs`, cạnh `MAX_HISTORY_ITEMS`:

```rust
/// Số mục ảnh được giữ nguyên dữ liệu base64. Mục cũ hơn chỉ giữ nhãn và
/// thumbnail — vẫn xem được preview, nhưng không copy lại được nữa. Không
/// có giới hạn này thì mỗi lần flush state là ghi lại hàng trăm MB.
pub(crate) const MAX_INLINE_PAYLOADS: usize = 50;
```

```rust
/// Giả định `history` đã được sắp xếp mới-nhất-trước (trim_history làm việc đó).
pub(crate) fn trim_inline_payloads(history: &mut [HistoryItem]) -> bool {
    let mut kept = 0usize;
    let mut changed = false;
    for item in history.iter_mut() {
        let Some(payload) = item.payload.as_mut() else {
            continue;
        };
        if payload.data.is_empty() {
            continue;
        }
        if kept < MAX_INLINE_PAYLOADS {
            kept += 1;
            continue;
        }
        payload.data.clear();
        item.blob_ready = false;
        changed = true;
    }
    changed
}
```

- [ ] **Step 4: Chạy test để xác nhận xanh**

```bash
cd src-tauri && cargo test --lib history_maintenance 2>&1 | tail -10
```

Expected: PASS (2 test).

- [ ] **Step 5: Gọi khi flush**

Trong `src-tauri/src/state.rs`, trong `flush_state_now`, ngay sau `let mut persisted = data.clone();`:

```rust
    crate::history::trim_history(&mut persisted.history);
    crate::history::trim_inline_payloads(&mut persisted.history);
```

Ghi chú: chỉ cắt trên bản `persisted` (bản đem đi ghi), không đụng vào state đang chạy trong bộ nhớ — mục vừa bị cắt data trên đĩa vẫn copy lại được cho tới khi app khởi động lại.

- [ ] **Step 6: Build và test**

```bash
cd src-tauri && cargo test --lib 2>&1 | tail -10
```

Expected: PASS toàn bộ.

- [ ] **Step 7: Commit**

```bash
git add src-tauri/src/history.rs src-tauri/src/state.rs
git commit -m "perf: cap how many image payloads stay inline in the vault

Images stay base64-inline by design, so without a cap every flush
rewrites the entire accumulated history. The newest 50 keep their data;
older ones keep the label and thumbnail so previews still render.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 9: Server-side ping và bỏ đọc clipboard trong `session_hello`

**Files:**
- Modify: `src-tauri/src/network.rs:23-24` (hằng), `:231-269` (sender task), `:372` (session_hello)

**Interfaces:**
- Consumes: `watcher::latest_payload()` (từ Task 5)
- Produces: không có API mới

- [ ] **Step 1: Đổi hằng timeout**

Trong `src-tauri/src/network.rs`:

```rust
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
/// Kết nối im lặng bị cắt sau ngần này. Phải dài hơn hẳn chu kỳ ping của
/// server, nếu không một thiết bị đang doze sẽ bị cắt oan.
const CLIENT_IDLE_TIMEOUT: Duration = Duration::from_secs(60);
/// Server tự ping để giữ kết nối sống và phát hiện peer chết, thay vì chỉ
/// dựa vào ping của OkHttp bên Android.
const SERVER_PING_INTERVAL: Duration = Duration::from_secs(15);
```

- [ ] **Step 2: Thêm nhánh ping vào sender task**

Trong `handle_client`, sửa vòng `tokio::select!` của `sender_task` (`network.rs:236-268`), thêm nhánh ticker:

```rust
        let mut ping_ticker = tokio::time::interval(SERVER_PING_INTERVAL);
        ping_ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

        loop {
            tokio::select! {
                _ = ping_ticker.tick() => {
                    // Message::Ping nhận Bytes; Default::default() cho payload rỗng
                    // mà không cần import bytes::Bytes.
                    if write.send(Message::Ping(Default::default())).await.is_err() { break; }
                }
                direct = direct_rx.recv() => {
                    let Some(direct) = direct else { break };
                    match direct {
                        DirectMessage::Plain(message) => {
                            if write.send(Message::Text(message.into())).await.is_err() { break; }
                        }
                        DirectMessage::App(message) => {
                            if let Some(message) = protect_for_client(&data_sync, &sender_session, message) {
                                if write.send(Message::Text(message.into())).await.is_err() { break; }
                            }
                        }
                        DirectMessage::AppBinary(frame) => {
                            if let Some(frame) = protect_binary_for_client(&data_sync, &sender_session, frame) {
                                if write.send(Message::Binary(frame.into())).await.is_err() { break; }
                            }
                        }
                    }
                }
                received = rx.recv() => match received {
                    Ok(message) => {
                        if let Some(message) = protect_for_client(&data_sync, &sender_session, message) {
                            if write.send(Message::Text(message.into())).await.is_err() { break; }
                        }
                    }
                    // Client chậm có thể tụt lại sau broadcast channel; bỏ qua
                    // phần backlog bị mất chứ không giết kết nối.
                    Err(broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(broadcast::error::RecvError::Closed) => break,
                }
            }
        }
```

- [ ] **Step 3: Dùng cache thay vì đọc clipboard trực tiếp**

Tại `network.rs:372`, trong nhánh `session_hello`, thay:

```rust
                            let current_clipboard = clipboard::read_clipboard();
```

bằng:

```rust
                            // Đọc clipboard là thao tác Win32 blocking; ở đây
                            // đang ở trong task WS nên phải lấy từ cache của
                            // watcher thread thay vì gọi thẳng.
                            let current_clipboard = crate::watcher::latest_payload();
```

- [ ] **Step 4: Build và kiểm tra**

```bash
cd src-tauri && cargo test --lib && cargo build 2>&1 | tail -5
```

Kiểm tra bằng tay: kết nối Android, khoá màn hình điện thoại và để yên 3 phút, mở lại → kết nối phải vẫn còn (thông báo vẫn hiện "Đã kết nối"), không phải reconnect lại từ đầu.

- [ ] **Step 5: Commit**

```bash
git add src-tauri/src/network.rs
git commit -m "fix: keep idle WS connections alive with server-side pings

The server never pinged and cut anything silent for 45s, so a dozing
Android device was dropped and forced through a full reconnect. Each
reconnect then called read_clipboard() synchronously inside the WS task.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 10: Đẩy công việc blocking ra khỏi task WebSocket

`write_clipboard()` (decode ảnh, Win32) vẫn đang gọi trực tiếp trong task WS ở 4 chỗ.

**Files:**
- Modify: `src-tauri/src/network.rs:317`, `:508`, `:629`, `:668`, `:735-758`

**Interfaces:**
- Consumes: `clipboard::write_clipboard`, `clipboard::mark_self_write`
- Produces: `network::apply_clipboard(payload: ClipboardPayload)` — async, chạy phần blocking qua `spawn_blocking`

- [ ] **Step 1: Thêm helper**

Thêm vào `src-tauri/src/network.rs`:

```rust
/// Ghi clipboard là thao tác Win32 blocking (kèm decode ảnh). Gọi thẳng
/// trong task WS sẽ block worker của tokio và làm trễ pong của các kết nối
/// khác, nên nó phải đi qua thread pool blocking.
async fn apply_clipboard(payload: ClipboardPayload) {
    let _ = tokio::task::spawn_blocking(move || clipboard::write_clipboard(&payload)).await;
}

/// Bản dành cho plain text — cũng blocking, cũng phải đánh dấu self-write.
async fn apply_clipboard_text(app: AppHandle, text: String) {
    let _ = tokio::task::spawn_blocking(move || {
        let result = app.clipboard().write_text(text);
        if result.is_ok() {
            clipboard::mark_self_write();
        }
        result
    })
    .await;
}
```

- [ ] **Step 2: Thay 4 call site**

`network.rs:317` (nhánh binary chunk hoàn tất):

```rust
                    if let Some(payload) = outcome.payload {
                        apply_clipboard(payload.clone()).await;
                        let mut d = data.lock().unwrap();
                        if history::promote_or_insert_payload(&mut d, &payload, "ANDROID") {
                            save_state();
                        }
                    }
```

`network.rs:508` (nhánh `blob_chunk` JSON):

```rust
                            if let Some(payload) = outcome.payload {
                                apply_clipboard(payload.clone()).await;
                                let changed = {
                                    let mut d = data.lock().unwrap();
                                    let changed = history::promote_or_insert_payload(
                                        &mut d, &payload, "ANDROID",
                                    );
                                    if changed {
                                        save_state();
                                    }
                                    changed
                                };
                                if changed {
                                    broadcast_state(&app);
                                    queue_cloud_sync(&app);
                                }
                            }
```

`network.rs:629` (nhánh `clipboard_payload`):

```rust
                if let Some(payload) = protocol.payload {
                    apply_clipboard(payload.clone()).await;
                    let history_changed = {
                        let mut d = data.lock().unwrap();
                        let changed =
                            history::promote_or_insert_payload(&mut d, &payload, "ANDROID");
                        if changed {
                            save_state();
                        }
                        changed
                    };
                    if history_changed {
                        broadcast_state(&app);
                        queue_cloud_sync(&app);
                    }
                    continue;
                }
```

`network.rs:668` (nhánh plain text) — bỏ dòng `mark_self_write` đã thêm ở Task 5 vì helper lo rồi:

```rust
        // Text thô = dán clipboard ngay lập tức từ thiết bị.
        apply_clipboard_text(app.clone(), text.clone()).await;
        let history_changed = {
            let mut d = data.lock().unwrap();
            let changed = history::promote_or_insert_history(&mut d, &text, "ANDROID");
            if changed {
                save_state();
            }
            changed
        };
```

- [ ] **Step 3: Sửa `handle_history_sync` thành async**

`handle_history_sync` (`network.rs:735`) cũng gọi `write_clipboard`. Đổi thành `async fn` và await ở 2 call site (`network.rs:564`, `:623`):

```rust
async fn handle_history_sync(app: &AppHandle, data: &Mutex<AppStateData>, entries: Vec<SyncEntry>) {
    let (newest_incoming, history_changed, latest_local_timestamp) = {
        let mut d = data.lock().unwrap();
        let result = history::merge_sync_entries(&mut d, entries);
        if result.1 {
            save_state();
        }
        result
    };

    if let Some(entry) = newest_incoming {
        if entry.timestamp > latest_local_timestamp {
            if let Some(payload) = entry.payload {
                apply_clipboard(payload).await;
            } else {
                apply_clipboard_text(app.clone(), entry.text).await;
            }
        }
    }
    if history_changed {
        broadcast_state(app);
        queue_cloud_sync(app);
    }
}
```

Hai call site đổi thành `handle_history_sync(&app, &data, vec![entry]).await;` và `handle_history_sync(&app, &data, protocol.entries.unwrap_or_default()).await;`.

- [ ] **Step 4: Bỏ `mark_self_write` thủ công đã thêm ở Task 5**

Task 5 thêm `crate::clipboard::mark_self_write();` sau `app.clipboard().write_text()` ở `network.rs:668` và `:750`. Cả hai giờ đi qua `apply_clipboard_text` nên xoá hai dòng thủ công đó. Các call site trong `lib.rs` giữ nguyên (chúng chạy trong IPC command, không phải task WS).

- [ ] **Step 5: Build và test**

```bash
cd src-tauri && cargo test --lib && cargo build 2>&1 | tail -10
```

Expected: compile sạch. Nếu báo lỗi `MutexGuard` không `Send` qua `.await`, nghĩa là còn chỗ giữ lock qua `await` — tách khối lock ra thành scope riêng như mẫu trên.

- [ ] **Step 6: Đo kết quả cuối**

Chạy app, kết nối Android, rồi ghi lại:

1. Copy text trên PC → độ trễ tới lúc clipboard Android đổi. Mục tiêu: dưới 500ms.
2. Copy ảnh 5MB trên PC → Android nhận được, UI PC không đứng hình.
3. Copy liên tục 20 chuỗi trong 10 giây → không rớt kết nối, không có mục trùng trong lịch sử.
4. Task Manager: CPU FastPaste khi idle ~0%.

- [ ] **Step 7: Commit**

```bash
git add src-tauri/src/network.rs
git commit -m "perf: run clipboard writes off the WS task via spawn_blocking

write_clipboard decodes images and calls Win32 synchronously. Running it
inside the per-client WS task blocked a tokio worker, delaying pongs on
every other connection and feeding the reconnect churn.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Self-review

**Spec coverage (GĐ 0, 1, 2, 4):**

| Yêu cầu trong spec | Task |
|---|---|
| §2.0 sửa test gãy + CI gate | Task 1 |
| §5.1 gỡ `read_files`/`write_files`/`ClipboardFile`/`files` | Task 2 |
| §5.1 migration mục `kind == "files"` | Task 2 (step 1-5, 7) |
| §5.1 gỡ file phía Android, giữ nhánh ảnh | Task 3 |
| §5.1 `GetClipboardSequenceNumber` | Task 4 |
| §5.1 chống echo bằng sequence number | Task 4 + Task 5 (step 4) |
| §5.2 poller sang thread riêng | Task 5 |
| §5.5 thứ tự LAN-trước-đĩa | Task 5 (step 3) |
| §5.3 writer thread có debounce | Task 6 |
| §5.3 bỏ `fs::copy` thừa | Task 6 (step 1) |
| §5.3 khởi động không chặn | Task 7 |
| §5.3 `hydrate_running_app_icons` rời đường khởi động | Task 7 (step 1) |
| §5.3 chặn phình state file | Task 8 |
| §5.5 server-side ping, timeout 60s | Task 9 |
| §5.5 `session_hello` không đọc clipboard | Task 9 (step 3) |
| §5.5 đẩy blocking ra khỏi task WS | Task 10 |

Ba mục trong §5.5 **không** thuộc plan này và chuyển sang plan 2: chunk PC→Android hai chiều, `ws_tx` dùng `Arc` thay vì clone `String`, và việc gộp `broadcast_state` trong đường transfer (thuộc GĐ 3).

**Type consistency:** `save_state()` không tham số (Task 6) được dùng nhất quán ở Task 10. `flush_state_now(&AppStateData)` chỉ gọi từ `load_state` và writer thread. `empty_state()` do Task 2 tạo, Task 7 dùng lại. `watcher::latest_payload()` do Task 5 tạo, Task 9 dùng. `drop_file_payloads` / `trim_inline_payloads` đều nhận `&mut` và trả `bool`.

---

## Ngoài phạm vi plan này

Sẽ nằm trong plan 2 và 3:

- **GĐ 3** — chia `update_state` thành `update_settings`/`update_history`/`update_transfers`, thumbnail lazy, frontend diff + virtualize
- **GĐ 5** — pairing fail-visible: `pair_required`, banner desktop, 3 trạng thái kết nối Android
- **GĐ 6** — cloud debounce theo deadline cố định + delta sync
- **GĐ 7** — Android: tách fingerprint hai chiều, một chủ `ServiceDiscovery`, tách `ClipboardService.kt` và `HomeScreen.kt`
- **GĐ 8** — cập nhật `CLAUDE.md`

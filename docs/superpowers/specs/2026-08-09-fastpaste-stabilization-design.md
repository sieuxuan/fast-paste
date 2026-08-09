# FastPaste — Kế hoạch ổn định hoá toàn diện

Ngày: 2026-08-09
Phạm vi: desktop (Tauri/Rust), Android (Kotlin), frontend (`src/index.html`)
Điểm xuất phát: `5e07d29` — commit thêm E2EE, pairing, secure storage (~5000 dòng, 2 app, chưa chạy test)

---

## 1. Triệu chứng người dùng báo

1. App treo, không mở lên được
2. Cần bỏ tính năng copy file
3. App Android bị lỗi
4. Sync chậm — cả LAN lẫn Google Drive
5. WebSocket không ổn định

Thứ tự ưu tiên do người dùng xác định: **LAN WebSocket là đường đồng bộ chính, Google Drive là dự phòng.**

---

## 2. Chẩn đoán

### 2.0. Test suite đang gãy

```
cargo check --tests → error: could not compile `fast-paste` (lib test) due to 2 previous errors
```

- `src-tauri/src/transfer.rs:422` — khởi tạo `BlobRequest` thiếu field `window_size`
- `src-tauri/src/transfer.rs:435` — `ReceiveOutcome::control` là `Option<String>` nhưng dùng như `&str`

`cargo check` (không `--tests`) vẫn pass, nên lỗi lọt qua. Commit cuối chưa từng được chạy test.

### 2.1. Treo khi khởi động

`src-tauri/src/lib.rs:959` — `load_state()` chạy **đồng bộ, trên main thread, trước `tauri::Builder`**, tức trước khi tồn tại bất kỳ cửa sổ nào. Công việc phải hoàn tất trước khi UI xuất hiện:

- đọc `settings.json` + `history.vault`, giải mã DPAPI toàn bộ vault
- deserialize tối đa **1000 mục** (`history.rs:8`, `MAX_HISTORY_ITEMS = 1_000`) kèm base64 ảnh/file nhúng inline
- `hydrate_running_app_icons()` (`history.rs:262`) — enumerate toàn bộ process đang chạy để trích icon
- gọi `save_state()` (`state.rs:228`, `state.rs:258`) → serialize lại + DPAPI + ghi **3 file** (`state.rs:165-171`: vault, `settings.json`, và `fs::copy` sang `.bak`)

Với lịch sử đã tích ảnh/file, đây là hàng trăm MB JSON trước khi UI xuất hiện.

Khuếch đại bởi plugin `single_instance` (`lib.rs:972`): khi đã có instance đang treo, lần mở sau chỉ gọi `show_window` lên process đó rồi tự thoát → "không mở lên được".

### 2.2. Copy file — tải nặng nhất hệ thống

`clipboard.rs:117` — `read_files()` là nhánh **đầu tiên** của `read_clipboard()`, mà `read_clipboard()` chạy trong poller **mỗi 250ms** (`lib.rs:1110`).

Hệ quả: chỉ cần một file nằm trên clipboard, FastPaste **đọc lại toàn bộ file từ đĩa (tới 64MB) và base64-encode, 4 lần/giây, vô hạn** (`clipboard.rs:252-271`). Sau đó `fingerprint()` (`clipboard.rs:58`) serialize cả chuỗi base64 ra JSON rồi SHA-256 lên đó.

Ảnh chịu cùng cơ chế: `read_image()` (`clipboard.rs:155`) decode bitmap → **re-encode PNG** → sinh thumbnail, mỗi 250ms.

### 2.3. WebSocket không ổn định — do starvation, không do giao thức

Đã loại trừ giả thuyết thiếu pong: tungstenite `WebSocketContext::read()` tự flush `additional_send` ngay đầu mỗi vòng lặp, nên pong vẫn được gửi từ read half kể cả khi stream đã `split()`.

Nguyên nhân thực: **mọi công việc blocking chạy bên trong `tauri::async_runtime::spawn`**, block thẳng worker thread của tokio:

| Vị trí | Công việc blocking |
|---|---|
| `lib.rs:1110` | `read_clipboard()` — Win32 + đọc đĩa + encode PNG |
| `lib.rs:1118` | `save_state()` — DPAPI + 3 lần ghi file |
| `network.rs:317`, `:508`, `:629`, `:668` | `write_clipboard()` — Win32 + decode ảnh |
| `network.rs:321`, `:516`, `:636`, `:674`, `:740` | `save_state()` |
| `network.rs:372` | `read_clipboard()` trong đường xử lý `session_hello` |

Khi worker bị block, read half của WS không được poll → pong không kịp gửi → OkHttp ping deadline 10s (`WebSocketClient.kt:26`) hết hạn → Android rớt kết nối và reconnect.

Mỗi reconnect lại tốn tiếp: `session_hello` gọi `read_clipboard()` đồng bộ (`network.rs:372`) cộng full history delta → vòng xoáy reconnect.

Bổ sung: `CLIENT_IDLE_TIMEOUT = 45s` (`network.rs:24`) nhưng server **không tự gửi ping**. Khi Android vào doze và ngừng ping, server cắt kết nối.

### 2.4. Sync chậm

#### LAN

Thứ tự thao tác trong poller (`lib.rs:1113-1130`) đang là:

```
save_state()  →  broadcast_state()  →  queue_cloud_sync()  →  ws_tx.send()
```

Gói tin LAN bị xếp hàng **sau** một lần ghi đĩa + DPAPI toàn bộ lịch sử và một lần emit full-state. `ws_tx.send()` đáng lẽ phải đi đầu tiên.

`broadcast_state()` (`state.rs:285`) clone toàn bộ state rồi emit **cả 1000 mục kèm thumbnail (tới 384KB/mục theo `MAX_THUMBNAIL_CHARS`)** sang webview mỗi lần thay đổi. Frontend (`index.html:3615`) gọi `render()` dựng lại toàn bộ danh sách, không diff, không virtualize.

Trong lúc truyền blob, `broadcast_state` bị gọi **2 lần cho mỗi chunk 48KB** (`network.rs:520` và `network.rs:524`) → một ảnh 10MB sinh ~400 lần emit full-state.

PC→Android **không chunk**: `protocol_json()` (`clipboard.rs:70`) nhét cả base64 vào một text frame duy nhất, trong khi hệ thống chunked transfer đã tồn tại nhưng chỉ dùng cho chiều Android→PC (`clipboard_blob_offer`).

`ws_tx` là `broadcast::Sender<String>`: mỗi frame lớn bị **clone cho từng subscriber**.

#### Echo loop (nhân đôi mọi chi phí)

Khi clipboard từ Android được ghi vào PC (`network.rs:629`, `network.rs:668`, `network.rs:748-751`), biến `last_clipboard_key` của poller (`lib.rs:1106`) **không được cập nhật**. 250ms sau, poller thấy nội dung đó như một lần copy mới của PC và:

- chèn lại vào history với `source = "PC"`
- **phát ngược lại cho Android** qua `ws_tx.send()`
- `save_state()` lần nữa
- `broadcast_state()` lần nữa
- `queue_cloud_sync()` lần nữa

#### Google Drive

`lib.rs:1046-1057`, vòng debounce viết sai:

```rust
loop {
    sleep(CLOUD_SYNC_DEBOUNCE_MS).await;   // 3s
    let mut received_more = false;
    while cloud_sync_rx.try_recv().is_ok() { received_more = true; }
    if !received_more { break; }
}
```

Mỗi request mới **khởi động lại trọn 3 giây**. Echo loop ở trên bắn thêm một `queue_cloud_sync` sau ~250ms mỗi lần copy → **mỗi lần copy tự gia hạn debounce**. Copy liên tục → Drive sync bị đói vô hạn.

Ngoài ra mỗi lần sync upload **toàn bộ** lịch sử (`history.rs:981` — `history_to_cloud_entries` clone cả `payload`), không có delta.

### 2.5. Android

#### Pairing bắt buộc nhưng fail im lặng — lỗi nghiêm trọng nhất

`network.rs:406-409` bỏ mọi message từ peer chưa pair. `protect_for_client` (`network.rs:711`) trả `None` khi chưa có session → **server không gửi gì cả**.

Comment tại `network.rs:226-228` ghi *"legacy peers receive the initial sync immediately"* — code làm ngược lại chính comment của nó.

Hệ quả: mọi cài đặt Android hiện có, sau khi cập nhật desktop, sẽ **kết nối TCP/WS thành công nhưng không đồng bộ gì**, và không hiện bất kỳ thông báo lỗi nào. Người dùng thấy "đã kết nối" nhưng clipboard không chạy.

#### Các lỗi còn lại

- `lastSyncedFingerprint` (`ClipboardService.kt:60`) là `var` **không `@Volatile`**, dùng chung cho **cả hai chiều gửi/nhận**, bị ghi từ 7 vị trí trên nhiều coroutine `Dispatchers.IO` (`:91`, `:335`, `:373`, `:545`, `:716`, `:789`). Vừa là race condition, vừa khiến việc copy lại một mục vừa nhận về bị nuốt im lặng.
- Hai `ServiceDiscovery` chạy song song: `MainViewModel.kt:72` và `ClipboardService.kt:211`.
- `ClipboardService.kt` 982 dòng, `HomeScreen.kt` 69KB — vượt ngưỡng dễ đọc/dễ sửa.

### 2.6. Tài liệu lệch thực tế

`CLAUDE.md` ghi history tối đa 500 items; thực tế `MAX_HISTORY_ITEMS = 1_000`. Không đề cập các module `crypto.rs`, `pairing.rs`, `transfer.rs`, `vault.rs`, `clipboard.rs`.

---

## 3. Quyết định thiết kế đã chốt

| Vấn đề | Quyết định |
|---|---|
| E2EE + pairing | **Giữ E2EE.** Chuyển từ fail-closed-im-lặng sang fail-visible: báo rõ trạng thái chưa ghép đôi trên cả 2 app, bổ sung luồng QR hoàn chỉnh |
| Copy file | **Gỡ bỏ hoàn toàn** khỏi cả 2 app |
| Copy ảnh | **Giữ.** Thay cơ chế phát hiện clipboard bằng `GetClipboardSequenceNumber()` thay vì đọc lại nội dung mỗi 250ms |
| Triển khai | Viết spec + implementation plan trước; người dùng quyết định thời điểm thực thi |

---

## 4. Nguyên tắc thiết kế

1. **Không có công việc blocking nào chạy trên async runtime.** Win32 clipboard, DPAPI, ghi file, encode ảnh đều thuộc về thread riêng.
2. **Đường LAN đi trước mọi thứ khác.** Thứ tự bắt buộc: gửi WS → lưu state → cập nhật UI → xếp hàng cloud.
3. **Trạng thái UI phải tăng trưởng theo delta, không phải toàn bộ snapshot.**
4. **Dữ liệu nặng (base64) không bao giờ đi qua UI event.**
5. **Thất bại phải nhìn thấy được.** Không có đường dẫn nào im lặng nuốt dữ liệu.
6. **Mỗi giai đoạn phải để lại app chạy được**, không có giai đoạn nào "hỏng tạm thời".

---

## 5. Thiết kế theo module

### 5.1. `clipboard.rs` — gỡ file, đổi cơ chế phát hiện

**Gỡ bỏ:**
- `read_files()`, `write_files()` (`clipboard.rs:217`, `:297`)
- struct `ClipboardFile`, field `files` trong `ClipboardPayload`
- toàn bộ code HDROP/DROPFILES, `mime_for_name()`, `sanitize_file_name()`
- nhánh `"files"` trong `write_clipboard()`
- Android: nhánh file trong `AndroidClipboardCodec.kt`

**Thêm — cổng phát hiện thay đổi:**

```rust
/// Win32 GetClipboardSequenceNumber: tăng mỗi khi clipboard đổi.
/// Rẻ, không cần OpenClipboard, không đụng nội dung.
fn clipboard_sequence() -> u32;
```

Poller mới: đọc sequence number (rẻ) → nếu không đổi thì bỏ qua hoàn toàn, không chạm nội dung. Chỉ khi đổi mới `read_clipboard()`.

**Thêm — chống echo bằng chính sequence number:**

Mọi đường ghi clipboard (`write_clipboard`, `app.clipboard().write_text`) phải ghi lại sequence number **ngay sau khi ghi** vào một `AtomicU32` dùng chung. Poller bỏ qua đúng giá trị đó. Đây là cách chặn echo loop sạch hơn so-sánh-fingerprint, vì nó đúng ở cả trường hợp nội dung trùng nhau hợp lệ.

**Migration:** mục lịch sử cũ có `kind == "files"` → giữ nguyên `text` làm nhãn, xoá `payload`. Chạy một lần khi load.

### 5.2. Poller — chuyển sang thread riêng

Poller rời `tauri::async_runtime::spawn` sang một `std::thread` chuyên dụng. Nó gửi kết quả qua channel về async runtime. Đảm bảo tuyệt đối không starve tokio worker.

### 5.3. `state.rs` — persistence không chặn

**Writer thread có debounce:**

- Mutation chỉ đánh dấu dirty + gửi tín hiệu qua channel, **không tự ghi đĩa**
- Một writer thread riêng gom các thay đổi, ghi tối đa mỗi 500ms, và ghi cưỡng bức khi thoát app
- `save_state()` hiện tại chuyển thành `mark_dirty()`; hàm ghi thật đổi tên thành `flush_state_now()` và chỉ writer thread gọi

**Bỏ `fs::copy` sang `.bak` mỗi lần ghi** (`state.rs:171`) — chỉ giữ cho lần migration đầu tiên.

**Khởi động không chặn:**

- `tauri::Builder` chạy trước với state rỗng, cửa sổ hiện ngay
- `load_state()` chạy trên thread nền, xong thì emit `update_state`
- `hydrate_running_app_icons()` rời khỏi đường khởi động, chạy lazy sau khi UI đã hiện
- Bỏ `save_state()` bên trong `load_state()` (`state.rs:228`, `:258`) khỏi đường khởi động

**Chặn phình state file:** giữ `data` base64 đầy đủ cho N mục ảnh mới nhất (đề xuất N = 50); mục cũ hơn chỉ giữ `text` + `thumbnail`, xoá `data`. Vẫn xem được preview, vẫn copy lại được nếu còn trong vòng N.

### 5.4. `broadcast_state` — chia nhỏ event

Thay một event `update_state` khổng lồ bằng các event riêng:

| Event | Nội dung | Tần suất |
|---|---|---|
| `update_settings` | settings, ips, clients, cloud | khi đổi |
| `update_history` | metadata lịch sử, **không có `data`, không có `thumbnail`** | coalesce ≤ 1 lần/100ms |
| `update_transfers` | chỉ tiến độ transfer | trong lúc truyền blob |

Thumbnail chuyển sang lazy: frontend gọi command mới `get_history_thumbnail(id)` cho từng mục đang hiển thị (đã có tiền lệ `get_history_image_preview`, `lib.rs:327`).

`network.rs:520` và `:524` — hai lần `broadcast_state` mỗi chunk gộp thành một lần `update_transfers`.

Frontend: `render()` diff theo `id` thay vì dựng lại toàn bộ; virtualize danh sách khi > 100 mục.

### 5.5. `network.rs` — thứ tự và keepalive

**Đảo thứ tự trong mọi đường xử lý clipboard:**

```
ws_tx.send()  →  mark_dirty()  →  broadcast (coalesced)  →  queue_cloud_sync()
```

**Đẩy blocking ra khỏi task WS:** `write_clipboard()` và ghi state đi qua channel tới thread chuyên dụng, không gọi trực tiếp trong task.

**Server-side keepalive:** sender task gửi WS Ping mỗi 15s. `CLIENT_IDLE_TIMEOUT` nâng lên 60s và tính theo pong nhận được, không theo message ứng dụng.

**`session_hello` không gọi `read_clipboard()`** (`network.rs:372`) — dùng payload clipboard mới nhất đã cache sẵn từ poller.

**Chunk 2 chiều:** payload lớn từ PC→Android dùng chung đường `clipboard_blob_offer` + chunk như chiều ngược lại, thay vì một frame khổng lồ. Ngưỡng đề xuất: > 256KB thì chunk.

**Giảm clone:** `ws_tx` đổi từ `broadcast::Sender<String>` sang `broadcast::Sender<Arc<OutgoingMessage>>` để frame lớn không bị clone theo số subscriber.

### 5.6. Pairing/E2EE — từ fail-closed-im-lặng sang fail-visible

**Desktop:**
- Khi peer chưa pair kết nối: gửi control message plaintext `{"app":"fastpaste","type":"pair_required"}` (không kèm dữ liệu nào) thay vì im lặng bỏ qua
- Hiện banner trong UI: "Thiết bị mới đang chờ ghép đôi" + nút mở QR
- Sửa comment `network.rs:226-228` cho khớp code

**Android:**
- `requiresPairing` (`WebSocketClient.kt:78`) nâng từ chuỗi log thành trạng thái UI thật: màn hình chính hiện rõ "Cần ghép đôi — quét QR trên PC", notification cũng phản ánh trạng thái này
- Trạng thái kết nối tách làm 3: `DISCONNECTED` / `CONNECTED_UNPAIRED` / `CONNECTED_SECURE`. Hiện tại `CONNECTED` bị dùng cho cả trường hợp không sync được gì

**Migration người dùng cũ:** lần chạy đầu sau cập nhật, cả 2 app hiện hướng dẫn ghép đôi lại một lần.

### 5.7. `cloud.rs` + orchestration — debounce đúng và delta

**Debounce theo deadline cố định** thay vì restart:

```
request đầu tiên → đặt deadline = now + 3s
request tiếp theo trong cửa sổ → bỏ qua, không gia hạn
tới deadline → sync
```

**Delta sync:** gửi các mục thay đổi kể từ revision cuối + cursor, thay vì `history_to_cloud_entries(&data.history)` toàn bộ (`history.rs:981`).

**Không giữ mutex lâu:** `lib.rs:875-915` clone toàn bộ history trong lúc giữ lock — đổi sang snapshot metadata trước, lấy payload sau.

### 5.8. Android — sửa lỗi và tách file

- `lastSyncedFingerprint` tách làm hai field `@Volatile` riêng biệt: `lastSentFingerprint` (chiều Android→PC, ghi tại `:91`) và `lastAppliedFingerprint` (chiều PC→Android, ghi tại `:335`, `:373`, `:545`, `:716`, `:789`). Android không có API tương đương `GetClipboardSequenceNumber`, nên giữ cách so-sánh fingerprint — chỉ tách đôi để hết lẫn hai chiều và hết race
- **Một chủ sở hữu duy nhất cho `ServiceDiscovery`**: Service giữ, ViewModel chỉ quan sát qua state của Service. Gỡ `ServiceDiscovery` khỏi `MainViewModel.kt:72`
- Tách `ClipboardService.kt` (982 dòng): `BlobTransferManager` (transfer), `HistorySyncMerger` (merge/dedup), phần còn lại là vòng đời service
- Tách `HomeScreen.kt` (69KB) theo section màn hình

---

## 6. Giai đoạn triển khai

Mỗi giai đoạn phải để lại app chạy được và có thể dừng lại ở đó.

| GĐ | Nội dung | Kết quả kiểm chứng được |
|---|---|---|
| **0** | Sửa 2 lỗi compile test trong `transfer.rs`; thêm CI gate `cargo check --tests` + `cargo test` | `cargo test` xanh |
| **1** | Gỡ copy file (2 app) + migration history + `GetClipboardSequenceNumber` + poller ra thread riêng + chống echo bằng sequence | CPU idle về ~0 khi có file trên clipboard; không còn vòng lặp echo |
| **2** | Persistence: writer thread có debounce, bỏ `fs::copy` mỗi lần ghi, khởi động không chặn, giới hạn payload inline | App mở < 1s bất kể lịch sử lớn cỡ nào |
| **3** | Chia `update_state` thành 3 event + coalesce + thumbnail lazy + frontend diff/virtualize | UI không đứng khi truyền blob |
| **4** | Network: đảo thứ tự gửi, server ping, bỏ `read_clipboard` trong `session_hello`, chunk 2 chiều, `Arc` cho ws_tx | Đo độ trễ LAN; kết nối sống qua doze |
| **5** | Pairing fail-visible: `pair_required`, banner desktop, 3 trạng thái Android, hướng dẫn migration | Máy chưa pair hiện lỗi rõ ràng thay vì im lặng |
| **6** | Cloud: debounce theo deadline + delta sync + không giữ mutex lâu | Drive sync chạy đúng 3s sau copy, kể cả khi copy liên tục |
| **7** | Android: tách fingerprint 2 chiều, một chủ ServiceDiscovery, tách `ClipboardService`/`HomeScreen` | Không còn race; file < 400 dòng |
| **8** | Cập nhật `CLAUDE.md` cho khớp kiến trúc thật (module mới, `MAX_HISTORY_ITEMS`, luồng pairing) | Tài liệu khớp code |

**Đường ngắn nhất để hết treo và sync nhanh: giai đoạn 0 → 1 → 2 → 4.** Giai đoạn 3, 5, 6, 7 xử lý phần còn lại.

---

## 7. Kiểm chứng

Mỗi sửa đổi phải có test, không dựa vào quan sát thủ công.

**Rust:**
- `clipboard_sequence` không đổi → poller không đọc nội dung (test bằng counter giả)
- Ghi clipboard từ WS → poller **không** phát ngược lại (test hồi quy echo loop)
- Debounce cloud: N request trong 3s → đúng 1 lần sync (test bằng thời gian ảo tokio)
- Peer chưa pair → nhận `pair_required`, không nhận dữ liệu nào
- History migration: mục `kind == "files"` → còn `text`, mất `payload`
- Sửa lại 2 test `transfer.rs` đang gãy

**Android:**
- `PairingProtocolTest`, `SqlCipherMigrationTest` hiện có phải chạy được trong CI
- Test merge history: nhận rồi copy lại cùng nội dung → không bị nuốt

**Đo đạc thủ công (ghi số trước/sau):**
- Thời gian từ lúc chạy `.exe` tới lúc cửa sổ hiện
- Độ trễ copy trên PC → clipboard Android đổi
- CPU khi để một file 50MB trên clipboard

---

## 8. Rủi ro

| Rủi ro | Giảm thiểu |
|---|---|
| Gỡ field `files` khỏi `ClipboardPayload` phá vault/settings cũ | `#[serde(default)]` + migration một chiều khi load; giữ `.bak` trước migration |
| Đổi giao thức WS làm lệch phiên bản Android cũ | `pair_required` và chunk 2 chiều đều là bổ sung; giữ khả năng đọc frame cũ ít nhất 1 phiên bản |
| Writer thread debounce làm mất dữ liệu khi crash | Flush cưỡng bức khi thoát; cửa sổ debounce ngắn (500ms) |
| Giới hạn payload inline làm mất ảnh cũ | Chỉ xoá `data`, giữ `thumbnail` + `text`; thông báo rõ trong UI khi mục quá cũ để copy lại |
| Chia `update_state` phá frontend | Giai đoạn 3 làm cả 2 phía cùng lúc, có thể giữ `update_state` cũ song song một phiên bản |

---

## 9. Ngoài phạm vi

- Đổi cơ chế lưu trữ ảnh sang file rời (đã cân nhắc, người dùng chọn giữ inline)
- Viết lại frontend bằng framework
- Hỗ trợ nền tảng ngoài Windows
- Refactor `cloud.rs` ngoài phần debounce + delta

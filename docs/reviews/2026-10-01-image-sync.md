# FastPaste 2.2.8 — sửa đồng bộ ảnh

Phạm vi: sửa ảnh hai chiều PC ↔ Android, giữ thao tác đơn giản cho một người dùng. Không thêm tuỳ chọn hoặc bước xác nhận.

## Cách dùng

- Cài cùng bản 2.2.8 trên PC và Android. Pairing hiện có được giữ nếu cập nhật tại chỗ với cùng chữ ký.
- PC: copy ảnh trong app hoặc Ctrl+C một file PNG/JPEG/WebP/GIF/BMP trong Explorer.
- Android: Chia sẻ → FastPaste từ Gallery; hoặc copy ảnh rồi mở FastPaste để app đọc clipboard khi có focus.
- Ảnh mới nhất từ thiết bị kia tự tải khi kết nối lại; chọn ảnh trong lịch sử để copy lại.
- Khi không kết nối được PC trong hai phút, Android dừng dịch vụ và bỏ notification như đã thống nhất.

## Đã sửa

1. Windows trước đây chỉ bật decoder PNG, nên JPEG/WebP từ Android không ghi được vào clipboard. Nay hỗ trợ PNG/JPEG/WebP/GIF/BMP; ảnh điện thoại có định dạng riêng được Android chuyển sang PNG nếu BitmapFactory đọc được.
2. Windows nhận Ctrl+C một file ảnh trong Explorer qua CF_HDROP, ngoài clipboard bitmap. Không mở rộng sang truyền file chung hoặc nhiều ảnh.
3. History sync chỉ chứa metadata của ảnh: hai phía nay yêu cầu tải body cho ảnh mới nhất từ phía kia, thay vì ghi ảnh rỗng hoặc chỉ hiện preview.
4. Share activity Android lưu nội dung trước khi finish, rồi gửi ID mục đã lưu cho dịch vụ; không phụ thuộc lần đọc clipboard nền sau đó.
5. FileProvider cache giữ metadata và kiểm tra fingerprint khi đọc lại ảnh do FastPaste vừa ghi. Caption/MIME không đổi và không gây gửi ngược.
6. WS Android dùng queue cho thông điệp và binary, tránh mất history đầu phiên khi consumer chưa đăng ký; collector được khởi tạo trước connect.
7. Copy ảnh lớn từ lịch sử và editor PC dùng blob offer/chunks. Android ACK theo kích thước cửa sổ thực tế của frame, kể cả sau resume.
8. Download hoàn tất bổ sung body vào mục có sẵn, giữ timestamp và cả blob ID Android cũ; cập nhật cache clipboard sau tự ghi.
9. Thumbnail Android giải mã có lấy mẫu; Windows giới hạn decode ảnh ở 32 megapixel và budget decoder 128 MB. Lỗi ghi ảnh nhận trên PC được báo trong app.

Các sửa fingerprint chuẩn chung, hydrate metadata cùng timestamp, dedup ảnh, caption, reconnect và notification từ lượt audit trước được đưa vào build này.

## Kiểm chứng

- Rust: 50 test qua; có test encode/decode ảnh thật ở năm định dạng và bổ sung body với ID cũ mà không đổi timestamp.
- Android: 12 test qua; có test giữ nguyên identity/caption khi đọc URI tự ghi và lấy mẫu thumbnail ảnh 6000×4000.
- Android lint: 0 lỗi, 44 cảnh báo có sẵn. APK debug tạo thành công.
- Rust Clippy qua, còn hai advisory có sẵn về chunks_exact. cargo fmt, cú pháp JavaScript và git diff --check qua.
- Windows Tauri release + NSIS installer 2.2.8 build thành công.

Chưa kiểm tra clipboard OS trên cặp điện thoại thật; adb không có thiết bị kết nối. Cần thử copy ảnh mới hai chiều, Share khi app đóng, chọn lại ảnh lớn trong lịch sử, rồi ngắt và nối lại Wi-Fi.

Build local PC không có desktop OAuth client được nhúng vì không có google_oauth.json hay biến môi trường build tương ứng. LAN hoạt động độc lập; cấu hình Google Drive cần được bổ sung khi build bản dùng Drive. APK dùng debug signing vì release signing secrets chưa sẵn sàng; file keystore hiện tại là placeholder. Không gỡ app nếu chữ ký cập nhật không khớp: cần build với khóa gốc để giữ dữ liệu.

Không tự cài đè app đang chạy, không publish release hoặc sửa update.json của bản phát hành từ xa.

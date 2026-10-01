use base64::{engine::general_purpose::STANDARD, Engine as _};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::sync::atomic::{AtomicU32, Ordering};

/// Sequence number tại thời điểm FastPaste tự ghi clipboard lần cuối.
/// Watcher bỏ qua đúng giá trị này để không phát ngược nội dung vừa nhận.
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

pub(crate) fn mark_self_write() {
    SELF_WRITE_SEQUENCE.store(clipboard_sequence(), Ordering::Release);
}

pub(crate) fn self_write_sequence() -> u32 {
    SELF_WRITE_SEQUENCE.load(Ordering::Acquire)
}

pub(crate) fn should_read(current: u32, last_seen: u32, self_write: u32) -> bool {
    current != last_seen && current != self_write
}

pub(crate) const MAX_PAYLOAD_BYTES: usize = 64 * 1024 * 1024;
const MAX_THUMBNAIL_CHARS: usize = 512 * 1024;

#[derive(Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub(crate) struct ClipboardPayload {
    #[serde(default)]
    pub(crate) kind: String,
    #[serde(default)]
    pub(crate) text: String,
    #[serde(default)]
    pub(crate) html: String,
    #[serde(default, rename = "mimeType", alias = "mime_type")]
    pub(crate) mime_type: String,
    #[serde(default)]
    pub(crate) data: String,
    #[serde(default)]
    pub(crate) thumbnail: String,
    #[serde(default, rename = "hasThumbnail", skip_deserializing)]
    pub(crate) has_thumbnail: bool,
}

impl ClipboardPayload {
    pub(crate) fn text(text: String) -> Self {
        Self {
            kind: "text".to_string(),
            text,
            mime_type: "text/plain".to_string(),
            ..Self::default()
        }
    }

    pub(crate) fn fingerprint(&self) -> String {
        // A preview can be regenerated with a different codec on Android and
        // Windows. It is metadata, not clipboard identity.
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

    pub(crate) fn protocol_json(&self) -> String {
        serde_json::json!({
            "app": "fastpaste",
            "type": "clipboard_payload",
            "payload": self,
        })
        .to_string()
    }

    pub(crate) fn matches_fingerprint(&self, expected: &str) -> bool {
        if self.fingerprint() == expected {
            return true;
        }
        self.legacy_fingerprint() == expected
    }

    pub(crate) fn legacy_fingerprint(&self) -> String {
        // Android's historical JSONStringer escaped every slash. Accept that
        // identity when reading existing blobs, while all new IDs are canonical.
        let legacy = format!(
            "{{\"kind\":{},\"text\":{},\"html\":{},\"mimeType\":{},\"data\":{}}}",
            serde_json::to_string(&self.kind).unwrap(),
            serde_json::to_string(&self.text).unwrap(),
            serde_json::to_string(&self.html).unwrap(),
            serde_json::to_string(&self.mime_type).unwrap(),
            serde_json::to_string(&self.data).unwrap()
        )
        .replace('/', "\\/");
        format!("{:x}", Sha256::digest(legacy.as_bytes()))
    }

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
        (self.data.len() / 4)
            .saturating_mul(3)
            .saturating_sub(padding)
    }

    pub(crate) fn sanitized_for_ui(&self) -> Self {
        let mut payload = self.sanitized_for_cloud();
        payload.has_thumbnail = !payload.thumbnail.is_empty();
        payload.thumbnail.clear();
        payload
    }

    pub(crate) fn sanitized_for_cloud(&self) -> Self {
        Self {
            kind: self.kind.clone(),
            text: self.text.clone(),
            html: self.html.clone(),
            mime_type: self.mime_type.clone(),
            data: String::new(),
            thumbnail: self.thumbnail.clone(),
            has_thumbnail: self.has_thumbnail,
        }
    }
}

#[cfg(windows)]
pub(crate) fn read_clipboard() -> Option<ClipboardPayload> {
    read_image()
        .or_else(read_image_file)
        .or_else(read_html)
        .or_else(read_text)
}

#[cfg(not(windows))]
pub(crate) fn read_clipboard() -> Option<ClipboardPayload> {
    None
}

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
        crate::watcher::store_latest_payload(payload.clone());
    }
    result
}

#[cfg(not(windows))]
pub(crate) fn write_clipboard(_payload: &ClipboardPayload) -> Result<(), String> {
    Err("Clipboard đa định dạng chỉ hỗ trợ desktop Windows.".to_string())
}

#[cfg(windows)]
fn read_text() -> Option<ClipboardPayload> {
    let text = arboard::Clipboard::new().ok()?.get_text().ok()?;
    (!text.is_empty()).then(|| ClipboardPayload::text(text))
}

#[cfg(windows)]
fn read_image() -> Option<ClipboardPayload> {
    use image::{DynamicImage, ImageFormat, RgbaImage};
    use std::io::Cursor;

    let image = arboard::Clipboard::new().ok()?.get_image().ok()?;
    let width = image.width as u32;
    let height = image.height as u32;
    let rgba = RgbaImage::from_raw(width, height, image.bytes.into_owned())?;
    let mut output = Cursor::new(Vec::new());
    let dynamic = DynamicImage::ImageRgba8(rgba);
    dynamic.write_to(&mut output, ImageFormat::Png).ok()?;
    let bytes = output.into_inner();
    if bytes.len() > MAX_PAYLOAD_BYTES {
        return None;
    }
    let hash = format!("{:x}", Sha256::digest(&bytes));
    Some(ClipboardPayload {
        kind: "image".to_string(),
        text: format!("[Hình ảnh {width}×{height} · {}]", &hash[..8]),
        mime_type: "image/png".to_string(),
        data: STANDARD.encode(bytes),
        thumbnail: image_thumbnail(&dynamic).unwrap_or_default(),
        ..ClipboardPayload::default()
    })
}

#[cfg(windows)]
fn image_thumbnail(image: &image::DynamicImage) -> Option<String> {
    use image::ImageFormat;
    use std::io::Cursor;

    let thumbnail = image.thumbnail(256, 256);
    let mut output = Cursor::new(Vec::new());
    thumbnail.write_to(&mut output, ImageFormat::Png).ok()?;
    let bytes = output.into_inner();
    (bytes.len() <= 384 * 1024).then(|| format!("data:image/png;base64,{}", STANDARD.encode(bytes)))
}

#[cfg(windows)]
fn read_image_file() -> Option<ClipboardPayload> {
    use windows_sys::Win32::System::DataExchange::{
        CloseClipboard, GetClipboardData, OpenClipboard,
    };
    use windows_sys::Win32::System::Ole::CF_HDROP;
    use windows_sys::Win32::UI::Shell::DragQueryFileW;
    // Explorer's Ctrl+C is a file list, not bitmap clipboard data.
    let path = unsafe {
        if OpenClipboard(std::ptr::null_mut()) == 0 {
            return None;
        }
        let handle = GetClipboardData(CF_HDROP as u32);
        let result = if !handle.is_null()
            && DragQueryFileW(handle, u32::MAX, std::ptr::null_mut(), 0) == 1
        {
            let length = DragQueryFileW(handle, 0, std::ptr::null_mut(), 0);
            let mut path = vec![0u16; length as usize + 1];
            let copied = DragQueryFileW(handle, 0, path.as_mut_ptr(), path.len() as u32);
            (copied > 0).then(|| {
                std::path::PathBuf::from(String::from_utf16_lossy(&path[..copied as usize]))
            })
        } else {
            None
        };
        CloseClipboard();
        result?
    };
    let extension = path.extension()?.to_str()?.to_ascii_lowercase();
    if !matches!(
        extension.as_str(),
        "png" | "jpg" | "jpeg" | "webp" | "gif" | "bmp"
    ) {
        return None;
    }
    let file = std::fs::File::open(path).ok()?;
    if file.metadata().ok()?.len() > MAX_PAYLOAD_BYTES as u64 {
        return None;
    }
    use std::io::Read;
    let mut bytes = Vec::new();
    file.take(MAX_PAYLOAD_BYTES as u64 + 1)
        .read_to_end(&mut bytes)
        .ok()?;
    image_file_payload(&bytes)
}

#[cfg(windows)]
fn image_file_payload(bytes: &[u8]) -> Option<ClipboardPayload> {
    if bytes.len() > MAX_PAYLOAD_BYTES {
        return None;
    }
    let format = image::guess_format(bytes).ok()?;
    let image = decode_image(bytes).ok()?;
    let hash = format!("{:x}", Sha256::digest(bytes));
    Some(ClipboardPayload {
        kind: "image".into(),
        text: format!(
            "[Hình ảnh {}×{} · {}]",
            image.width(),
            image.height(),
            &hash[..8]
        ),
        mime_type: format.to_mime_type().into(),
        data: STANDARD.encode(bytes),
        thumbnail: image_thumbnail(&image).unwrap_or_default(),
        ..Default::default()
    })
}

#[cfg(windows)]
fn write_image(payload: &ClipboardPayload) -> Result<(), String> {
    use arboard::ImageData;
    use std::borrow::Cow;

    let bytes = STANDARD
        .decode(&payload.data)
        .map_err(|error| format!("Dữ liệu ảnh lỗi: {error}"))?;
    let rgba = decode_image(&bytes)?.to_rgba8();
    let (width, height) = rgba.dimensions();
    arboard::Clipboard::new()
        .and_then(|mut clipboard| {
            clipboard.set_image(ImageData {
                width: width as usize,
                height: height as usize,
                bytes: Cow::Owned(rgba.into_raw()),
            })
        })
        .map_err(|error| error.to_string())
}

#[cfg(windows)]
fn decode_image(bytes: &[u8]) -> Result<image::DynamicImage, String> {
    use image::{ImageDecoder, ImageReader};
    let mut reader = ImageReader::new(std::io::Cursor::new(bytes))
        .with_guessed_format()
        .map_err(|error| format!("Không đọc được định dạng ảnh: {error}"))?;
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(32_768);
    limits.max_image_height = Some(32_768);
    limits.max_alloc = Some(128 * 1024 * 1024);
    reader.limits(limits);
    let decoder = reader
        .into_decoder()
        .map_err(|error| format!("Không đọc được ảnh: {error}"))?;
    let (width, height) = decoder.dimensions();
    if u64::from(width) * u64::from(height) > 32 * 1024 * 1024 {
        return Err("Ảnh vượt giới hạn 32 megapixel.".into());
    }
    image::DynamicImage::from_decoder(decoder)
        .map_err(|error| format!("Không giải mã được ảnh: {error}"))
}

#[cfg(windows)]
fn read_html() -> Option<ClipboardPayload> {
    let format = register_html_format();
    let bytes = read_native_format(format)?;
    let raw = String::from_utf8_lossy(&bytes)
        .trim_end_matches('\0')
        .to_string();
    let html = extract_html_fragment(&raw);
    if html.trim().is_empty() {
        return None;
    }
    let text = arboard::Clipboard::new()
        .ok()?
        .get_text()
        .unwrap_or_else(|_| "[Rich text]".to_string());
    Some(ClipboardPayload {
        kind: "html".to_string(),
        text,
        html,
        mime_type: "text/html".to_string(),
        ..ClipboardPayload::default()
    })
}

#[cfg(windows)]
fn write_html(payload: &ClipboardPayload) -> Result<(), String> {
    use windows_sys::Win32::Foundation::GlobalFree;
    use windows_sys::Win32::System::DataExchange::{
        CloseClipboard, EmptyClipboard, OpenClipboard, SetClipboardData,
    };
    use windows_sys::Win32::System::Ole::CF_UNICODETEXT;

    let html = build_cf_html(&payload.html);
    let mut text: Vec<u16> = payload
        .text
        .encode_utf16()
        .chain(std::iter::once(0))
        .collect();
    let text_memory = unsafe { alloc_global(text.as_mut_ptr() as *const u8, text.len() * 2)? };
    let html_memory = match unsafe { alloc_global(html.as_ptr(), html.len() + 1) } {
        Ok(memory) => memory,
        Err(error) => {
            unsafe { GlobalFree(text_memory) };
            return Err(error);
        }
    };
    unsafe {
        if OpenClipboard(std::ptr::null_mut()) == 0 {
            GlobalFree(text_memory);
            GlobalFree(html_memory);
            return Err("Clipboard đang bận.".to_string());
        }
        EmptyClipboard();
        if SetClipboardData(CF_UNICODETEXT as u32, text_memory).is_null() {
            GlobalFree(text_memory);
            GlobalFree(html_memory);
            CloseClipboard();
            return Err("Không ghi được plain text fallback.".to_string());
        }
        if SetClipboardData(register_html_format(), html_memory).is_null() {
            GlobalFree(html_memory);
            CloseClipboard();
            return Err("Không ghi được rich text.".to_string());
        }
        CloseClipboard();
    }
    Ok(())
}

#[cfg(windows)]
unsafe fn alloc_global(source: *const u8, size: usize) -> Result<*mut std::ffi::c_void, String> {
    use windows_sys::Win32::Foundation::GlobalFree;
    use windows_sys::Win32::System::Memory::{
        GlobalAlloc, GlobalLock, GlobalUnlock, GMEM_MOVEABLE,
    };
    let memory = GlobalAlloc(GMEM_MOVEABLE, size);
    if memory.is_null() {
        return Err("Không cấp phát được clipboard.".to_string());
    }
    let pointer = GlobalLock(memory) as *mut u8;
    if pointer.is_null() {
        GlobalFree(memory);
        return Err("Không khóa được clipboard.".to_string());
    }
    std::ptr::copy_nonoverlapping(source, pointer, size.saturating_sub(1));
    *pointer.add(size - 1) = 0;
    GlobalUnlock(memory);
    Ok(memory)
}

#[cfg(windows)]
fn read_native_format(format: u32) -> Option<Vec<u8>> {
    use windows_sys::Win32::System::DataExchange::{
        CloseClipboard, GetClipboardData, OpenClipboard,
    };
    use windows_sys::Win32::System::Memory::{GlobalLock, GlobalSize, GlobalUnlock};
    unsafe {
        if OpenClipboard(std::ptr::null_mut()) == 0 {
            return None;
        }
        let handle = GetClipboardData(format);
        if handle.is_null() {
            CloseClipboard();
            return None;
        }
        let size = GlobalSize(handle);
        let pointer = GlobalLock(handle) as *const u8;
        if pointer.is_null() || size == 0 {
            CloseClipboard();
            return None;
        }
        let bytes = std::slice::from_raw_parts(pointer, size).to_vec();
        GlobalUnlock(handle);
        CloseClipboard();
        Some(bytes)
    }
}

#[cfg(windows)]
fn register_html_format() -> u32 {
    use windows_sys::Win32::System::DataExchange::RegisterClipboardFormatW;
    let name: Vec<u16> = "HTML Format"
        .encode_utf16()
        .chain(std::iter::once(0))
        .collect();
    unsafe { RegisterClipboardFormatW(name.as_ptr()) }
}

fn extract_html_fragment(raw: &str) -> String {
    if let (Some(start), Some(end)) = (
        raw.find("<!--StartFragment-->"),
        raw.find("<!--EndFragment-->"),
    ) {
        return raw[start + 20..end].to_string();
    }
    raw.to_string()
}

fn build_cf_html(fragment: &str) -> Vec<u8> {
    let body =
        format!("<html><body><!--StartFragment-->{fragment}<!--EndFragment--></body></html>");
    let header_template = "Version:1.0\r\nStartHTML:0000000000\r\nEndHTML:0000000000\r\nStartFragment:0000000000\r\nEndFragment:0000000000\r\n";
    let start_html = header_template.len();
    let end_html = start_html + body.len();
    let start_fragment = start_html + body.find("<!--StartFragment-->").unwrap() + 20;
    let end_fragment = start_html + body.find("<!--EndFragment-->").unwrap();
    format!("Version:1.0\r\nStartHTML:{start_html:010}\r\nEndHTML:{end_html:010}\r\nStartFragment:{start_fragment:010}\r\nEndFragment:{end_fragment:010}\r\n{body}\0").into_bytes()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(windows)]
    #[test]
    fn phone_image_formats_decode_and_explorer_files_keep_binary_content() {
        let image = image::DynamicImage::ImageRgb8(image::RgbImage::from_pixel(
            3,
            2,
            image::Rgb([20, 90, 180]),
        ));
        for format in [
            image::ImageFormat::Png,
            image::ImageFormat::Jpeg,
            image::ImageFormat::WebP,
            image::ImageFormat::Gif,
            image::ImageFormat::Bmp,
        ] {
            let mut bytes = std::io::Cursor::new(Vec::new());
            image.write_to(&mut bytes, format).unwrap();
            let decoded = decode_image(bytes.get_ref()).unwrap();
            assert_eq!((decoded.width(), decoded.height()), (3, 2));
            let payload = image_file_payload(bytes.get_ref()).unwrap();
            assert_eq!(payload.mime_type, format.to_mime_type());
            assert_eq!(STANDARD.decode(&payload.data).unwrap(), *bytes.get_ref());
            assert!(!payload.thumbnail.is_empty());
        }
        assert!(decode_image(b"invalid-image").is_err());
    }

    #[test]
    fn fingerprint_matches_android_vector_with_slashes_unicode_and_controls() {
        let payload = ClipboardPayload {
            kind: "image".into(),
            text: "ảnh / 🙂\n\u{1}".into(),
            mime_type: "image/png".into(),
            data: "AA/A".into(),
            ..Default::default()
        };
        assert_eq!(
            payload.fingerprint(),
            "3787d0ba5fa60929472e14a743397613c4ebc60d2c359859270a2858b463f961"
        );
        assert!(payload.matches_fingerprint(
            "91cf4f3c682861451c4924f3a28415af1a41ccfadbfd2b2081d25a8530960a97"
        ));
        assert!(!payload.matches_fingerprint("invalid"));
    }

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
        assert!(!should_read(8, 7, 8));
    }

    #[test]
    fn poller_resumes_after_a_real_copy_follows_its_own_write() {
        assert!(should_read(9, 8, 8));
    }

    #[test]
    fn cf_html_round_trip_preserves_fragment() {
        let fragment = "<p><strong>FastPaste</strong> · tiếng Việt</p>";
        let encoded = build_cf_html(fragment);
        let decoded = String::from_utf8(encoded).expect("CF_HTML should be UTF-8");
        assert_eq!(extract_html_fragment(&decoded), fragment);
    }

    #[test]
    fn payload_limit_counts_base64_data() {
        let allowed = ClipboardPayload {
            kind: "image".to_string(),
            text: "image".to_string(),
            data: STANDARD.encode(vec![0u8; MAX_PAYLOAD_BYTES]),
            ..ClipboardPayload::default()
        };
        assert!(allowed.is_within_limit());

        let oversized = ClipboardPayload {
            data: STANDARD.encode(vec![0u8; MAX_PAYLOAD_BYTES + 4]),
            ..allowed
        };
        assert!(!oversized.is_within_limit());
    }

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

    #[test]
    fn ui_payload_carries_no_binary_data() {
        let payload = ClipboardPayload {
            kind: "image".into(),
            text: "[Hình ảnh]".into(),
            mime_type: "image/png".into(),
            data: STANDARD.encode(vec![9u8; 4096]),
            thumbnail: "data:image/png;base64,AAAA".into(),
            ..ClipboardPayload::default()
        };

        let ui = payload.sanitized_for_ui();

        assert!(ui.data.is_empty());
        assert!(ui.thumbnail.is_empty());
        assert!(ui.has_thumbnail);
        assert_eq!(ui.text, "[Hình ảnh]");
    }

    #[test]
    fn ui_payload_without_thumbnail_says_so() {
        let payload = ClipboardPayload::text("xin chào".into());
        assert!(!payload.sanitized_for_ui().has_thumbnail);
    }
}

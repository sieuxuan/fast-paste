use base64::{engine::general_purpose::STANDARD, Engine as _};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

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
        let mut payload = self.clone();
        payload.data.clear();
        payload
    }
}

#[cfg(windows)]
pub(crate) fn read_clipboard() -> Option<ClipboardPayload> {
    read_image().or_else(read_html).or_else(read_text)
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
    match payload.kind.as_str() {
        "image" => write_image(payload),
        "html" => write_html(payload),
        _ => arboard::Clipboard::new()
            .and_then(|mut clipboard| clipboard.set_text(payload.text.clone()))
            .map_err(|error| error.to_string()),
    }
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
fn write_image(payload: &ClipboardPayload) -> Result<(), String> {
    use arboard::ImageData;
    use std::borrow::Cow;

    let bytes = STANDARD
        .decode(&payload.data)
        .map_err(|error| format!("Dữ liệu ảnh lỗi: {error}"))?;
    let rgba = image::load_from_memory(&bytes)
        .map_err(|error| format!("Không đọc được ảnh: {error}"))?
        .to_rgba8();
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
}

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::clipboard::ClipboardPayload;
use crate::cloud;
use crate::state::{save_state, AppStateData};

pub(crate) const MAX_HISTORY_ITEMS: usize = 1_000;
const MAX_DELETED_MARKERS: usize = 1_000;

#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct HistoryItem {
    pub(crate) id: String,
    pub(crate) text: String,
    pub(crate) timestamp: String,
    pub(crate) source: String,
    #[serde(default, rename = "sourceApp", alias = "source_app")]
    pub(crate) source_app: String,
    #[serde(default, rename = "sourceTitle", alias = "source_title")]
    pub(crate) source_title: String,
    #[serde(default)]
    pub(crate) pinned: bool,
    #[serde(default)]
    pub(crate) quick_slot: Option<u8>,
    #[serde(default)]
    pub(crate) folder: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub(crate) payload: Option<ClipboardPayload>,
}

#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct SyncEntry {
    pub(crate) text: String,
    pub(crate) timestamp: i64,
    pub(crate) source: String,
    #[serde(default, rename = "sourceApp", alias = "source_app")]
    pub(crate) source_app: String,
    #[serde(default, rename = "sourceTitle", alias = "source_title")]
    pub(crate) source_title: String,
    #[serde(default)]
    pub(crate) pinned: bool,
    #[serde(default)]
    pub(crate) folder: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub(crate) payload: Option<ClipboardPayload>,
}

#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct DeletedMarker {
    #[serde(default)]
    pub(crate) text_hash: String,
    #[serde(default, skip_serializing)]
    pub(crate) text: Option<String>,
    pub(crate) deleted_at: i64,
    #[serde(default)]
    pub(crate) include_pinned: bool,
}

#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct HistoryBackup {
    pub(crate) items: Vec<HistoryItem>,
    pub(crate) deleted_at: i64,
    pub(crate) label: String,
}

#[derive(Default)]
pub(crate) struct SourceMetadata {
    pub(crate) app: String,
    pub(crate) title: String,
    pub(crate) icon: String,
}

#[cfg(windows)]
pub(crate) fn capture_source_metadata() -> SourceMetadata {
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::System::Threading::{
        OpenProcess, QueryFullProcessImageNameW, PROCESS_QUERY_LIMITED_INFORMATION,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        GetForegroundWindow, GetWindowTextLengthW, GetWindowTextW, GetWindowThreadProcessId,
    };

    unsafe {
        let hwnd = GetForegroundWindow();
        if hwnd.is_null() {
            return SourceMetadata::default();
        }

        let title_len = GetWindowTextLengthW(hwnd);
        let title = if title_len > 0 {
            let mut buffer = vec![0u16; title_len as usize + 1];
            let copied = GetWindowTextW(hwnd, buffer.as_mut_ptr(), buffer.len() as i32);
            String::from_utf16_lossy(&buffer[..copied as usize])
                .trim()
                .chars()
                .take(160)
                .collect()
        } else {
            String::new()
        };

        let mut process_id = 0u32;
        GetWindowThreadProcessId(hwnd, &mut process_id);
        let (app, icon) = if process_id == 0 {
            (String::new(), String::new())
        } else {
            let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, process_id);
            if handle.is_null() {
                (String::new(), String::new())
            } else {
                let mut buffer = vec![0u16; 32768];
                let mut size = buffer.len() as u32;
                let full_path =
                    if QueryFullProcessImageNameW(handle, 0, buffer.as_mut_ptr(), &mut size) != 0 {
                        String::from_utf16_lossy(&buffer[..size as usize])
                    } else {
                        String::new()
                    };
                let app = std::path::Path::new(&full_path)
                    .file_name()
                    .map(|name| name.to_string_lossy().into_owned())
                    .unwrap_or_default()
                    .chars()
                    .take(96)
                    .collect();
                let icon = extract_executable_icon(&full_path).unwrap_or_default();
                CloseHandle(handle);
                (app, icon)
            }
        };

        SourceMetadata { app, title, icon }
    }
}

#[cfg(windows)]
fn extract_executable_icon(path: &str) -> Option<String> {
    use base64::{engine::general_purpose::STANDARD, Engine as _};
    use image::{DynamicImage, ImageFormat, RgbaImage};
    use std::io::Cursor;
    use std::ptr::{null_mut, slice_from_raw_parts};
    use windows_sys::Win32::Graphics::Gdi::{
        CreateCompatibleDC, CreateDIBSection, DeleteDC, DeleteObject, SelectObject, BITMAPINFO,
        BI_RGB, DIB_RGB_COLORS,
    };
    use windows_sys::Win32::UI::Shell::ExtractIconExW;
    use windows_sys::Win32::UI::WindowsAndMessaging::{DestroyIcon, DrawIconEx, DI_NORMAL};

    if path.is_empty() {
        return None;
    }

    unsafe {
        let wide: Vec<u16> = path.encode_utf16().chain(std::iter::once(0)).collect();
        let mut large = null_mut();
        let mut small = null_mut();
        if ExtractIconExW(wide.as_ptr(), 0, &mut large, &mut small, 1) == 0 {
            return None;
        }
        let icon = if !small.is_null() { small } else { large };
        if icon.is_null() {
            if !large.is_null() {
                DestroyIcon(large);
            }
            return None;
        }

        let dc = CreateCompatibleDC(null_mut());
        if dc.is_null() {
            DestroyIcon(icon);
            if !large.is_null() && large != icon {
                DestroyIcon(large);
            }
            return None;
        }

        let mut bitmap_info: BITMAPINFO = std::mem::zeroed();
        bitmap_info.bmiHeader.biSize = std::mem::size_of_val(&bitmap_info.bmiHeader) as u32;
        bitmap_info.bmiHeader.biWidth = 32;
        bitmap_info.bmiHeader.biHeight = -32;
        bitmap_info.bmiHeader.biPlanes = 1;
        bitmap_info.bmiHeader.biBitCount = 32;
        bitmap_info.bmiHeader.biCompression = BI_RGB;

        let mut bits = null_mut();
        let bitmap = CreateDIBSection(dc, &bitmap_info, DIB_RGB_COLORS, &mut bits, null_mut(), 0);
        if bitmap.is_null() || bits.is_null() {
            DeleteDC(dc);
            DestroyIcon(icon);
            if !large.is_null() && large != icon {
                DestroyIcon(large);
            }
            return None;
        }

        let previous = SelectObject(dc, bitmap);
        let drawn = DrawIconEx(dc, 0, 0, icon, 32, 32, 0, null_mut(), DI_NORMAL);
        let raw = &*slice_from_raw_parts(bits as *const u8, 32 * 32 * 4);
        let has_alpha = raw.chunks_exact(4).any(|pixel| pixel[3] != 0);
        let mut rgba = Vec::with_capacity(raw.len());
        for pixel in raw.chunks_exact(4) {
            rgba.extend_from_slice(&[
                pixel[2],
                pixel[1],
                pixel[0],
                if has_alpha { pixel[3] } else { 255 },
            ]);
        }

        SelectObject(dc, previous);
        DeleteObject(bitmap);
        DeleteDC(dc);
        DestroyIcon(icon);
        if !large.is_null() && large != icon {
            DestroyIcon(large);
        }

        if drawn == 0 {
            return None;
        }
        let image = RgbaImage::from_raw(32, 32, rgba)?;
        let mut output = Cursor::new(Vec::new());
        DynamicImage::ImageRgba8(image)
            .write_to(&mut output, ImageFormat::Png)
            .ok()?;
        Some(format!(
            "data:image/png;base64,{}",
            STANDARD.encode(output.into_inner())
        ))
    }
}

#[cfg(windows)]
pub(crate) fn hydrate_running_app_icons(data: &mut AppStateData) -> bool {
    use windows_sys::Win32::Foundation::{CloseHandle, INVALID_HANDLE_VALUE};
    use windows_sys::Win32::System::Diagnostics::ToolHelp::{
        CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W,
        TH32CS_SNAPPROCESS,
    };
    use windows_sys::Win32::System::Threading::{
        OpenProcess, QueryFullProcessImageNameW, PROCESS_QUERY_LIMITED_INFORMATION,
    };

    let mut missing: std::collections::HashSet<String> = data
        .history
        .iter()
        .map(|item| item.source_app.trim().to_ascii_lowercase())
        .filter(|app| !app.is_empty() && !data.app_icons.contains_key(app))
        .collect();
    if missing.is_empty() {
        return false;
    }

    let mut changed = false;
    unsafe {
        let snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if snapshot == INVALID_HANDLE_VALUE {
            return false;
        }
        let mut process: PROCESSENTRY32W = std::mem::zeroed();
        process.dwSize = std::mem::size_of::<PROCESSENTRY32W>() as u32;
        let mut has_process = Process32FirstW(snapshot, &mut process) != 0;
        while has_process && !missing.is_empty() {
            let name_len = process
                .szExeFile
                .iter()
                .position(|value| *value == 0)
                .unwrap_or(process.szExeFile.len());
            let app_name = String::from_utf16_lossy(&process.szExeFile[..name_len]);
            let app_key = app_name.to_ascii_lowercase();
            if missing.contains(&app_key) {
                let handle =
                    OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, process.th32ProcessID);
                if !handle.is_null() {
                    let mut buffer = vec![0u16; 32768];
                    let mut size = buffer.len() as u32;
                    if QueryFullProcessImageNameW(handle, 0, buffer.as_mut_ptr(), &mut size) != 0 {
                        let path = String::from_utf16_lossy(&buffer[..size as usize]);
                        if let Some(icon) = extract_executable_icon(&path) {
                            data.app_icons.insert(app_key.clone(), icon);
                            missing.remove(&app_key);
                            changed = true;
                        }
                    }
                    CloseHandle(handle);
                }
            }
            has_process = Process32NextW(snapshot, &mut process) != 0;
        }
        CloseHandle(snapshot);
    }
    changed
}

#[cfg(not(windows))]
pub(crate) fn hydrate_running_app_icons(_data: &mut AppStateData) -> bool {
    false
}

#[cfg(not(windows))]
pub(crate) fn capture_source_metadata() -> SourceMetadata {
    SourceMetadata::default()
}

pub(crate) fn make_history_item(text: &str, source: &str) -> HistoryItem {
    let metadata = if source == "PC" {
        capture_source_metadata()
    } else {
        SourceMetadata::default()
    };
    make_history_item_at(
        text,
        source,
        chrono::Utc::now().timestamp_millis(),
        metadata,
    )
}

pub(crate) fn make_history_item_at(
    text: &str,
    source: &str,
    timestamp_millis: i64,
    metadata: SourceMetadata,
) -> HistoryItem {
    let timestamp = chrono::DateTime::<chrono::Utc>::from_timestamp_millis(timestamp_millis)
        .unwrap_or_else(chrono::Utc::now)
        .to_rfc3339();
    HistoryItem {
        id: format!("{}", timestamp_millis),
        text: text.to_string(),
        timestamp,
        source: source.to_string(),
        source_app: metadata.app,
        source_title: metadata.title,
        pinned: false,
        quick_slot: None,
        folder: String::new(),
        payload: None,
    }
}

pub(crate) fn make_history_item_from_sync(entry: &SyncEntry) -> HistoryItem {
    let mut item = make_history_item_at(
        &entry.text,
        &entry.source,
        entry.timestamp,
        SourceMetadata {
            app: entry.source_app.clone(),
            title: entry.source_title.clone(),
            icon: String::new(),
        },
    );
    item.pinned = entry.pinned;
    item.folder = clean_folder_name(&entry.folder);
    item.payload = entry.payload.clone();
    item
}

pub(crate) fn make_history_item_from_cloud(entry: &cloud::CloudEntry) -> HistoryItem {
    let mut item = make_history_item_at(
        &entry.text,
        &entry.source,
        entry.timestamp,
        SourceMetadata {
            app: entry.source_app.clone(),
            title: entry.source_title.clone(),
            icon: String::new(),
        },
    );
    item.pinned = entry.pinned;
    item.folder = clean_folder_name(&entry.folder);
    item.payload = entry.payload.clone();
    item
}

pub(crate) fn promote_or_insert_history(data: &mut AppStateData, text: &str, source: &str) -> bool {
    let now = chrono::Utc::now();
    let metadata = if source == "PC" {
        capture_source_metadata()
    } else {
        SourceMetadata::default()
    };
    let mut icon_changed = false;
    if !metadata.app.is_empty() && !metadata.icon.is_empty() {
        let app_key = metadata.app.to_ascii_lowercase();
        icon_changed = data.app_icons.get(&app_key) != Some(&metadata.icon);
        data.app_icons.insert(app_key, metadata.icon.clone());
        if data.app_icons.len() > 96 {
            let active_apps: std::collections::HashSet<String> = data
                .history
                .iter()
                .map(|item| item.source_app.to_ascii_lowercase())
                .collect();
            data.app_icons.retain(|app, _| active_apps.contains(app));
        }
    }
    if source == "PC"
        && data
            .settings
            .excluded_apps
            .iter()
            .any(|app| app.eq_ignore_ascii_case(&metadata.app))
    {
        return icon_changed;
    }
    if let Some(index) = data.history.iter().position(|item| item.text == text) {
        if index == 0
            && data.history[index].source == source
            && data.history[index].source_app == metadata.app
            && data.history[index].source_title == metadata.title
        {
            return icon_changed;
        }
        let mut item = data.history.remove(index);
        item.timestamp = now.to_rfc3339();
        item.source = source.to_string();
        if source == "PC" {
            item.source_app = metadata.app;
            item.source_title = metadata.title;
        }
        data.history.insert(0, item);
    } else {
        data.history.insert(
            0,
            make_history_item_at(text, source, now.timestamp_millis(), metadata),
        );
    }
    trim_history(&mut data.history);
    true
}

pub(crate) fn promote_or_insert_payload(
    data: &mut AppStateData,
    payload: &ClipboardPayload,
    source: &str,
) -> bool {
    if payload.kind == "text" {
        return promote_or_insert_history(data, &payload.text, source);
    }
    let now = chrono::Utc::now();
    let metadata = if source == "PC" {
        capture_source_metadata()
    } else {
        SourceMetadata::default()
    };
    let mut icon_changed = false;
    if !metadata.app.is_empty() && !metadata.icon.is_empty() {
        let app_key = metadata.app.to_ascii_lowercase();
        icon_changed = data.app_icons.get(&app_key) != Some(&metadata.icon);
        data.app_icons.insert(app_key, metadata.icon.clone());
    }
    if source == "PC"
        && data
            .settings
            .excluded_apps
            .iter()
            .any(|app| app.eq_ignore_ascii_case(&metadata.app))
    {
        return icon_changed;
    }
    let key = payload.fingerprint();
    if let Some(index) = data
        .history
        .iter()
        .position(|item| history_item_key(item) == key)
    {
        let mut item = data.history.remove(index);
        item.timestamp = now.to_rfc3339();
        item.source = source.to_string();
        item.text = payload.text.clone();
        item.payload = Some(payload.clone());
        if source == "PC" {
            item.source_app = metadata.app;
            item.source_title = metadata.title;
        }
        data.history.insert(0, item);
    } else {
        let mut item =
            make_history_item_at(&payload.text, source, now.timestamp_millis(), metadata);
        item.payload = Some(payload.clone());
        data.history.insert(0, item);
    }
    trim_history(&mut data.history);
    true
}

pub(crate) fn history_item_key(item: &HistoryItem) -> String {
    item.payload
        .as_ref()
        .map(ClipboardPayload::fingerprint)
        .unwrap_or_else(|| item.text.clone())
}

fn sync_entry_key(entry: &SyncEntry) -> String {
    entry
        .payload
        .as_ref()
        .map(ClipboardPayload::fingerprint)
        .unwrap_or_else(|| entry.text.clone())
}

pub(crate) fn apply_sync_metadata(
    item: &mut HistoryItem,
    pinned: bool,
    folder: &str,
    source_app: &str,
    source_title: &str,
) -> bool {
    let before_pinned = item.pinned;
    let before_folder = item.folder.clone();
    let before_source_app = item.source_app.clone();
    let before_source_title = item.source_title.clone();

    item.pinned = item.pinned || pinned;
    let folder = clean_folder_name(folder);
    if !folder.is_empty() {
        item.folder = folder;
    }
    if !source_app.trim().is_empty() {
        item.source_app = source_app.trim().chars().take(96).collect();
    }
    if !source_title.trim().is_empty() {
        item.source_title = source_title.trim().chars().take(160).collect();
    }

    item.pinned != before_pinned
        || item.folder != before_folder
        || item.source_app != before_source_app
        || item.source_title != before_source_title
}

pub(crate) fn clean_folder_name(folder: &str) -> String {
    folder
        .split_whitespace()
        .collect::<Vec<&str>>()
        .join(" ")
        .chars()
        .take(48)
        .collect()
}

pub(crate) fn trim_history(history: &mut Vec<HistoryItem>) {
    history.sort_by_key(|item| std::cmp::Reverse(timestamp_to_millis(&item.timestamp)));
    if history.len() > MAX_HISTORY_ITEMS {
        let pinned_count = history.iter().filter(|item| item.pinned).count();
        let non_pinned_limit = MAX_HISTORY_ITEMS.saturating_sub(pinned_count);
        let mut kept_non_pinned = 0usize;
        history.retain(|item| {
            if item.pinned {
                true
            } else if kept_non_pinned < non_pinned_limit {
                kept_non_pinned += 1;
                true
            } else {
                false
            }
        });
    }
}

pub(crate) fn unmark_deleted_text(data: &mut AppStateData, text: &str) {
    let text_hash = hash_text(text);
    data.deleted_markers
        .retain(|marker| marker.text_hash != text_hash);
}

pub(crate) fn mark_deleted_item(data: &mut AppStateData, item: &HistoryItem) {
    mark_deleted_text_with_policy(data, history_item_key(item), true);
}

pub(crate) fn mark_deleted_item_preserving_pinned(data: &mut AppStateData, item: &HistoryItem) {
    mark_deleted_text_with_policy(data, history_item_key(item), false);
}

fn mark_deleted_text_with_policy(data: &mut AppStateData, text: String, include_pinned: bool) {
    let deleted_at = chrono::Utc::now().timestamp_millis();
    let text_hash = hash_text(&text);
    if let Some(existing) = data
        .deleted_markers
        .iter_mut()
        .find(|marker| marker.text_hash == text_hash)
    {
        existing.deleted_at = deleted_at;
        existing.include_pinned = existing.include_pinned || include_pinned;
    } else {
        data.deleted_markers.push(DeletedMarker {
            text_hash,
            text: None,
            deleted_at,
            include_pinned,
        });
    }

    data.deleted_markers
        .sort_by_key(|marker| std::cmp::Reverse(marker.deleted_at));
    if data.deleted_markers.len() > MAX_DELETED_MARKERS {
        data.deleted_markers.truncate(MAX_DELETED_MARKERS);
    }
}

pub(crate) fn normalize_deleted_markers(data: &mut AppStateData) {
    for marker in &mut data.deleted_markers {
        if marker.text_hash.is_empty() {
            if let Some(text) = marker.text.take() {
                marker.text_hash = hash_text(&text);
            }
        }
    }
    data.deleted_markers
        .retain(|marker| !marker.text_hash.is_empty());
}

pub(crate) fn is_deleted_by_local_marker(
    data: &AppStateData,
    text: &str,
    timestamp: i64,
    pinned: bool,
) -> bool {
    if !pinned
        && data
            .clear_history_at
            .map(|clear_at| timestamp <= clear_at)
            .unwrap_or(false)
    {
        return true;
    }

    let text_hash = hash_text(text);
    data.deleted_markers.iter().any(|marker| {
        marker.text_hash == text_hash
            && timestamp <= marker.deleted_at
            && (!pinned || marker.include_pinned)
    })
}

pub(crate) fn has_deleted_text_marker(data: &AppStateData, text: &str) -> bool {
    let text_hash = hash_text(text);
    data.deleted_markers
        .iter()
        .any(|marker| marker.text_hash == text_hash)
}

fn hash_text(text: &str) -> String {
    format!("{:x}", Sha256::digest(text.as_bytes()))
}

pub(crate) fn timestamp_to_millis(timestamp: &str) -> i64 {
    chrono::DateTime::parse_from_rfc3339(timestamp)
        .map(|value| value.timestamp_millis())
        .unwrap_or(0)
}

pub(crate) fn make_history_sync_payload(
    data: &mut AppStateData,
    current_clipboard: Option<ClipboardPayload>,
) -> Option<String> {
    if let Some(payload) = current_clipboard {
        let key = if payload.kind == "text" {
            payload.text.clone()
        } else {
            payload.fingerprint()
        };
        let already_known = data
            .history
            .iter()
            .any(|item| history_item_key(item) == key);
        if !payload.text.is_empty()
            && !has_deleted_text_marker(data, &key)
            && !already_known
            && promote_or_insert_payload(data, &payload, "PC")
        {
            save_state(data);
        }
    }

    let entries: Vec<SyncEntry> = data
        .history
        .iter()
        .map(|item| SyncEntry {
            text: item.text.clone(),
            timestamp: timestamp_to_millis(&item.timestamp),
            source: item.source.clone(),
            source_app: item.source_app.clone(),
            source_title: item.source_title.clone(),
            pinned: item.pinned,
            folder: item.folder.clone(),
            payload: item.payload.clone(),
        })
        .collect();

    Some(
        serde_json::json!({
            "app": "fastpaste",
            "type": "history_sync",
            "entries": entries,
        })
        .to_string(),
    )
}

/// Merge a history_sync payload from a device into local history.
/// Returns (newest surviving incoming entry, whether history changed,
/// latest local timestamp BEFORE the merge).
pub(crate) fn merge_sync_entries(
    data: &mut AppStateData,
    entries: Vec<SyncEntry>,
) -> (Option<SyncEntry>, bool, i64) {
    let latest_local_timestamp = data
        .history
        .first()
        .map(|item| timestamp_to_millis(&item.timestamp))
        .unwrap_or(0);
    let mut newest_incoming: Option<SyncEntry> = None;
    let mut history_changed = false;

    for entry in entries {
        if entry.text.is_empty() {
            continue;
        }
        if entry
            .payload
            .as_ref()
            .map(|payload| !payload.is_within_limit())
            .unwrap_or(false)
        {
            continue;
        }
        let entry_key = sync_entry_key(&entry);
        if is_deleted_by_local_marker(data, &entry_key, entry.timestamp, entry.pinned) {
            continue;
        }
        if newest_incoming
            .as_ref()
            .map(|current| entry.timestamp > current.timestamp)
            .unwrap_or(true)
        {
            newest_incoming = Some(entry.clone());
        }
        if let Some(existing) = data
            .history
            .iter_mut()
            .find(|item| history_item_key(item) == entry_key)
        {
            history_changed |= apply_sync_metadata(
                existing,
                entry.pinned,
                &entry.folder,
                &entry.source_app,
                &entry.source_title,
            );
            if entry.timestamp > timestamp_to_millis(&existing.timestamp) {
                existing.timestamp =
                    chrono::DateTime::<chrono::Utc>::from_timestamp_millis(entry.timestamp)
                        .unwrap_or_else(chrono::Utc::now)
                        .to_rfc3339();
                existing.source = entry.source.clone();
                existing.payload = entry.payload.clone();
                history_changed = true;
            }
        } else {
            data.history.push(make_history_item_from_sync(&entry));
            history_changed = true;
        }
    }

    if history_changed {
        data.history.sort_by(|a, b| {
            timestamp_to_millis(&b.timestamp).cmp(&timestamp_to_millis(&a.timestamp))
        });
        trim_history(&mut data.history);
    }
    (newest_incoming, history_changed, latest_local_timestamp)
}

pub(crate) fn history_to_cloud_entries(history: &[HistoryItem]) -> Vec<cloud::CloudEntry> {
    history
        .iter()
        .map(|item| cloud::CloudEntry {
            text: item.text.clone(),
            timestamp: timestamp_to_millis(&item.timestamp),
            source: item.source.clone(),
            source_app: item.source_app.clone(),
            source_title: item.source_title.clone(),
            pinned: item.pinned,
            folder: item.folder.clone(),
            payload: item.payload.clone(),
        })
        .collect()
}

pub(crate) fn merge_cloud_entries_into_history(
    data: &mut AppStateData,
    entries: Vec<cloud::CloudEntry>,
) -> (usize, bool) {
    let mut inserted = 0;
    let mut changed = false;

    for entry in entries {
        if entry.text.trim().is_empty() {
            continue;
        }
        if entry
            .payload
            .as_ref()
            .map(|payload| !payload.is_within_limit())
            .unwrap_or(false)
        {
            continue;
        }
        let entry_key = cloud::cloud_entry_key(&entry);
        if is_deleted_by_local_marker(data, &entry_key, entry.timestamp, entry.pinned) {
            continue;
        }

        if let Some(existing) = data
            .history
            .iter_mut()
            .find(|item| history_item_key(item) == entry_key)
        {
            changed |= apply_sync_metadata(
                existing,
                entry.pinned,
                &entry.folder,
                &entry.source_app,
                &entry.source_title,
            );
            if entry.timestamp > timestamp_to_millis(&existing.timestamp) {
                existing.timestamp =
                    chrono::DateTime::<chrono::Utc>::from_timestamp_millis(entry.timestamp)
                        .unwrap_or_else(chrono::Utc::now)
                        .to_rfc3339();
                existing.source = entry.source;
                existing.payload = entry.payload.clone();
                changed = true;
            }
        } else {
            data.history.push(make_history_item_from_cloud(&entry));
            inserted += 1;
            changed = true;
        }
    }

    if changed {
        data.history.sort_by(|a, b| {
            timestamp_to_millis(&b.timestamp).cmp(&timestamp_to_millis(&a.timestamp))
        });
        trim_history(&mut data.history);
    }
    (inserted, changed)
}

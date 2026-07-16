use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::cloud;
use crate::state::{save_state, AppStateData};

pub(crate) const MAX_HISTORY_ITEMS: usize = 500;
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
}

#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct DeletedMarker {
    #[serde(default)]
    pub(crate) text_hash: String,
    #[serde(default, skip_serializing)]
    pub(crate) text: Option<String>,
    pub(crate) deleted_at: i64,
}

#[derive(Default)]
pub(crate) struct SourceMetadata {
    pub(crate) app: String,
    pub(crate) title: String,
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
        let app = if process_id == 0 {
            String::new()
        } else {
            let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, process_id);
            if handle.is_null() {
                String::new()
            } else {
                let mut buffer = vec![0u16; 32768];
                let mut size = buffer.len() as u32;
                let app =
                    if QueryFullProcessImageNameW(handle, 0, buffer.as_mut_ptr(), &mut size) != 0 {
                        std::path::Path::new(&String::from_utf16_lossy(&buffer[..size as usize]))
                            .file_name()
                            .map(|name| name.to_string_lossy().into_owned())
                            .unwrap_or_default()
                            .chars()
                            .take(96)
                            .collect()
                    } else {
                        String::new()
                    };
                CloseHandle(handle);
                app
            }
        };

        SourceMetadata { app, title }
    }
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
        },
    );
    item.pinned = entry.pinned;
    item.folder = clean_folder_name(&entry.folder);
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
        },
    );
    item.pinned = entry.pinned;
    item.folder = clean_folder_name(&entry.folder);
    item
}

pub(crate) fn promote_or_insert_history(
    data: &mut AppStateData,
    text: &str,
    source: &str,
) -> bool {
    let now = chrono::Utc::now();
    let metadata = if source == "PC" {
        capture_source_metadata()
    } else {
        SourceMetadata::default()
    };
    if let Some(index) = data.history.iter().position(|item| item.text == text) {
        if index == 0
            && data.history[index].source == source
            && data.history[index].source_app == metadata.app
            && data.history[index].source_title == metadata.title
        {
            return false;
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
        .trim()
        .split_whitespace()
        .collect::<Vec<&str>>()
        .join(" ")
        .chars()
        .take(48)
        .collect()
}

pub(crate) fn trim_history(history: &mut Vec<HistoryItem>) {
    history
        .sort_by(|a, b| timestamp_to_millis(&b.timestamp).cmp(&timestamp_to_millis(&a.timestamp)));
    if history.len() > MAX_HISTORY_ITEMS {
        history.truncate(MAX_HISTORY_ITEMS);
    }
}

pub(crate) fn mark_deleted_text(data: &mut AppStateData, text: String) {
    let deleted_at = chrono::Utc::now().timestamp_millis();
    let text_hash = hash_text(&text);
    if let Some(existing) = data
        .deleted_markers
        .iter_mut()
        .find(|marker| marker.text_hash == text_hash)
    {
        existing.deleted_at = deleted_at;
    } else {
        data.deleted_markers.push(DeletedMarker {
            text_hash,
            text: None,
            deleted_at,
        });
    }

    data.deleted_markers
        .sort_by(|a, b| b.deleted_at.cmp(&a.deleted_at));
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

pub(crate) fn is_deleted_by_local_marker(data: &AppStateData, text: &str, timestamp: i64) -> bool {
    if data
        .clear_history_at
        .map(|clear_at| timestamp <= clear_at)
        .unwrap_or(false)
    {
        return true;
    }

    let text_hash = hash_text(text);
    data.deleted_markers
        .iter()
        .any(|marker| marker.text_hash == text_hash && timestamp <= marker.deleted_at)
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
    current_clipboard: Option<String>,
) -> Option<String> {
    if let Some(text) = current_clipboard {
        let already_known = data.history.iter().any(|item| item.text == text);
        if !text.is_empty() && !has_deleted_text_marker(data, &text) && !already_known {
            data.history.insert(0, make_history_item(&text, "PC"));
            trim_history(&mut data.history);
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
        if is_deleted_by_local_marker(data, &entry.text, entry.timestamp) {
            continue;
        }
        if newest_incoming
            .as_ref()
            .map(|current| entry.timestamp > current.timestamp)
            .unwrap_or(true)
        {
            newest_incoming = Some(entry.clone());
        }
        if let Some(existing) = data.history.iter_mut().find(|item| item.text == entry.text) {
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
        if is_deleted_by_local_marker(data, &entry.text, entry.timestamp) {
            continue;
        }

        if let Some(existing) = data.history.iter_mut().find(|item| item.text == entry.text) {
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

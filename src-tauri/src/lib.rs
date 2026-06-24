mod cloud;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_clipboard_manager::ClipboardExt;
use tauri_plugin_global_shortcut::{GlobalShortcutExt, Shortcut, ShortcutState};
use tauri_plugin_opener::OpenerExt;
use tokio::net::{TcpListener, UdpSocket};
use tokio::time::sleep;

const AUTOSTART_HIDDEN_ARG: &str = "--fastpaste-hidden";
const MAX_HISTORY_ITEMS: usize = 500;
const CLIPBOARD_POLL_INTERVAL_MS: u64 = 250;
const CLOUD_SYNC_DEBOUNCE_MS: u64 = 3_000;
const MAX_DELETED_MARKERS: usize = 1_000;

// ── Data Models ──

#[derive(Clone, Serialize, Deserialize)]
struct AppSettings {
    hotkey: String,
    auto_start: bool,
}

#[derive(Clone, Serialize, Deserialize)]
struct HistoryItem {
    id: String,
    text: String,
    timestamp: String,
    source: String,
}

#[derive(Clone, Serialize, Deserialize)]
struct SyncEntry {
    text: String,
    timestamp: i64,
    source: String,
}

#[derive(Clone, Serialize, Deserialize)]
struct DeletedMarker {
    #[serde(default)]
    text_hash: String,
    #[serde(default, skip_serializing)]
    text: Option<String>,
    deleted_at: i64,
}

#[derive(Deserialize)]
struct WsProtocolMessage {
    app: Option<String>,
    #[serde(rename = "type")]
    kind: String,
    entries: Option<Vec<SyncEntry>>,
}

#[derive(Clone, Serialize, Deserialize)]
struct AppStateData {
    settings: AppSettings,
    history: Vec<HistoryItem>,
    ips: Vec<String>,
    clients: Vec<String>,
    #[serde(default)]
    deleted_markers: Vec<DeletedMarker>,
    #[serde(default)]
    clear_history_at: Option<i64>,
    #[serde(default)]
    cloud: cloud::CloudUiState,
}

struct AppState(Arc<Mutex<AppStateData>>);

// ── IPC Commands ──

#[tauri::command]
fn save_hotkey(hotkey: String, state: State<'_, AppState>, app: AppHandle) -> Result<(), String> {
    let new_shortcut = hotkey
        .parse::<Shortcut>()
        .map_err(|_| "Hotkey không hợp lệ. Ví dụ: CommandOrControl+Alt+Z".to_string())?;

    let mut data = state.0.lock().unwrap();
    let old_hotkey = data.settings.hotkey.clone();

    if old_hotkey == hotkey {
        return Ok(());
    }

    let old_shortcut = old_hotkey.parse::<Shortcut>().ok();
    if let Some(old) = old_shortcut {
        let _ = app.global_shortcut().unregister(old);
    }

    if let Err(error) = app
        .global_shortcut()
        .on_shortcut(new_shortcut, |app, _, event| {
            if event.state == ShortcutState::Pressed {
                toggle_window(app);
            }
        })
    {
        if let Ok(old) = old_hotkey.parse::<Shortcut>() {
            let _ = app.global_shortcut().on_shortcut(old, |app, _, event| {
                if event.state == ShortcutState::Pressed {
                    toggle_window(app);
                }
            });
        }
        return Err(format!("Không thể đăng ký hotkey: {error}"));
    }

    data.settings.hotkey = hotkey;
    save_state(&data);
    drop(data);
    broadcast_state(&app);
    Ok(())
}

use tauri_plugin_autostart::ManagerExt;

#[tauri::command]
fn save_autostart(
    autostart: bool,
    state: State<'_, AppState>,
    app: AppHandle,
) -> Result<(), String> {
    let mut data = state.0.lock().unwrap();
    data.settings.auto_start = autostart;

    let autostart_manager = app.autolaunch();
    if autostart {
        let _ = autostart_manager.disable();
        autostart_manager
            .enable()
            .map_err(|error| format!("Không thể bật tự khởi động: {error}"))?;
    } else {
        autostart_manager
            .disable()
            .map_err(|error| format!("Không thể tắt tự khởi động: {error}"))?;
    }

    save_state(&data);
    drop(data);
    broadcast_state(&app);
    Ok(())
}

#[tauri::command]
fn copy_text(text: String, app: AppHandle) {
    let _ = app.clipboard().write_text(text.clone());
    if let Some(tx) = app.try_state::<tokio::sync::broadcast::Sender<String>>() {
        let _ = tx.send(text);
    }
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.hide();
    }
}

#[tauri::command]
async fn delete_history_item(
    id: String,
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<(), String> {
    {
        let mut data = state.0.lock().unwrap();
        let Some(index) = data.history.iter().position(|item| item.id == id) else {
            return Ok(());
        };
        let item = data.history.remove(index);
        mark_deleted_text(&mut data, item.text);
        refresh_cloud_state(&mut data.cloud);
        if data.cloud.configured && data.cloud.signed_in {
            data.cloud.status = "Đã xóa mục. Google Drive sẽ cập nhật sau vài giây.".to_string();
        }

        save_state(&data);
    }

    broadcast_state(&app);
    queue_cloud_sync(&app);
    Ok(())
}

#[tauri::command]
async fn clear_history(app: AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    let data_arc = state.0.clone();
    {
        let mut data = data_arc.lock().unwrap();
        if data.history.is_empty() {
            return Ok(());
        }
        data.clear_history_at = Some(chrono::Utc::now().timestamp_millis());
        let texts: Vec<String> = data.history.iter().map(|item| item.text.clone()).collect();
        for text in texts {
            mark_deleted_text(&mut data, text);
        }
        data.history.clear();
        save_state(&data);
    }

    broadcast_state(&app);
    replace_cloud_history(
        app,
        data_arc,
        vec![],
        "Đã xóa lịch sử và cập nhật Google Drive.",
    )
    .await
}

#[tauri::command]
fn request_state(app: AppHandle) {
    broadcast_state(&app);
}

#[tauri::command]
fn open_update_url(app: AppHandle, url: String) -> Result<(), String> {
    if !url.starts_with("https://github.com/sieuxuan/fast-paste/") {
        return Err("Link cập nhật không hợp lệ.".to_string());
    }

    app.opener()
        .open_url(url, None::<&str>)
        .map_err(|error| format!("Không thể mở link cập nhật: {error}"))
}

#[tauri::command]
async fn google_sign_in(app: AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    let data_arc = state.0.clone();

    {
        let mut data = data_arc.lock().unwrap();
        refresh_cloud_state(&mut data.cloud);
        if !data.cloud.configured {
            data.cloud.status = "Google sync chưa được bật trong bản build này.".to_string();
            save_state(&data);
            drop(data);
            broadcast_state(&app);
            return Err("Google sync chưa được bật trong bản build này.".to_string());
        }

        data.cloud.syncing = true;
        data.cloud.status = "Đang mở Google login...".to_string();
        save_state(&data);
    }
    broadcast_state(&app);

    match cloud::sign_in(&app).await {
        Ok(email) => {
            {
                let mut data = data_arc.lock().unwrap();
                data.cloud.configured = cloud::is_configured();
                data.cloud.signed_in = true;
                data.cloud.account_email = email;
                data.cloud.syncing = false;
                data.cloud.status = "Đã đăng nhập Google, đang đồng bộ...".to_string();
                save_state(&data);
            }
            broadcast_state(&app);
            sync_google_drive(app, data_arc).await
        }
        Err(error) => {
            let mut data = data_arc.lock().unwrap();
            data.cloud.syncing = false;
            data.cloud.signed_in = cloud::is_signed_in();
            data.cloud.account_email = cloud::signed_in_email();
            data.cloud.status = format!("Google login lỗi: {error}");
            save_state(&data);
            drop(data);
            broadcast_state(&app);
            Err(error)
        }
    }
}

#[tauri::command]
async fn google_sync_now(app: AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    {
        let mut data = state.0.lock().unwrap();
        data.cloud.syncing = false;
        data.cloud.status = "Đang thử đồng bộ lại Google Drive...".to_string();
        save_state(&data);
    }
    broadcast_state(&app);
    sync_google_drive(app, state.0.clone()).await
}

#[tauri::command]
fn google_sign_out(app: AppHandle, state: State<'_, AppState>) {
    cloud::sign_out();
    let mut data = state.0.lock().unwrap();
    data.cloud.signed_in = false;
    data.cloud.account_email = None;
    data.cloud.syncing = false;
    data.cloud.configured = cloud::is_configured();
    data.cloud.status = "Đã đăng xuất Google Drive.".to_string();
    save_state(&data);
    drop(data);
    broadcast_state(&app);
}

// ── Helpers ──

fn toggle_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        if window.is_visible().unwrap_or(false) {
            let _ = window.hide();
        } else {
            show_window(app);
        }
    }
}

fn show_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.set_focus();
    }
}

fn should_start_hidden() -> bool {
    std::env::args().any(|arg| arg == AUTOSTART_HIDDEN_ARG)
}

fn get_settings_path() -> std::path::PathBuf {
    std::env::current_exe()
        .map(|p| p.parent().unwrap().join("settings.json"))
        .unwrap_or_else(|_| std::path::PathBuf::from("settings.json"))
}

fn save_state(data: &AppStateData) {
    let mut persisted = data.clone();
    persisted.clients.clear();
    persisted.ips.clear();
    persisted.cloud.syncing = false;

    if let Ok(json) = serde_json::to_string(&persisted) {
        let _ = std::fs::write(get_settings_path(), json);
    }
}

fn load_state() -> AppStateData {
    if let Ok(json) = std::fs::read_to_string(get_settings_path()) {
        if let Ok(mut data) = serde_json::from_str::<AppStateData>(&json) {
            data.clients.clear();
            data.ips.clear();
            data.cloud.syncing = false;
            normalize_deleted_markers(&mut data);
            refresh_cloud_state(&mut data.cloud);
            return data;
        }
    }
    AppStateData {
        settings: AppSettings {
            hotkey: "CommandOrControl+Alt+Z".to_string(),
            auto_start: false,
        },
        history: vec![],
        ips: vec![],
        clients: vec![],
        deleted_markers: vec![],
        clear_history_at: None,
        cloud: cloud::CloudUiState::default(),
    }
}

fn refresh_cloud_state(cloud_state: &mut cloud::CloudUiState) {
    cloud_state.configured = cloud::is_configured();
    cloud_state.signed_in = cloud::is_signed_in();
    cloud_state.account_email = cloud::signed_in_email();

    if !cloud_state.configured {
        cloud_state.status = "Google sync chưa được bật trong bản build này.".to_string();
    } else if cloud_state.signed_in {
        if cloud_state.status.trim().is_empty()
            || cloud_state.status.contains("chưa được bật")
            || cloud_state.status.contains("Chưa cấu hình")
        {
            cloud_state.status = "Tự đồng bộ Google Drive đang bật.".to_string();
        }
    } else if cloud_state.status.trim().is_empty()
        || cloud_state.status.contains("chưa được bật")
        || cloud_state.status.contains("Chưa cấu hình")
    {
        cloud_state.status = "Đăng nhập Google để bật tự đồng bộ.".to_string();
    }
}

fn broadcast_state(app: &AppHandle) {
    let state = app.state::<AppState>();
    let data = state.0.lock().unwrap().clone();
    let _ = app.emit("update_state", data);
}

fn queue_cloud_sync(app: &AppHandle) {
    if let Some(tx) = app.try_state::<tokio::sync::mpsc::UnboundedSender<()>>() {
        let _ = tx.send(());
    }
}

fn get_local_ips() -> Vec<String> {
    let mut ips = vec![];
    if let Ok(interfaces) = local_ip_address::list_afinet_netifas() {
        for (name, ip) in interfaces {
            if ip.is_ipv4() && !ip.is_loopback() {
                let s = ip.to_string();
                let name_lower = name.to_lowercase();

                // Skip link-local (169.254.x.x) — not routable
                if s.starts_with("169.254.") {
                    continue;
                }

                // Skip common virtual adapters
                if name_lower.contains("vmware")
                    || name_lower.contains("virtual")
                    || name_lower.contains("vbox")
                    || name_lower.contains("wsl")
                    || name_lower.contains("hyper-v")
                    || name_lower.contains("vethernet")
                    || name_lower.contains("fortinet")
                    || name_lower.contains("loopback")
                {
                    continue;
                }

                ips.push(s);
            }
        }
    }
    ips
}

fn make_history_item(text: &str, source: &str) -> HistoryItem {
    HistoryItem {
        id: format!("{}", chrono::Utc::now().timestamp_millis()),
        text: text.to_string(),
        timestamp: chrono::Utc::now().to_rfc3339(),
        source: source.to_string(),
    }
}

fn make_history_item_at(text: &str, source: &str, timestamp_millis: i64) -> HistoryItem {
    let timestamp = chrono::DateTime::<chrono::Utc>::from_timestamp_millis(timestamp_millis)
        .unwrap_or_else(chrono::Utc::now)
        .to_rfc3339();
    HistoryItem {
        id: format!("{}", timestamp_millis),
        text: text.to_string(),
        timestamp,
        source: source.to_string(),
    }
}

fn trim_history(history: &mut Vec<HistoryItem>) {
    history
        .sort_by(|a, b| timestamp_to_millis(&b.timestamp).cmp(&timestamp_to_millis(&a.timestamp)));
    if history.len() > MAX_HISTORY_ITEMS {
        history.truncate(MAX_HISTORY_ITEMS);
    }
}

fn mark_deleted_text(data: &mut AppStateData, text: String) {
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

fn normalize_deleted_markers(data: &mut AppStateData) {
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

fn is_deleted_by_local_marker(data: &AppStateData, text: &str, timestamp: i64) -> bool {
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

fn has_deleted_text_marker(data: &AppStateData, text: &str) -> bool {
    let text_hash = hash_text(text);
    data.deleted_markers
        .iter()
        .any(|marker| marker.text_hash == text_hash)
}

fn hash_text(text: &str) -> String {
    format!("{:x}", Sha256::digest(text.as_bytes()))
}

fn timestamp_to_millis(timestamp: &str) -> i64 {
    chrono::DateTime::parse_from_rfc3339(timestamp)
        .map(|value| value.timestamp_millis())
        .unwrap_or(0)
}

fn make_history_sync_payload(
    data: &mut AppStateData,
    current_clipboard: Option<String>,
) -> Option<String> {
    if let Some(text) = current_clipboard {
        if !text.is_empty()
            && !has_deleted_text_marker(data, &text)
            && !data.history.iter().any(|item| item.text == text)
        {
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

fn history_to_cloud_entries(history: &[HistoryItem]) -> Vec<cloud::CloudEntry> {
    history
        .iter()
        .map(|item| cloud::CloudEntry {
            text: item.text.clone(),
            timestamp: timestamp_to_millis(&item.timestamp),
            source: item.source.clone(),
        })
        .collect()
}

fn merge_cloud_entries_into_history(
    data: &mut AppStateData,
    entries: Vec<cloud::CloudEntry>,
) -> usize {
    let mut inserted = 0;

    for entry in entries {
        if entry.text.trim().is_empty() {
            continue;
        }
        if is_deleted_by_local_marker(data, &entry.text, entry.timestamp) {
            continue;
        }

        if let Some(existing) = data.history.iter_mut().find(|item| item.text == entry.text) {
            if entry.timestamp > timestamp_to_millis(&existing.timestamp) {
                existing.timestamp =
                    chrono::DateTime::<chrono::Utc>::from_timestamp_millis(entry.timestamp)
                        .unwrap_or_else(chrono::Utc::now)
                        .to_rfc3339();
                existing.source = entry.source;
            }
        } else {
            data.history.push(make_history_item_at(
                &entry.text,
                &entry.source,
                entry.timestamp,
            ));
            inserted += 1;
        }
    }

    trim_history(&mut data.history);
    inserted
}

async fn sync_google_drive(
    app: AppHandle,
    data_arc: Arc<Mutex<AppStateData>>,
) -> Result<(), String> {
    let (entries, deleted_markers, clear_history_at) = {
        let mut data = data_arc.lock().unwrap();
        refresh_cloud_state(&mut data.cloud);

        if !data.cloud.configured {
            data.cloud.status = "Google sync chưa được bật trong bản build này.".to_string();
            save_state(&data);
            drop(data);
            broadcast_state(&app);
            return Err("Google sync chưa được bật trong bản build này.".to_string());
        }

        if data.cloud.syncing {
            return Ok(());
        }

        if !data.cloud.signed_in {
            data.cloud.status = "Cần đăng nhập Google trước khi đồng bộ.".to_string();
            save_state(&data);
            drop(data);
            broadcast_state(&app);
            return Err("Chưa đăng nhập Google.".to_string());
        }

        data.cloud.syncing = true;
        data.cloud.status = "Đang đồng bộ Google Drive...".to_string();
        save_state(&data);
        (
            history_to_cloud_entries(&data.history),
            data.deleted_markers
                .iter()
                .map(|marker| cloud::CloudDeleteMarker {
                    text_hash: marker.text_hash.clone(),
                    deleted_at: marker.deleted_at,
                })
                .collect::<Vec<_>>(),
            data.clear_history_at,
        )
    };
    broadcast_state(&app);

    match cloud::sync_pruned(entries, deleted_markers, clear_history_at).await {
        Ok(result) => {
            let inserted = {
                let mut data = data_arc.lock().unwrap();
                let inserted = merge_cloud_entries_into_history(&mut data, result.entries);
                data.cloud.syncing = false;
                data.cloud.configured = cloud::is_configured();
                data.cloud.signed_in = true;
                data.cloud.account_email = cloud::signed_in_email();
                data.cloud.last_sync_at = Some(chrono::Utc::now().timestamp_millis());
                data.cloud.status = format!(
                    "Tự đồng bộ Google Drive: {} mục, tải về {} mục mới.",
                    result.merged_count, inserted
                );
                save_state(&data);
                inserted
            };
            broadcast_state(&app);

            let _ = inserted;
            Ok(())
        }
        Err(error) => {
            let mut data = data_arc.lock().unwrap();
            data.cloud.syncing = false;
            data.cloud.signed_in = cloud::is_signed_in();
            data.cloud.account_email = cloud::signed_in_email();
            data.cloud.status = format!("Đồng bộ Google lỗi: {error}");
            save_state(&data);
            drop(data);
            broadcast_state(&app);
            Err(error)
        }
    }
}

async fn replace_cloud_history(
    app: AppHandle,
    data_arc: Arc<Mutex<AppStateData>>,
    entries: Vec<cloud::CloudEntry>,
    success_status: &str,
) -> Result<(), String> {
    let should_replace = {
        let mut data = data_arc.lock().unwrap();
        refresh_cloud_state(&mut data.cloud);
        if !data.cloud.configured || !data.cloud.signed_in {
            return Ok(());
        }

        data.cloud.syncing = true;
        data.cloud.status = "Đang cập nhật Google Drive...".to_string();
        save_state(&data);
        true
    };

    if !should_replace {
        return Ok(());
    }

    broadcast_state(&app);
    match cloud::replace(entries).await {
        Ok(count) => {
            let mut data = data_arc.lock().unwrap();
            data.cloud.syncing = false;
            data.cloud.last_sync_at = Some(chrono::Utc::now().timestamp_millis());
            data.cloud.status = format!("{success_status} Còn {count} mục.");
            save_state(&data);
            drop(data);
            broadcast_state(&app);
            Ok(())
        }
        Err(error) => {
            let mut data = data_arc.lock().unwrap();
            data.cloud.syncing = false;
            data.cloud.status = format!("Cập nhật Google Drive lỗi: {error}");
            save_state(&data);
            drop(data);
            broadcast_state(&app);
            Err(error)
        }
    }
}

// ── Application Entry ──

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let initial_data = load_state();
    let data_arc = Arc::new(Mutex::new(initial_data));
    let (ws_tx, _ws_rx) = tokio::sync::broadcast::channel::<String>(100);

    tauri::Builder::default()
        .plugin(tauri_plugin_os::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec![AUTOSTART_HIDDEN_ARG]),
        ))
        .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        .setup(move |app| {
            let app_handle = app.handle().clone();
            let app_handle_clip = app.handle().clone();
            let start_hidden = should_start_hidden();

            // ── System Tray with Exit menu ──
            use tauri::menu::{MenuBuilder, MenuItemBuilder};
            use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder};

            let quit_handle = app.handle().clone();
            let show_item = MenuItemBuilder::with_id("show", "Mở FastPaste").build(app)?;
            let quit_item = MenuItemBuilder::with_id("quit", "Thoát").build(app)?;
            let menu = MenuBuilder::new(app)
                .item(&show_item)
                .separator()
                .item(&quit_item)
                .build()?;

            let tray_click_handle = app.handle().clone();
            let _tray = TrayIconBuilder::new()
                .tooltip("FastPaste")
                .icon(app.default_window_icon().unwrap().clone())
                .menu(&menu)
                .on_menu_event(move |_app, event| match event.id().as_ref() {
                    "show" => show_window(&quit_handle),
                    "quit" => std::process::exit(0),
                    _ => {}
                })
                .on_tray_icon_event(move |_tray, event| {
                    if let tauri::tray::TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        toggle_window(&tray_click_handle);
                    }
                })
                .build(app)?;

            // ── Close-to-tray ──
            if let Some(window) = app.get_webview_window("main") {
                if start_hidden {
                    let _ = window.hide();
                } else {
                    let _ = window.show();
                    let _ = window.set_focus();
                }

                let w = window.clone();
                let _ = window.on_window_event(move |event| {
                    if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                        let _ = w.hide();
                        api.prevent_close();
                    }
                });
            }

            // ── State init ──
            let mut data = data_arc.lock().unwrap();
            data.ips = get_local_ips();
            let local_ips = data.ips.clone();
            drop(data);
            app.manage(AppState(data_arc.clone()));
            app.manage(ws_tx.clone());

            let (cloud_sync_tx, mut cloud_sync_rx) = tokio::sync::mpsc::unbounded_channel::<()>();
            app.manage(cloud_sync_tx.clone());

            let app_cloud = app.handle().clone();
            let data_cloud = data_arc.clone();
            tauri::async_runtime::spawn(async move {
                while cloud_sync_rx.recv().await.is_some() {
                    loop {
                        sleep(Duration::from_millis(CLOUD_SYNC_DEBOUNCE_MS)).await;
                        let mut received_more = false;
                        while cloud_sync_rx.try_recv().is_ok() {
                            received_more = true;
                        }
                        if !received_more {
                            break;
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

            if cloud::is_configured() && cloud::is_signed_in() {
                let _ = cloud_sync_tx.send(());
            }

            let should_refresh_autostart = {
                let data = data_arc.lock().unwrap();
                data.settings.auto_start
            };
            if should_refresh_autostart {
                let autostart_manager = app.autolaunch();
                let _ = autostart_manager.disable();
                let _ = autostart_manager.enable();
            }

            // ── UDP Broadcast (discovery) ──
            let hostname = gethostname::gethostname().to_string_lossy().into_owned();
            tauri::async_runtime::spawn(async move {
                let mut sockets = vec![];
                for ip in &local_ips {
                    if let Ok(sock) = UdpSocket::bind(format!("{}:0", ip)).await {
                        let _ = sock.set_broadcast(true);
                        sockets.push(sock);
                    }
                }
                if sockets.is_empty() {
                    if let Ok(sock) = UdpSocket::bind("0.0.0.0:0").await {
                        let _ = sock.set_broadcast(true);
                        sockets.push(sock);
                    }
                }

                loop {
                    let msg = format!("FASTPASTE:{}:4567", hostname);
                    for sock in &sockets {
                        let _ = sock.send_to(msg.as_bytes(), "255.255.255.255:4568").await;
                        // Subnet-specific broadcast as fallback
                        if let Ok(addr) = sock.local_addr() {
                            let ip_str = addr.ip().to_string();
                            let parts: Vec<&str> = ip_str.split('.').collect();
                            if parts.len() == 4 {
                                let subnet =
                                    format!("{}.{}.{}.255:4568", parts[0], parts[1], parts[2]);
                                let _ = sock.send_to(msg.as_bytes(), subnet.as_str()).await;
                            }
                        }
                    }
                    sleep(Duration::from_secs(2)).await;
                }
            });

            // ── WebSocket Server ──
            let data_ws = data_arc.clone();
            let app_ws = app_handle.clone();
            let ws_tx_ws = ws_tx.clone();
            tauri::async_runtime::spawn(async move {
                let Ok(listener) = TcpListener::bind("0.0.0.0:4567").await else {
                    return;
                };
                while let Ok((stream, addr)) = listener.accept().await {
                    let ip = addr.ip().to_string();

                    // Register client
                    {
                        let mut d = data_ws.lock().unwrap();
                        if !d.clients.contains(&ip) {
                            d.clients.push(ip.clone());
                        }
                    }
                    broadcast_state(&app_ws);

                    let Ok(ws_stream) = tokio_tungstenite::accept_async(stream).await else {
                        continue;
                    };
                    let (mut write, mut read) = futures_util::StreamExt::split(ws_stream);

                    // Sender: exchange full history first, then forward future clipboard changes.
                    let mut rx = ws_tx_ws.subscribe();
                    let app_initial_sync = app_ws.clone();
                    let data_initial_sync = data_ws.clone();
                    tauri::async_runtime::spawn(async move {
                        use futures_util::SinkExt;
                        let current_clipboard = app_initial_sync.clipboard().read_text().ok();
                        let payload = {
                            let mut data = data_initial_sync.lock().unwrap();
                            make_history_sync_payload(&mut data, current_clipboard)
                        };
                        if let Some(payload) = payload {
                            if !payload.is_empty() {
                                let _ = write
                                    .send(tokio_tungstenite::tungstenite::Message::Text(
                                        payload.into(),
                                    ))
                                    .await;
                            }
                        }

                        while let Ok(msg) = rx.recv().await {
                            let _ = write
                                .send(tokio_tungstenite::tungstenite::Message::Text(msg.into()))
                                .await;
                        }
                    });

                    // Receiver: read messages from this client
                    let data_rx = data_ws.clone();
                    let app_rx = app_ws.clone();
                    let ip_clone = ip.clone();
                    tauri::async_runtime::spawn(async move {
                        while let Ok(Some(Ok(msg))) = tokio::time::timeout(
                            Duration::from_secs(45),
                            futures_util::StreamExt::next(&mut read),
                        )
                        .await
                        {
                            if let Ok(text) = msg.to_text() {
                                if !text.is_empty() {
                                    if let Ok(protocol) =
                                        serde_json::from_str::<WsProtocolMessage>(text)
                                    {
                                        if protocol.app.as_deref() == Some("fastpaste")
                                            && protocol.kind == "history_sync"
                                        {
                                            let entries = protocol.entries.unwrap_or_default();
                                            let mut newest_incoming: Option<SyncEntry> = None;
                                            let latest_local_timestamp = {
                                                let mut d = data_rx.lock().unwrap();
                                                let latest = d
                                                    .history
                                                    .first()
                                                    .map(|item| {
                                                        timestamp_to_millis(&item.timestamp)
                                                    })
                                                    .unwrap_or(0);

                                                for entry in entries {
                                                    if entry.text.is_empty() {
                                                        continue;
                                                    }
                                                    if is_deleted_by_local_marker(
                                                        &d,
                                                        &entry.text,
                                                        entry.timestamp,
                                                    ) {
                                                        continue;
                                                    }
                                                    if newest_incoming
                                                        .as_ref()
                                                        .map(|current| {
                                                            entry.timestamp > current.timestamp
                                                        })
                                                        .unwrap_or(true)
                                                    {
                                                        newest_incoming = Some(entry.clone());
                                                    }
                                                    if !d
                                                        .history
                                                        .iter()
                                                        .any(|item| item.text == entry.text)
                                                    {
                                                        d.history.push(make_history_item_at(
                                                            &entry.text,
                                                            &entry.source,
                                                            entry.timestamp,
                                                        ));
                                                    }
                                                }

                                                d.history.sort_by(|a, b| {
                                                    timestamp_to_millis(&b.timestamp)
                                                        .cmp(&timestamp_to_millis(&a.timestamp))
                                                });
                                                trim_history(&mut d.history);
                                                save_state(&d);
                                                latest
                                            };

                                            if let Some(entry) = newest_incoming {
                                                if entry.timestamp > latest_local_timestamp {
                                                    let _ =
                                                        app_rx.clipboard().write_text(entry.text);
                                                }
                                            }
                                            broadcast_state(&app_rx);
                                            queue_cloud_sync(&app_rx);
                                            continue;
                                        }
                                    }

                                    let _ = app_rx.clipboard().write_text(text.to_string());
                                    let mut d = data_rx.lock().unwrap();
                                    d.history.insert(0, make_history_item(text, "ANDROID"));
                                    trim_history(&mut d.history);
                                    save_state(&d);
                                    drop(d);
                                    broadcast_state(&app_rx);
                                    queue_cloud_sync(&app_rx);
                                }
                            }
                        }
                        // Client disconnected
                        {
                            let mut d = data_rx.lock().unwrap();
                            d.clients.retain(|c| c != &ip_clone);
                        }
                        broadcast_state(&app_rx);
                    });
                }
            });

            // ── Clipboard Poller ──
            let data_clip = data_arc.clone();
            tauri::async_runtime::spawn(async move {
                let mut last_text = String::new();
                loop {
                    if let Ok(text) = app_handle_clip.clipboard().read_text() {
                        if !text.is_empty() && text != last_text {
                            let is_startup_snapshot = last_text.is_empty();
                            last_text = text.clone();
                            let mut d = data_clip.lock().unwrap();
                            let is_new = d.history.first().map(|h| h.text != text).unwrap_or(true);
                            let is_deleted_startup_clipboard =
                                is_startup_snapshot && has_deleted_text_marker(&d, &text);
                            if is_new && !is_deleted_startup_clipboard {
                                d.history.insert(0, make_history_item(&text, "PC"));
                                trim_history(&mut d.history);
                                save_state(&d);
                                drop(d);
                                broadcast_state(&app_handle_clip);
                                let _ = ws_tx.send(text);
                                queue_cloud_sync(&app_handle_clip);
                            }
                        }
                    }
                    sleep(Duration::from_millis(CLIPBOARD_POLL_INTERVAL_MS)).await;
                }
            });

            // ── Register initial hotkey ──
            let d = data_arc.lock().unwrap();
            if let Ok(shortcut) = d.settings.hotkey.parse::<Shortcut>() {
                let _ = app
                    .global_shortcut()
                    .on_shortcut(shortcut, |app, _, event| {
                        if event.state == ShortcutState::Pressed {
                            toggle_window(app);
                        }
                    });
            }
            drop(d);

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            save_hotkey,
            save_autostart,
            copy_text,
            delete_history_item,
            clear_history,
            request_state,
            open_update_url,
            google_sign_in,
            google_sync_now,
            google_sign_out
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

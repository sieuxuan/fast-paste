mod cloud;
mod history;
mod hotkeys;
mod network;
mod state;

use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_autostart::ManagerExt;
use tauri_plugin_clipboard_manager::ClipboardExt;
use tauri_plugin_opener::OpenerExt;
use tokio::time::sleep;

use history::*;
use hotkeys::*;
use state::*;

const AUTOSTART_HIDDEN_ARG: &str = "--fastpaste-hidden";
const CLIPBOARD_POLL_INTERVAL_MS: u64 = 250;
const CLOUD_SYNC_DEBOUNCE_MS: u64 = 3_000;
const STARTUP_CLOUD_SYNC_DELAY_MS: u64 = 1_500;

// ── IPC Commands ──

#[tauri::command]
fn save_hotkey(hotkey: String, state: State<'_, AppState>, app: AppHandle) -> Result<(), String> {
    save_role_hotkey(hotkey, HotkeyRole::Toggle, state, app)
}

#[tauri::command]
fn save_edit_hotkey(
    hotkey: String,
    state: State<'_, AppState>,
    app: AppHandle,
) -> Result<(), String> {
    save_role_hotkey(hotkey, HotkeyRole::Edit, state, app)
}

#[tauri::command]
fn save_pinned_hotkey(
    hotkey: String,
    state: State<'_, AppState>,
    app: AppHandle,
) -> Result<(), String> {
    save_role_hotkey(hotkey, HotkeyRole::Pinned, state, app)
}

#[tauri::command]
fn save_quick_slot_hotkey(
    hotkey: String,
    state: State<'_, AppState>,
    app: AppHandle,
) -> Result<(), String> {
    let prefix = validate_quick_slot_prefix(&hotkey)?;

    let mut data = state.0.lock().unwrap();
    let old_prefix = data.settings.quick_slot_hotkey.clone();

    if normalize_hotkey_text(&old_prefix) == normalize_hotkey_text(&prefix) {
        return Ok(());
    }

    for slot in 1..=9 {
        let slot_hotkey = quick_slot_hotkey(&prefix, slot);
        let normalized_slot = normalize_hotkey_text(&slot_hotkey);
        if normalized_slot == normalize_hotkey_text(&data.settings.hotkey) {
            return Err("Phím dán nhanh bị trùng phím ẩn/hiện FastPaste.".to_string());
        }
        if normalized_slot == normalize_hotkey_text(&data.settings.edit_hotkey) {
            return Err("Phím dán nhanh bị trùng phím sửa clipboard mới nhất.".to_string());
        }
        if normalized_slot == normalize_hotkey_text(&data.settings.pinned_hotkey) {
            return Err("Phím dán nhanh bị trùng phím mở bảng ghim.".to_string());
        }
    }

    unregister_quick_paste_slots(&app, &old_prefix);
    if let Err(error) = register_quick_paste_slots(&app, &prefix) {
        unregister_quick_paste_slots(&app, &prefix);
        let _ = register_quick_paste_slots(&app, &old_prefix);
        return Err(error);
    }

    data.settings.quick_slot_hotkey = prefix;
    save_state(&data);
    drop(data);
    broadcast_state(&app);
    Ok(())
}

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
fn save_always_on_top(always_on_top: bool, state: State<'_, AppState>, app: AppHandle) {
    let mut data = state.0.lock().unwrap();
    data.settings.always_on_top = always_on_top;
    save_state(&data);
    drop(data);

    if let Some(window) = app.get_webview_window("main") {
        let _ = window.set_always_on_top(always_on_top);
    }
    broadcast_state(&app);
}

#[tauri::command]
fn copy_text(text: String, app: AppHandle, state: State<'_, AppState>) {
    let _ = app.clipboard().write_text(text.clone());
    let history_changed = {
        let mut data = state.0.lock().unwrap();
        let changed = promote_or_insert_history(&mut data, &text, "PC");
        if changed {
            save_state(&data);
        }
        changed
    };
    if history_changed {
        broadcast_state(&app);
        queue_cloud_sync(&app);
    }
    if let Some(tx) = app.try_state::<tokio::sync::broadcast::Sender<String>>() {
        let _ = tx.send(text);
    }
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.hide();
    }
}

#[tauri::command]
fn update_history_item(
    id: String,
    text: String,
    folder: String,
    copy_after_save: bool,
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<(), String> {
    if text.trim().is_empty() {
        return Err("Nội dung không được để trống.".to_string());
    }

    {
        let mut data = state.0.lock().unwrap();
        let Some(index) = data.history.iter().position(|item| item.id == id) else {
            return Err("Không tìm thấy mục clipboard cần sửa.".to_string());
        };

        let mut item = data.history.remove(index);
        if item.text != text {
            mark_deleted_text(&mut data, item.text.clone());
        }

        data.history
            .retain(|existing| existing.id == item.id || existing.text != text);
        item.text = text.clone();
        item.folder = clean_folder_name(&folder);
        item.timestamp = chrono::Utc::now().to_rfc3339();
        item.source = "PC".to_string();
        data.history.insert(0, item);
        trim_history(&mut data.history);

        refresh_cloud_state(&mut data.cloud);
        if data.cloud.configured && data.cloud.signed_in {
            data.cloud.status = "Đã sửa mục. Google Drive sẽ cập nhật sau vài giây.".to_string();
        }

        save_state(&data);
    }

    broadcast_state(&app);
    if copy_after_save {
        let _ = app.clipboard().write_text(text.clone());
        if let Some(tx) = app.try_state::<tokio::sync::broadcast::Sender<String>>() {
            let _ = tx.send(text);
        }
    }
    queue_cloud_sync(&app);
    Ok(())
}

#[tauri::command]
fn toggle_history_pin(
    id: String,
    pinned: bool,
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<(), String> {
    {
        let mut data = state.0.lock().unwrap();
        let Some(item) = data.history.iter_mut().find(|item| item.id == id) else {
            return Err("Không tìm thấy mục clipboard cần ghim.".to_string());
        };

        item.pinned = pinned;
        if !pinned {
            item.quick_slot = None;
        }
        save_state(&data);
    }

    broadcast_state(&app);
    Ok(())
}

#[tauri::command]
fn set_pinned_slot(
    id: String,
    slot: Option<u8>,
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<(), String> {
    if let Some(slot) = slot {
        if !(1..=9).contains(&slot) {
            return Err("Vị trí phải từ 1 đến 9.".to_string());
        }
    }

    {
        let mut data = state.0.lock().unwrap();
        if let Some(slot) = slot {
            for item in data.history.iter_mut() {
                if item.quick_slot == Some(slot) {
                    item.quick_slot = None;
                }
            }
        }

        let Some(item) = data.history.iter_mut().find(|item| item.id == id) else {
            return Err("Không tìm thấy mục đã ghim.".to_string());
        };

        if !item.pinned {
            return Err("Chỉ có thể đặt vị trí cho mục đã ghim.".to_string());
        }

        item.quick_slot = slot;
        save_state(&data);
    }

    broadcast_state(&app);
    Ok(())
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
fn add_history_item(
    text: String,
    folder: String,
    copy_after_save: bool,
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<(), String> {
    if text.trim().is_empty() {
        return Err("Nội dung không được để trống.".to_string());
    }
    {
        let mut data = state.0.lock().unwrap();
        data.history.retain(|existing| existing.text != text);
        let mut item = make_history_item(&text, "PC");
        item.folder = clean_folder_name(&folder);
        data.history.insert(0, item);
        trim_history(&mut data.history);
        refresh_cloud_state(&mut data.cloud);
        if data.cloud.configured && data.cloud.signed_in {
            data.cloud.status =
                "Đã thêm mục mới. Google Drive sẽ cập nhật sau vài giây.".to_string();
        }
        save_state(&data);
    }
    broadcast_state(&app);
    if copy_after_save {
        let _ = app.clipboard().write_text(text.clone());
        if let Some(tx) = app.try_state::<tokio::sync::broadcast::Sender<String>>() {
            let _ = tx.send(text);
        }
    }
    queue_cloud_sync(&app);
    Ok(())
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
            data.cloud.status = "Chưa bật đồng bộ Google trong bản build này.".to_string();
            save_state(&data);
            drop(data);
            broadcast_state(&app);
            return Err("Chưa bật đồng bộ Google trong bản build này.".to_string());
        }

        data.cloud.syncing = true;
        data.cloud.status = "Đang mở đăng nhập Google...".to_string();
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
            data.cloud.status = format!("Đăng nhập Google lỗi: {error}");
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

// ── Window Helpers ──

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
        let always_on_top = app
            .try_state::<AppState>()
            .map(|state| state.0.lock().unwrap().settings.always_on_top)
            .unwrap_or_else(default_always_on_top);
        let _ = window.set_always_on_top(always_on_top);
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

fn open_last_clipboard_editor(app: &AppHandle) {
    show_window(app);
    let entry = app
        .try_state::<AppState>()
        .and_then(|state| state.0.lock().ok()?.history.first().cloned());
    let _ = app.emit("edit_history_item", entry);
}

fn open_pinned_clipboard_list(app: &AppHandle) {
    show_window(app);
    let mut entries = app
        .try_state::<AppState>()
        .and_then(|state| {
            let data = state.0.lock().ok()?;
            Some(
                data.history
                    .iter()
                    .filter(|item| item.pinned)
                    .cloned()
                    .collect::<Vec<_>>(),
            )
        })
        .unwrap_or_default();
    entries.sort_by(|a, b| match (a.quick_slot, b.quick_slot) {
        (Some(left), Some(right)) => left.cmp(&right),
        (Some(_), None) => std::cmp::Ordering::Less,
        (None, Some(_)) => std::cmp::Ordering::Greater,
        (None, None) => timestamp_to_millis(&b.timestamp).cmp(&timestamp_to_millis(&a.timestamp)),
    });
    let _ = app.emit("open_pinned_list", entries);
}

fn should_start_hidden() -> bool {
    std::env::args().any(|arg| arg == AUTOSTART_HIDDEN_ARG)
}

// ── Cloud Sync Orchestration ──

async fn sync_google_drive(
    app: AppHandle,
    data_arc: Arc<Mutex<AppStateData>>,
) -> Result<(), String> {
    let (entries, deleted_markers, clear_history_at) = {
        let mut data = data_arc.lock().unwrap();
        refresh_cloud_state(&mut data.cloud);

        if !data.cloud.configured {
            data.cloud.status = "Chưa bật đồng bộ Google trong bản build này.".to_string();
            save_state(&data);
            drop(data);
            broadcast_state(&app);
            return Err("Chưa bật đồng bộ Google trong bản build này.".to_string());
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
                let (inserted, _history_changed) =
                    merge_cloud_entries_into_history(&mut data, result.entries);
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
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            show_window(app);
        }))
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
            data.ips = network::get_local_ips();
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
                let startup_cloud_sync_tx = cloud_sync_tx.clone();
                tauri::async_runtime::spawn(async move {
                    sleep(Duration::from_millis(STARTUP_CLOUD_SYNC_DELAY_MS)).await;
                    let _ = startup_cloud_sync_tx.send(());
                });
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

            // ── Networking: UDP discovery broadcast + WebSocket server ──
            network::spawn_udp_broadcaster(&app_handle, data_arc.clone());
            network::spawn_ws_server(&app_handle, data_arc.clone(), ws_tx.clone());

            // ── Clipboard Poller ──
            let data_clip = data_arc.clone();
            tauri::async_runtime::spawn(async move {
                let mut last_text = app_handle_clip.clipboard().read_text().unwrap_or_default();
                loop {
                    if let Ok(text) = app_handle_clip.clipboard().read_text() {
                        if !text.is_empty() && text != last_text {
                            last_text = text.clone();
                            let history_changed = {
                                let mut d = data_clip.lock().unwrap();
                                let changed = promote_or_insert_history(&mut d, &text, "PC");
                                if changed {
                                    save_state(&d);
                                }
                                changed
                            };
                            if history_changed {
                                broadcast_state(&app_handle_clip);
                                let _ = ws_tx.send(text);
                                queue_cloud_sync(&app_handle_clip);
                            }
                        }
                    }
                    sleep(Duration::from_millis(CLIPBOARD_POLL_INTERVAL_MS)).await;
                }
            });

            // ── Register initial hotkeys ──
            let settings = {
                let d = data_arc.lock().unwrap();
                d.settings.clone()
            };
            register_initial_hotkeys(app.handle(), &settings);

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            save_hotkey,
            save_edit_hotkey,
            save_pinned_hotkey,
            save_quick_slot_hotkey,
            save_autostart,
            save_always_on_top,
            copy_text,
            update_history_item,
            toggle_history_pin,
            set_pinned_slot,
            delete_history_item,
            clear_history,
            add_history_item,
            request_state,
            open_update_url,
            google_sign_in,
            google_sync_now,
            google_sign_out
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

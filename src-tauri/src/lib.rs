use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager, State};
use serde::{Deserialize, Serialize};
use tokio::net::{UdpSocket, TcpListener};
use tokio::time::sleep;
use tauri_plugin_global_shortcut::{GlobalShortcutExt, Shortcut, ShortcutState};
use tauri_plugin_clipboard_manager::ClipboardExt;

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
struct AppStateData {
    settings: AppSettings,
    history: Vec<HistoryItem>,
    ips: Vec<String>,
    clients: Vec<String>,
}

struct AppState(Arc<Mutex<AppStateData>>);

// ── IPC Commands ──

#[tauri::command]
fn save_hotkey(hotkey: String, state: State<'_, AppState>, app: AppHandle) {
    let mut data = state.0.lock().unwrap();

    if let Ok(old) = data.settings.hotkey.parse::<Shortcut>() {
        let _ = app.global_shortcut().unregister(old);
    }

    data.settings.hotkey = hotkey.clone();

    if let Ok(new) = hotkey.parse::<Shortcut>() {
        let _ = app.global_shortcut().on_shortcut(new, |app, _, event| {
            if event.state == ShortcutState::Pressed {
                toggle_window(app);
            }
        });
    }

    save_state(&data);
    drop(data);
    broadcast_state(&app);
}

use tauri_plugin_autostart::ManagerExt;

#[tauri::command]
fn save_autostart(autostart: bool, state: State<'_, AppState>, app: AppHandle) {
    let mut data = state.0.lock().unwrap();
    data.settings.auto_start = autostart;
    
    let autostart_manager = app.autolaunch();
    if autostart {
        let _ = autostart_manager.enable();
    } else {
        let _ = autostart_manager.disable();
    }

    save_state(&data);
    drop(data);
    broadcast_state(&app);
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
fn request_state(app: AppHandle) {
    broadcast_state(&app);
}

// ── Helpers ──

fn toggle_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        if window.is_visible().unwrap_or(false) {
            let _ = window.hide();
        } else {
            let _ = window.show();
            let _ = window.set_focus();
        }
    }
}

fn save_state(data: &AppStateData) {
    if let Ok(json) = serde_json::to_string(data) {
        let _ = std::fs::write("settings.json", json);
    }
}

fn load_state() -> AppStateData {
    if let Ok(json) = std::fs::read_to_string("settings.json") {
        if let Ok(data) = serde_json::from_str(&json) {
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
    }
}

fn broadcast_state(app: &AppHandle) {
    let state = app.state::<AppState>();
    let data = state.0.lock().unwrap().clone();
    let _ = app.emit("update_state", data);
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
                    || name_lower.contains("loopback") {
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

// ── Application Entry ──

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let initial_data = load_state();
    let data_arc = Arc::new(Mutex::new(initial_data));
    let (ws_tx, _ws_rx) = tokio::sync::broadcast::channel::<String>(100);

    tauri::Builder::default()
        .plugin(tauri_plugin_os::init())
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec![]),
        ))
        .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        .setup(move |app| {
            let app_handle = app.handle().clone();
            let app_handle_clip = app.handle().clone();

            // ── System Tray with Exit menu ──
            use tauri::menu::{MenuBuilder, MenuItemBuilder};
            use tauri::tray::{TrayIconBuilder, MouseButton, MouseButtonState};

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
                .on_menu_event(move |_app, event| {
                    match event.id().as_ref() {
                        "show" => toggle_window(&quit_handle),
                        "quit" => std::process::exit(0),
                        _ => {}
                    }
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

            // ── UDP Broadcast (discovery) ──
            let hostname = gethostname::gethostname()
                .to_string_lossy()
                .into_owned();
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
                                let subnet = format!("{}.{}.{}.255:4568", parts[0], parts[1], parts[2]);
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

                    // Sender: forward broadcast messages to this client
                    let mut rx = ws_tx_ws.subscribe();
                    tauri::async_runtime::spawn(async move {
                        while let Ok(msg) = rx.recv().await {
                            use futures_util::SinkExt;
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
                        while let Ok(Some(Ok(msg))) = tokio::time::timeout(Duration::from_secs(45), futures_util::StreamExt::next(&mut read)).await {
                            if let Ok(text) = msg.to_text() {
                                if !text.is_empty() {
                                    let _ = app_rx.clipboard().write_text(text.to_string());
                                    let mut d = data_rx.lock().unwrap();
                                    d.history.insert(0, make_history_item(text, "ANDROID"));
                                    if d.history.len() > 100 {
                                        d.history.truncate(100);
                                    }
                                    save_state(&d);
                                    drop(d);
                                    broadcast_state(&app_rx);
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
                            last_text = text.clone();
                            let mut d = data_clip.lock().unwrap();
                            let is_new = d
                                .history
                                .first()
                                .map(|h| h.text != text)
                                .unwrap_or(true);
                            if is_new {
                                d.history.insert(0, make_history_item(&text, "PC"));
                                if d.history.len() > 100 {
                                    d.history.truncate(100);
                                }
                                save_state(&d);
                                drop(d);
                                broadcast_state(&app_handle_clip);
                                let _ = ws_tx.send(text);
                            }
                        }
                    }
                    sleep(Duration::from_millis(800)).await;
                }
            });

            // ── Register initial hotkey ──
            let d = data_arc.lock().unwrap();
            if let Ok(shortcut) = d.settings.hotkey.parse::<Shortcut>() {
                let _ = app.global_shortcut().on_shortcut(shortcut, |app, _, event| {
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
            request_state
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

use serde::Deserialize;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tauri::AppHandle;
use tauri_plugin_clipboard_manager::ClipboardExt;
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::broadcast;
use tokio::time::sleep;
use tokio_tungstenite::tungstenite::Message;

use crate::history::{self, SyncEntry};
use crate::state::{broadcast_state, queue_cloud_sync, save_state, AppStateData};

const BROADCAST_INTERVAL: Duration = Duration::from_secs(2);
/// Refresh the interface list every N broadcast ticks (~30s at 2s/tick).
const IP_REFRESH_TICKS: u32 = 15;
/// Don't let a persistently failing adapter force a rebind more often than this.
const FORCED_REBIND_MIN_INTERVAL: Duration = Duration::from_secs(30);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
const CLIENT_IDLE_TIMEOUT: Duration = Duration::from_secs(45);

#[derive(Deserialize)]
struct WsProtocolMessage {
    app: Option<String>,
    #[serde(rename = "type")]
    kind: String,
    entries: Option<Vec<SyncEntry>>,
}

pub(crate) fn get_local_ips() -> Vec<String> {
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
    // Stable order: enumeration order can vary between calls and would
    // otherwise trigger spurious rebinds and UI churn.
    ips.sort();
    ips
}

async fn bind_broadcast_sockets(ips: &[String]) -> Vec<UdpSocket> {
    let mut sockets = vec![];
    for ip in ips {
        if let Ok(sock) = UdpSocket::bind(format!("{ip}:0")).await {
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
    sockets
}

/// Announce `FASTPASTE:<hostname>:4567` on every physical interface, rebinding
/// when interfaces change (sleep/wake, Wi-Fi switch) or sends start failing.
pub(crate) fn spawn_udp_broadcaster(app: &AppHandle, data: Arc<Mutex<AppStateData>>) {
    let hostname = gethostname::gethostname().to_string_lossy().into_owned();
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let mut sockets: Vec<UdpSocket> = vec![];
        let mut bound_ips: Vec<String> = vec![];
        let mut ticks_since_refresh = IP_REFRESH_TICKS; // refresh on first pass
        let mut last_forced_rebind: Option<Instant> = None;
        let msg = format!("FASTPASTE:{}:4567", hostname);
        loop {
            if sockets.is_empty() || ticks_since_refresh >= IP_REFRESH_TICKS {
                ticks_since_refresh = 0;
                let ips = get_local_ips();
                if sockets.is_empty() || ips != bound_ips {
                    sockets = bind_broadcast_sockets(&ips).await;
                    bound_ips = ips.clone();
                    let changed = {
                        let mut d = data.lock().unwrap();
                        if d.ips != ips {
                            d.ips = ips;
                            true
                        } else {
                            false
                        }
                    };
                    if changed {
                        broadcast_state(&app);
                    }
                }
            }
            ticks_since_refresh += 1;

            let mut send_failed = false;
            for sock in &sockets {
                if sock
                    .send_to(msg.as_bytes(), "255.255.255.255:4568")
                    .await
                    .is_err()
                {
                    send_failed = true;
                }
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
            // A socket can keep a stale IP after resume even when the list
            // matches — force a rebind, but rate-limited so one adapter that
            // always rejects broadcasts can't cause rebind churn every tick.
            if send_failed
                && last_forced_rebind
                    .map_or(true, |at| at.elapsed() >= FORCED_REBIND_MIN_INTERVAL)
            {
                sockets.clear();
                last_forced_rebind = Some(Instant::now());
            }
            sleep(BROADCAST_INTERVAL).await;
        }
    });
}

/// Rebuild the UI-visible client list from the per-IP connection counts.
/// Callers must hold the counts lock; lock order is always counts → data.
fn set_clients_from_counts(counts: &HashMap<String, usize>, data: &Mutex<AppStateData>) {
    let mut d = data.lock().unwrap();
    d.clients = counts.keys().cloned().collect();
}

pub(crate) fn spawn_ws_server(
    app: &AppHandle,
    data: Arc<Mutex<AppStateData>>,
    ws_tx: broadcast::Sender<String>,
) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let Ok(listener) = TcpListener::bind("0.0.0.0:4567").await else {
            return;
        };
        let client_counts = Arc::new(Mutex::new(HashMap::<String, usize>::new()));
        loop {
            let (stream, addr) = match listener.accept().await {
                Ok(accepted) => accepted,
                // Transient accept errors (e.g. a client resetting mid-handshake)
                // must not kill the server task.
                Err(_) => {
                    sleep(Duration::from_millis(100)).await;
                    continue;
                }
            };
            // Handshake and client I/O run in their own task so one stalled
            // client can never block the accept loop.
            tauri::async_runtime::spawn(handle_client(
                stream,
                addr.ip().to_string(),
                app.clone(),
                data.clone(),
                client_counts.clone(),
                ws_tx.subscribe(),
            ));
        }
    });
}

async fn handle_client(
    stream: TcpStream,
    ip: String,
    app: AppHandle,
    data: Arc<Mutex<AppStateData>>,
    client_counts: Arc<Mutex<HashMap<String, usize>>>,
    mut rx: broadcast::Receiver<String>,
) {
    let Ok(Ok(ws_stream)) = tokio::time::timeout(
        HANDSHAKE_TIMEOUT,
        tokio_tungstenite::accept_async(stream),
    )
    .await
    else {
        return;
    };

    // Register client only after a successful WS handshake; count connections
    // per IP so a quick reconnect doesn't unlist the new one.
    {
        let mut counts = client_counts.lock().unwrap();
        *counts.entry(ip.clone()).or_insert(0) += 1;
        set_clients_from_counts(&counts, &data);
    }
    broadcast_state(&app);

    let (mut write, mut read) = futures_util::StreamExt::split(ws_stream);

    // Sender: exchange full history first, then forward future clipboard changes.
    let app_sync = app.clone();
    let data_sync = data.clone();
    let sender_task = tauri::async_runtime::spawn(async move {
        use futures_util::SinkExt;
        let current_clipboard = app_sync.clipboard().read_text().ok();
        let payload = {
            let mut d = data_sync.lock().unwrap();
            history::make_history_sync_payload(&mut d, current_clipboard)
        };
        if let Some(payload) = payload {
            if !payload.is_empty() && write.send(Message::Text(payload.into())).await.is_err() {
                return;
            }
        }

        loop {
            match rx.recv().await {
                Ok(msg) => {
                    if write.send(Message::Text(msg.into())).await.is_err() {
                        break;
                    }
                }
                // A slow client can lag behind the broadcast channel; skip the
                // missed backlog instead of killing the connection.
                Err(broadcast::error::RecvError::Lagged(_)) => continue,
                Err(broadcast::error::RecvError::Closed) => break,
            }
        }
    });

    // Receive loop for this client.
    while let Ok(Some(Ok(msg))) = tokio::time::timeout(
        CLIENT_IDLE_TIMEOUT,
        futures_util::StreamExt::next(&mut read),
    )
    .await
    {
        let Ok(text) = msg.to_text() else {
            continue;
        };
        if text.is_empty() {
            continue;
        }

        if let Ok(protocol) = serde_json::from_str::<WsProtocolMessage>(text) {
            if protocol.app.as_deref() == Some("fastpaste") && protocol.kind == "history_sync" {
                handle_history_sync(&app, &data, protocol.entries.unwrap_or_default());
                continue;
            }
        }

        // Raw plain text = immediate clipboard paste from the device.
        let _ = app.clipboard().write_text(text.to_string());
        let history_changed = {
            let mut d = data.lock().unwrap();
            let changed = history::promote_or_insert_history(&mut d, text, "ANDROID");
            if changed {
                save_state(&d);
            }
            changed
        };
        if history_changed {
            broadcast_state(&app);
            queue_cloud_sync(&app);
        }
    }

    // Client disconnected (or idle past timeout): abort the sender so both
    // stream halves drop and the socket actually closes.
    sender_task.abort();
    {
        let mut counts = client_counts.lock().unwrap();
        if let Some(count) = counts.get_mut(&ip) {
            *count -= 1;
            if *count == 0 {
                counts.remove(&ip);
            }
        }
        set_clients_from_counts(&counts, &data);
    }
    broadcast_state(&app);
}

fn handle_history_sync(app: &AppHandle, data: &Mutex<AppStateData>, entries: Vec<SyncEntry>) {
    let (newest_incoming, history_changed, latest_local_timestamp) = {
        let mut d = data.lock().unwrap();
        let result = history::merge_sync_entries(&mut d, entries);
        if result.1 {
            save_state(&d);
        }
        result
    };

    if let Some(entry) = newest_incoming {
        if entry.timestamp > latest_local_timestamp {
            let _ = app.clipboard().write_text(entry.text);
        }
    }
    if history_changed {
        broadcast_state(app);
        queue_cloud_sync(app);
    }
}

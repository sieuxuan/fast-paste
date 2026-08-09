use serde::Deserialize;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter};
use tauri_plugin_clipboard_manager::ClipboardExt;
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::broadcast;
use tokio::time::sleep;
use tokio_tungstenite::tungstenite::Message;

use crate::clipboard::{self, ClipboardPayload};
use crate::history::{self, SyncEntry};
use crate::pairing::{self, PairRequest, SessionCipher, SessionHello};
use crate::state::{
    broadcast_state, broadcast_transfers, queue_cloud_sync, save_state, AppStateData,
    TransferUiState,
};
use crate::transfer::{self, BlobChunk, BlobRequest};

const BROADCAST_INTERVAL: Duration = Duration::from_secs(2);
/// Refresh the interface list every N broadcast ticks (~30s at 2s/tick).
const IP_REFRESH_TICKS: u32 = 15;
/// Don't let a persistently failing adapter force a rebind more often than this.
const FORCED_REBIND_MIN_INTERVAL: Duration = Duration::from_secs(30);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
const CLIENT_IDLE_TIMEOUT: Duration = Duration::from_secs(60);
const SERVER_PING_INTERVAL: Duration = Duration::from_secs(15);
pub(crate) const BLOB_OFFER_THRESHOLD_BYTES: usize = 256 * 1024;

pub(crate) fn outgoing_clipboard_message(payload: &ClipboardPayload) -> String {
    if payload.kind == "text" {
        return payload.text.clone();
    }
    if payload.encoded_size() <= BLOB_OFFER_THRESHOLD_BYTES {
        return payload.protocol_json();
    }
    serde_json::json!({
        "app": "fastpaste",
        "type": "clipboard_blob_offer",
        "version": 2,
        "text": payload.text,
        "timestamp": chrono::Utc::now().timestamp_millis(),
        "source": "PC",
        "blobId": payload.fingerprint(),
        "blobSize": payload.encoded_size(),
        "payload": payload.sanitized_for_cloud(),
    })
    .to_string()
}

#[derive(Deserialize)]
struct WsProtocolMessage {
    app: Option<String>,
    #[serde(rename = "type")]
    kind: String,
    entries: Option<Vec<SyncEntry>>,
    payload: Option<ClipboardPayload>,
    cursor: Option<i64>,
}

enum DirectMessage {
    Plain(String),
    App(String),
    AppBinary(Vec<u8>),
}

fn pair_required_notice() -> String {
    serde_json::json!({
        "app": "fastpaste",
        "type": "pair_required",
        "version": 2,
        "desktopId": pairing::desktop_id(),
    })
    .to_string()
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

/// Announce `FASTPASTE:<hostname>:4567:<desktop-id>` on every physical interface, rebinding
/// when interfaces change (sleep/wake, Wi-Fi switch) or sends start failing.
pub(crate) fn spawn_udp_broadcaster(app: &AppHandle, data: Arc<Mutex<AppStateData>>) {
    let hostname = gethostname::gethostname().to_string_lossy().into_owned();
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let mut sockets: Vec<UdpSocket> = vec![];
        let mut bound_ips: Vec<String> = vec![];
        let mut ticks_since_refresh = IP_REFRESH_TICKS; // refresh on first pass
        let mut last_forced_rebind: Option<Instant> = None;
        let msg = format!("FASTPASTE:{}:4567:{}", hostname, pairing::desktop_id());
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
                && match last_forced_rebind {
                    Some(at) => at.elapsed() >= FORCED_REBIND_MIN_INTERVAL,
                    None => true,
                }
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
    ws_tx: broadcast::Sender<Arc<String>>,
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
    mut rx: broadcast::Receiver<Arc<String>>,
) {
    let Ok(Ok(ws_stream)) =
        tokio::time::timeout(HANDSHAKE_TIMEOUT, tokio_tungstenite::accept_async(stream)).await
    else {
        return;
    };

    let (mut write, mut read) = futures_util::StreamExt::split(ws_stream);
    let (direct_tx, mut direct_rx) = tokio::sync::mpsc::unbounded_channel::<DirectMessage>();
    let session = Arc::new(Mutex::new(None::<SessionCipher>));
    let mut registered = false;
    let pair_required_sent = Arc::new(AtomicBool::new(false));

    // A brand-new Android install has no session hello to send. Give paired
    // clients a brief chance to authenticate, then explain the silent socket.
    let notice_session = session.clone();
    let notice_sent = pair_required_sent.clone();
    let notice_tx = direct_tx.clone();
    let notice_app = app.clone();
    let notice_ip = ip.clone();
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(Duration::from_millis(250)).await;
        if notice_session.lock().unwrap().is_none() && !notice_sent.swap(true, Ordering::AcqRel) {
            let _ = notice_tx.send(DirectMessage::Plain(pair_required_notice()));
            let _ = notice_app.emit("pairing_required", serde_json::json!({ "ip": notice_ip }));
        }
    });

    // Không dữ liệu ứng dụng nào rời PC trước khi session v2 được xác thực.
    let data_sync = data.clone();
    let sender_session = session.clone();
    let sender_task = tauri::async_runtime::spawn(async move {
        use futures_util::SinkExt;
        // Protocol v2 is secure-by-default. No history or live clipboard data
        // leaves the PC until QR pairing/session authentication has completed.

        let mut ping_ticker = tokio::time::interval(SERVER_PING_INTERVAL);
        ping_ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

        loop {
            tokio::select! {
                _ = ping_ticker.tick() => {
                    if write.send(Message::Ping(Default::default())).await.is_err() { break; }
                }
                direct = direct_rx.recv() => {
                    let Some(direct) = direct else { break };
                    match direct {
                        DirectMessage::Plain(message) => {
                            if write.send(Message::Text(message.into())).await.is_err() { break; }
                        }
                        DirectMessage::App(message) => {
                            if let Some(message) = protect_for_client(&data_sync, &sender_session, &message) {
                                if write.send(Message::Text(message.into())).await.is_err() { break; }
                            }
                        }
                        DirectMessage::AppBinary(frame) => {
                            if let Some(frame) = protect_binary_for_client(&data_sync, &sender_session, frame) {
                                if write.send(Message::Binary(frame.into())).await.is_err() { break; }
                            }
                        }
                    }
                }
                received = rx.recv() => match received {
                    Ok(message) => {
                        if let Some(message) = protect_for_client(&data_sync, &sender_session, message.as_str()) {
                            if write.send(Message::Text(message.into())).await.is_err() { break; }
                        }
                    }
                    // A slow client can lag behind the broadcast channel; skip
                    // missed backlog instead of killing the connection.
                    Err(broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(broadcast::error::RecvError::Closed) => break,
                }
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
        if msg.is_binary() {
            let wire = msg.into_data();
            let secure_frame = {
                let mut guard = session.lock().unwrap();
                guard
                    .as_mut()
                    .and_then(|cipher| cipher.decrypt_binary(&wire).ok().flatten())
            };
            if let Some(frame) = secure_frame {
                if let Ok(outcome) = transfer::receive_binary_chunk(&frame) {
                    let complete = outcome.payload.is_some();
                    let transfer_id = serde_json::from_str::<serde_json::Value>(
                        outcome.control.as_deref().unwrap_or("{}"),
                    )
                    .ok()
                    .and_then(|value| value["transferId"].as_str().map(str::to_string))
                    .unwrap_or_else(|| "binary-download".to_string());
                    let received = outcome
                        .payload
                        .as_ref()
                        .and_then(|payload| transfer::serialized_size(payload).ok())
                        .or_else(|| {
                            data.lock()
                                .unwrap()
                                .transfers
                                .iter()
                                .find(|item| item.transfer_id == transfer_id)
                                .map(|item| item.sent_bytes)
                        })
                        .unwrap_or(0);
                    update_transfer(
                        &data,
                        TransferUiState {
                            transfer_id,
                            label: "Ảnh".into(),
                            sent_bytes: received,
                            total_bytes: received,
                            direction: "download".into(),
                            status: if complete {
                                "Hoàn tất".into()
                            } else {
                                "Đang tải".into()
                            },
                        },
                    );
                    if let Some(control) = outcome.control {
                        let _ = direct_tx.send(DirectMessage::App(control));
                    }
                    let mut history_changed = false;
                    if let Some(payload) = outcome.payload {
                        let _ = apply_clipboard(payload.clone()).await;
                        let mut d = data.lock().unwrap();
                        if history::promote_or_insert_payload(&mut d, &payload, "ANDROID") {
                            save_state();
                            history_changed = true;
                        }
                    }
                    broadcast_transfers(&app);
                    if history_changed {
                        broadcast_state(&app);
                        queue_cloud_sync(&app);
                    }
                }
            }
            continue;
        }
        let Ok(wire_text) = msg.to_text() else {
            continue;
        };
        if wire_text.is_empty() {
            continue;
        }

        // Pairing/session control messages are authenticated but intentionally
        // plaintext: the QR secret authenticates pairing, then the stored root
        // key authenticates fresh P-256 ECDH for every reconnect.
        if let Ok(value) = serde_json::from_str::<serde_json::Value>(wire_text) {
            let kind = value
                .get("type")
                .and_then(|item| item.as_str())
                .unwrap_or_default();
            if value.get("app").and_then(|item| item.as_str()) == Some("fastpaste")
                && kind == "pair_request"
            {
                if let Ok(request) = serde_json::from_value::<PairRequest>(value) {
                    if let Ok(response) = pairing::accept_pair(request) {
                        if let Ok(json) = serde_json::to_string(&response) {
                            let _ = direct_tx.send(DirectMessage::Plain(json));
                        }
                    }
                }
                continue;
            }
            if value.get("app").and_then(|item| item.as_str()) == Some("fastpaste")
                && kind == "session_hello"
            {
                if let Ok(hello) = serde_json::from_value::<SessionHello>(value) {
                    if let Ok((response, cipher)) = pairing::accept_session(hello) {
                        if let Ok(json) = serde_json::to_string(&response) {
                            let _ = direct_tx.send(DirectMessage::Plain(json));
                            let sync_cursor = cipher.sync_cursor;
                            *session.lock().unwrap() = Some(cipher);
                            if !registered {
                                let mut counts = client_counts.lock().unwrap();
                                *counts.entry(ip.clone()).or_insert(0) += 1;
                                set_clients_from_counts(&counts, &data);
                                registered = true;
                                drop(counts);
                                broadcast_state(&app);
                            }
                            let current_clipboard = crate::watcher::latest_payload();
                            let history_payload = {
                                let mut d = data.lock().unwrap();
                                history::make_history_delta_payload(
                                    &mut d,
                                    current_clipboard,
                                    sync_cursor,
                                )
                            };
                            if let Some(payload) = history_payload {
                                let _ = direct_tx.send(DirectMessage::App(payload));
                            }
                        }
                    }
                }
                continue;
            }
        }

        let secure_text = {
            let mut guard = session.lock().unwrap();
            match guard.as_mut() {
                Some(cipher) => cipher.decrypt(wire_text),
                None => Ok(None),
            }
        };
        let secure_text = match secure_text {
            Ok(value) => value,
            Err(_) => continue,
        };
        if session.lock().unwrap().is_some() && secure_text.is_none() {
            // Authenticated connections never downgrade to plaintext.
            continue;
        }
        if session.lock().unwrap().is_none() {
            if !pair_required_sent.swap(true, Ordering::AcqRel) {
                let _ = direct_tx.send(DirectMessage::Plain(pair_required_notice()));
                let _ = app.emit("pairing_required", serde_json::json!({ "ip": ip.clone() }));
            }
            continue;
        }

        let Some(text) = secure_text else { continue };

        if let Ok(value) = serde_json::from_str::<serde_json::Value>(&text) {
            if value.get("app").and_then(|item| item.as_str()) == Some("fastpaste") {
                let kind = value
                    .get("type")
                    .and_then(|item| item.as_str())
                    .unwrap_or_default();
                if matches!(kind, "blob_request" | "blob_ack") {
                    if let Ok(request) = serde_json::from_value::<BlobRequest>(value) {
                        let payload = {
                            let d = data.lock().unwrap();
                            d.history
                                .iter()
                                .find(|item| item.blob_id == request.blob_id && item.blob_ready)
                                .and_then(|item| item.payload.clone())
                        }
                        .or_else(|| {
                            crate::watcher::latest_payload()
                                .filter(|payload| payload.fingerprint() == request.blob_id)
                        });
                        if let Some(payload) = payload {
                            if transfer::serialized_size(&payload).ok() == Some(request.next_offset)
                            {
                                let complete = serde_json::json!({
                                    "app": "fastpaste",
                                    "type": "blob_complete",
                                    "version": 2,
                                    "transferId": request.transfer_id,
                                    "blobId": request.blob_id,
                                })
                                .to_string();
                                let _ = direct_tx.send(DirectMessage::App(complete));
                                continue;
                            }
                            if let Ok(frames) = transfer::make_binary_chunks(&request, &payload) {
                                let total = transfer::serialized_size(&payload).unwrap_or(0);
                                let sent = (request.next_offset
                                    + frames.len()
                                        * request.chunk_size.max(transfer::DEFAULT_CHUNK_BYTES))
                                .min(total);
                                update_transfer(
                                    &data,
                                    TransferUiState {
                                        transfer_id: request.transfer_id.clone(),
                                        label: payload.text.clone(),
                                        sent_bytes: sent,
                                        total_bytes: total,
                                        direction: "upload".into(),
                                        status: if sent >= total {
                                            "Chờ ACK".into()
                                        } else {
                                            "Đang gửi".into()
                                        },
                                    },
                                );
                                broadcast_transfers(&app);
                                for frame in frames {
                                    let _ = direct_tx.send(DirectMessage::AppBinary(frame));
                                }
                            }
                        }
                    }
                    continue;
                }
                if kind == "blob_complete" {
                    let transfer_id = value
                        .get("transferId")
                        .and_then(|item| item.as_str())
                        .unwrap_or_default();
                    let mut state = data.lock().unwrap();
                    if let Some(progress) = state
                        .transfers
                        .iter_mut()
                        .find(|item| item.transfer_id == transfer_id)
                    {
                        progress.sent_bytes = progress.total_bytes;
                        progress.status = "Hoàn tất".into();
                    }
                    drop(state);
                    broadcast_transfers(&app);
                    continue;
                }
                if kind == "blob_chunk" {
                    if let Ok(chunk) = serde_json::from_value::<BlobChunk>(value) {
                        let transfer_id = chunk.transfer_id.clone();
                        let total = chunk.total;
                        let received = chunk.offset + chunk.data.len() * 3 / 4;
                        if let Ok(outcome) = transfer::receive_chunk(chunk) {
                            let complete = outcome.payload.is_some();
                            update_transfer(
                                &data,
                                TransferUiState {
                                    transfer_id,
                                    label: "Ảnh".into(),
                                    sent_bytes: received.min(total),
                                    total_bytes: total,
                                    direction: "download".into(),
                                    status: if complete {
                                        "Hoàn tất".into()
                                    } else {
                                        "Đang tải".into()
                                    },
                                },
                            );
                            if let Some(control) = outcome.control {
                                let _ = direct_tx.send(DirectMessage::App(control));
                            }
                            if let Some(payload) = outcome.payload {
                                let _ = apply_clipboard(payload.clone()).await;
                                let changed = {
                                    let mut d = data.lock().unwrap();
                                    let changed = history::promote_or_insert_payload(
                                        &mut d, &payload, "ANDROID",
                                    );
                                    if changed {
                                        save_state();
                                    }
                                    changed
                                };
                                if changed {
                                    broadcast_state(&app);
                                    queue_cloud_sync(&app);
                                }
                            }
                            broadcast_transfers(&app);
                        }
                    }
                    continue;
                }
                if kind == "clipboard_blob_offer" {
                    let payload = value.get("payload").cloned().and_then(|payload| {
                        serde_json::from_value::<ClipboardPayload>(payload).ok()
                    });
                    let blob_id = value
                        .get("blobId")
                        .and_then(|item| item.as_str())
                        .unwrap_or_default()
                        .to_string();
                    let text_value = value
                        .get("text")
                        .and_then(|item| item.as_str())
                        .unwrap_or_default()
                        .to_string();
                    if !blob_id.is_empty() && !text_value.is_empty() {
                        let entry = SyncEntry {
                            text: text_value,
                            timestamp: value
                                .get("timestamp")
                                .and_then(|item| item.as_i64())
                                .unwrap_or_else(|| chrono::Utc::now().timestamp_millis()),
                            source: "ANDROID".into(),
                            source_app: String::new(),
                            source_title: String::new(),
                            source_icon: String::new(),
                            pinned: false,
                            folder: String::new(),
                            payload,
                            blob_id: blob_id.clone(),
                            blob_size: value
                                .get("blobSize")
                                .and_then(|item| item.as_u64())
                                .unwrap_or(0) as usize,
                            blob_ready: false,
                        };
                        handle_history_sync(&app, &data, vec![entry]).await;
                        let request = transfer::make_request(&blob_id);
                        let transfer_id = serde_json::from_str::<serde_json::Value>(&request)
                            .ok()
                            .and_then(|item| {
                                item.get("transferId")
                                    .and_then(|value| value.as_str())
                                    .map(str::to_string)
                            })
                            .unwrap_or_default();
                        let _ = direct_tx.send(DirectMessage::App(request));
                        let retry_tx = direct_tx.clone();
                        tauri::async_runtime::spawn(async move {
                            let mut previous_offset =
                                transfer::current_offset(&blob_id).unwrap_or(0);
                            let mut retries = 0;
                            loop {
                                tokio::time::sleep(Duration::from_millis(2_500)).await;
                                let Some(offset) = transfer::current_offset(&blob_id) else {
                                    break;
                                };
                                if offset == previous_offset {
                                    retries += 1;
                                    if retries > 5 {
                                        break;
                                    }
                                } else {
                                    previous_offset = offset;
                                    retries = 0;
                                }
                                let resume =
                                    transfer::make_resume_request(&blob_id, &transfer_id, offset);
                                if retry_tx.send(DirectMessage::App(resume)).is_err() {
                                    break;
                                }
                            }
                        });
                    }
                    continue;
                }
            }
        }

        if let Ok(protocol) = serde_json::from_str::<WsProtocolMessage>(&text) {
            if protocol.app.as_deref() == Some("fastpaste")
                && matches!(protocol.kind.as_str(), "history_sync" | "history_delta")
            {
                if protocol.kind == "history_delta" {
                    if let (Some(cursor), Some(device_id)) = (
                        protocol.cursor,
                        session
                            .lock()
                            .unwrap()
                            .as_ref()
                            .map(|cipher| cipher.device_id.clone()),
                    ) {
                        pairing::update_sync_cursor(&device_id, cursor);
                    }
                }
                handle_history_sync(&app, &data, protocol.entries.unwrap_or_default()).await;
                continue;
            }
            if protocol.app.as_deref() == Some("fastpaste") && protocol.kind == "clipboard_payload"
            {
                if let Some(payload) = protocol.payload {
                    let _ = apply_clipboard(payload.clone()).await;
                    let history_changed = {
                        let mut d = data.lock().unwrap();
                        let changed =
                            history::promote_or_insert_payload(&mut d, &payload, "ANDROID");
                        if changed {
                            save_state();
                        }
                        changed
                    };
                    if history_changed {
                        broadcast_state(&app);
                        queue_cloud_sync(&app);
                    }
                    continue;
                }
            }
            // Never reinterpret FastPaste control JSON as clipboard text.
            if protocol.app.as_deref() == Some("fastpaste") {
                continue;
            }
        }

        if serde_json::from_str::<serde_json::Value>(&text)
            .ok()
            .and_then(|value| {
                value
                    .get("app")
                    .and_then(|item| item.as_str())
                    .map(str::to_owned)
            })
            .as_deref()
            == Some("fastpaste")
        {
            // Malformed or future control packets are ignored, never copied.
            continue;
        }

        // Raw plain text = immediate clipboard paste from the device.
        let _ = apply_clipboard_text(app.clone(), text.clone()).await;
        let history_changed = {
            let mut d = data.lock().unwrap();
            let changed = history::promote_or_insert_history(&mut d, &text, "ANDROID");
            if changed {
                save_state();
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
    if registered {
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

fn update_transfer(data: &Mutex<AppStateData>, progress: TransferUiState) {
    let mut state = data.lock().unwrap();
    state
        .transfers
        .retain(|item| item.transfer_id != progress.transfer_id);
    state.transfers.push(progress);
    if state.transfers.len() > 4 {
        let drain = state.transfers.len() - 4;
        state.transfers.drain(0..drain);
    }
}

/// Win32 clipboard và decode ảnh đều blocking; chạy ngoài worker WS.
async fn apply_clipboard(payload: ClipboardPayload) -> Result<(), String> {
    tokio::task::spawn_blocking(move || clipboard::write_clipboard(&payload))
        .await
        .map_err(|error| format!("Clipboard worker lỗi: {error}"))?
}

async fn apply_clipboard_text(app: AppHandle, text: String) -> Result<(), String> {
    tokio::task::spawn_blocking(move || {
        let result = app
            .clipboard()
            .write_text(text)
            .map_err(|error| error.to_string());
        if result.is_ok() {
            clipboard::mark_self_write();
        }
        result
    })
    .await
    .map_err(|error| format!("Clipboard worker lỗi: {error}"))?
}

fn protect_for_client(
    _data: &Mutex<AppStateData>,
    session: &Mutex<Option<SessionCipher>>,
    message: &str,
) -> Option<String> {
    if let Some(cipher) = session.lock().unwrap().as_mut() {
        cipher.encrypt(message).ok()
    } else {
        None
    }
}

fn protect_binary_for_client(
    _data: &Mutex<AppStateData>,
    session: &Mutex<Option<SessionCipher>>,
    frame: Vec<u8>,
) -> Option<Vec<u8>> {
    session
        .lock()
        .unwrap()
        .as_mut()
        .and_then(|cipher| cipher.encrypt_binary(&frame).ok())
}

async fn handle_history_sync(app: &AppHandle, data: &Mutex<AppStateData>, entries: Vec<SyncEntry>) {
    let (newest_incoming, history_changed, latest_local_timestamp) = {
        let mut d = data.lock().unwrap();
        let result = history::merge_sync_entries(&mut d, entries);
        if result.1 {
            save_state();
        }
        result
    };

    if let Some(entry) = newest_incoming {
        if entry.timestamp > latest_local_timestamp {
            if let Some(payload) = entry.payload {
                let _ = apply_clipboard(payload).await;
            } else {
                let _ = apply_clipboard_text(app.clone(), entry.text).await;
            }
        }
    }
    if history_changed {
        broadcast_state(app);
        queue_cloud_sync(app);
    }
}

#[cfg(test)]
mod keepalive_tests {
    use super::*;

    #[test]
    fn server_pings_well_before_idle_timeout() {
        assert!(SERVER_PING_INTERVAL * 2 < CLIENT_IDLE_TIMEOUT);
    }

    #[tokio::test]
    async fn blocking_clipboard_errors_are_returned() {
        let payload = ClipboardPayload {
            kind: "image".into(),
            text: "invalid image".into(),
            data: "not-base64".into(),
            ..ClipboardPayload::default()
        };

        let error = apply_clipboard(payload).await.unwrap_err();
        assert!(error.contains("Dữ liệu ảnh lỗi"));
    }
}

#[cfg(test)]
mod pairing_notice_tests {
    use super::*;

    #[test]
    fn pair_required_notice_contains_no_clipboard_data() {
        let value: serde_json::Value = serde_json::from_str(&pair_required_notice()).unwrap();
        assert_eq!(value["type"], "pair_required");
        assert_eq!(value["version"], 2);
        assert!(value.get("payload").is_none());
        assert!(value["desktopId"].as_str().is_some_and(|id| !id.is_empty()));
    }
}

#[cfg(test)]
mod outgoing_tests {
    use super::*;

    #[test]
    fn text_goes_out_raw() {
        assert_eq!(
            outgoing_clipboard_message(&ClipboardPayload::text("hello".into())),
            "hello"
        );
    }

    #[test]
    fn large_image_is_offered_without_inline_data() {
        let payload = ClipboardPayload {
            kind: "image".into(),
            text: "image".into(),
            data: "A".repeat((BLOB_OFFER_THRESHOLD_BYTES + 1) * 2),
            ..Default::default()
        };
        let value: serde_json::Value =
            serde_json::from_str(&outgoing_clipboard_message(&payload)).unwrap();
        assert_eq!(value["type"], "clipboard_blob_offer");
        assert_eq!(value["blobId"], payload.fingerprint());
        assert_eq!(value["payload"]["data"], "");
    }
}

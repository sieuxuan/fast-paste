use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter, Manager};

use crate::cloud;
use crate::crypto;
use crate::history::{
    hydrate_running_app_icons, normalize_deleted_markers, DeletedMarker, HistoryBackup, HistoryItem,
};
use crate::hotkeys::{
    default_edit_hotkey, default_hotkey, default_pinned_hotkey, default_quick_slot_hotkey,
    normalize_settings,
};
use crate::vault;

const VAULT_VERSION: u8 = 1;
static VAULT_WRITABLE: AtomicBool = AtomicBool::new(true);
const STATE_FLUSH_INTERVAL_MS: u64 = 500;
static DIRTY: AtomicBool = AtomicBool::new(false);
static STATE_READY: AtomicBool = AtomicBool::new(false);
const BROADCAST_COALESCE_MS: u64 = 100;
static LAST_BROADCAST_AT: AtomicI64 = AtomicI64::new(0);
static TRAILING_BROADCAST_PENDING: AtomicBool = AtomicBool::new(false);

#[derive(Serialize, Deserialize)]
struct SensitiveState {
    version: u8,
    history: Vec<HistoryItem>,
    deleted_markers: Vec<DeletedMarker>,
    clear_history_at: Option<i64>,
    app_icons: std::collections::HashMap<String, String>,
    history_backup: Option<HistoryBackup>,
}

impl SensitiveState {
    fn from_state(data: &AppStateData) -> Self {
        Self {
            version: VAULT_VERSION,
            history: data.history.clone(),
            deleted_markers: data.deleted_markers.clone(),
            clear_history_at: data.clear_history_at,
            app_icons: data.app_icons.clone(),
            history_backup: data.history_backup.clone(),
        }
    }

    fn hydrate(self, data: &mut AppStateData) -> Result<(), String> {
        if self.version != VAULT_VERSION {
            return Err(format!(
                "Phiên bản history vault {} chưa được hỗ trợ.",
                self.version
            ));
        }
        data.history = self.history;
        data.deleted_markers = self.deleted_markers;
        data.clear_history_at = self.clear_history_at;
        data.app_icons = self.app_icons;
        data.history_backup = self.history_backup;
        Ok(())
    }
}

pub(crate) fn default_always_on_top() -> bool {
    true
}

pub(crate) fn default_settings() -> AppSettings {
    AppSettings {
        hotkey: default_hotkey(),
        edit_hotkey: default_edit_hotkey(),
        pinned_hotkey: default_pinned_hotkey(),
        quick_slot_hotkey: default_quick_slot_hotkey(),
        always_on_top: default_always_on_top(),
        auto_start: false,
        excluded_apps: vec![],
        e2ee_enabled: false,
        e2ee_key_id: String::new(),
    }
}

#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct AppSettings {
    #[serde(default = "default_hotkey")]
    pub(crate) hotkey: String,
    #[serde(default = "default_edit_hotkey")]
    pub(crate) edit_hotkey: String,
    #[serde(default = "default_pinned_hotkey")]
    pub(crate) pinned_hotkey: String,
    #[serde(default = "default_quick_slot_hotkey")]
    pub(crate) quick_slot_hotkey: String,
    #[serde(default = "default_always_on_top")]
    pub(crate) always_on_top: bool,
    #[serde(default)]
    pub(crate) auto_start: bool,
    #[serde(default, rename = "excludedApps", alias = "excluded_apps")]
    pub(crate) excluded_apps: Vec<String>,
    #[serde(default, rename = "e2eeEnabled", alias = "e2ee_enabled")]
    pub(crate) e2ee_enabled: bool,
    #[serde(default, rename = "e2eeKeyId", alias = "e2ee_key_id")]
    pub(crate) e2ee_key_id: String,
}

#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct AppStateData {
    pub(crate) settings: AppSettings,
    pub(crate) history: Vec<HistoryItem>,
    pub(crate) ips: Vec<String>,
    pub(crate) clients: Vec<String>,
    #[serde(default)]
    pub(crate) deleted_markers: Vec<DeletedMarker>,
    #[serde(default)]
    pub(crate) clear_history_at: Option<i64>,
    #[serde(default, rename = "appIcons", alias = "app_icons")]
    pub(crate) app_icons: std::collections::HashMap<String, String>,
    #[serde(default, rename = "historyBackup", alias = "history_backup")]
    pub(crate) history_backup: Option<HistoryBackup>,
    #[serde(default)]
    pub(crate) cloud: cloud::CloudUiState,
    #[serde(default)]
    pub(crate) transfers: Vec<TransferUiState>,
}

#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct TransferUiState {
    #[serde(rename = "transferId")]
    pub(crate) transfer_id: String,
    pub(crate) label: String,
    #[serde(rename = "sentBytes")]
    pub(crate) sent_bytes: usize,
    #[serde(rename = "totalBytes")]
    pub(crate) total_bytes: usize,
    pub(crate) direction: String,
    pub(crate) status: String,
}

pub(crate) struct AppState(pub(crate) Arc<Mutex<AppStateData>>);

pub(crate) fn empty_state() -> AppStateData {
    AppStateData {
        settings: default_settings(),
        history: vec![],
        ips: vec![],
        clients: vec![],
        deleted_markers: vec![],
        clear_history_at: None,
        app_icons: std::collections::HashMap::new(),
        history_backup: None,
        cloud: cloud::CloudUiState::default(),
        transfers: vec![],
    }
}

pub(crate) fn get_settings_path() -> std::path::PathBuf {
    std::env::current_exe()
        .map(|p| p.parent().unwrap().join("settings.json"))
        .unwrap_or_else(|_| std::path::PathBuf::from("settings.json"))
}

fn get_history_vault_path() -> std::path::PathBuf {
    get_settings_path().with_file_name("history.vault")
}

/// Đánh dấu state cần ghi. Writer thread thực hiện toàn bộ I/O blocking.
pub(crate) fn save_state() {
    DIRTY.store(true, Ordering::Release);
}

fn take_dirty() -> bool {
    DIRTY.swap(false, Ordering::AcqRel)
}

pub(crate) fn spawn_state_writer(data: Arc<Mutex<AppStateData>>) {
    STATE_READY.store(true, Ordering::Release);
    std::thread::Builder::new()
        .name("fastpaste-state-writer".into())
        .spawn(move || loop {
            std::thread::sleep(std::time::Duration::from_millis(STATE_FLUSH_INTERVAL_MS));
            if !take_dirty() {
                continue;
            }
            let snapshot = data.lock().unwrap().clone();
            flush_state_now(&snapshot);
        })
        .expect("không tạo được state writer thread");
}

pub(crate) fn flush_on_exit(data: &Mutex<AppStateData>) {
    if !STATE_READY.load(Ordering::Acquire) {
        return;
    }
    if take_dirty() {
        let snapshot = data.lock().unwrap().clone();
        flush_state_now(&snapshot);
    }
}

pub(crate) fn flush_state_now(data: &AppStateData) {
    if !VAULT_WRITABLE.load(Ordering::Acquire) {
        eprintln!("FastPaste: history vault đang bị khoá vì lần giải mã trước thất bại; không ghi đè dữ liệu.");
        return;
    }

    let mut persisted = data.clone();
    crate::history::trim_history(&mut persisted.history);
    crate::history::trim_inline_payloads(&mut persisted.history);
    persisted.clients.clear();
    persisted.ips.clear();
    persisted.cloud.syncing = false;
    persisted.transfers.clear();
    let sensitive = SensitiveState::from_state(&persisted);
    persisted.history.clear();
    persisted.deleted_markers.clear();
    persisted.clear_history_at = None;
    persisted.app_icons.clear();
    persisted.history_backup = None;

    let result = (|| -> Result<(), String> {
        let vault_json = serde_json::to_vec(&sensitive).map_err(|error| error.to_string())?;
        // Commit the encrypted copy first. If this fails, settings.json is left
        // untouched so a legacy plaintext installation can still recover.
        vault::write_protected_atomic(&get_history_vault_path(), &vault_json)?;
        let public_json = serde_json::to_vec(&persisted).map_err(|error| error.to_string())?;
        vault::write_atomic(&get_settings_path(), &public_json)
    })();
    if let Err(error) = result {
        eprintln!("FastPaste: không lưu được state an toàn: {error}");
    }
}

pub(crate) fn load_state() -> AppStateData {
    if let Ok(json) = vault::read_with_backup(&get_settings_path()) {
        if let Ok(mut data) = serde_json::from_slice::<AppStateData>(&json) {
            let vault_path = get_history_vault_path();
            let vault_present = vault_path.exists() || vault_path.with_extension("bak").exists();
            let migrated_from_plaintext = !vault_present;
            if vault_present {
                let loaded = vault::read_protected(&vault_path)
                    .and_then(|plain| {
                        serde_json::from_slice::<SensitiveState>(&plain)
                            .map_err(|error| error.to_string())
                    })
                    .and_then(|sensitive| sensitive.hydrate(&mut data));
                if let Err(error) = loaded {
                    // Never overwrite a vault that DPAPI cannot open (different
                    // Windows account, damaged file, or restored disk image).
                    VAULT_WRITABLE.store(false, Ordering::Release);
                    data.cloud.status = format!("History vault cần khôi phục: {error}");
                    eprintln!("FastPaste: {error}");
                }
            }
            data.clients.clear();
            data.ips.clear();
            data.cloud.syncing = false;
            data.transfers.clear();
            let mut settings_changed = normalize_settings(&mut data.settings);
            if data.settings.e2ee_enabled {
                match crypto::load_key() {
                    Ok(key) => {
                        let key_id = crypto::key_id(&key);
                        if data.settings.e2ee_key_id != key_id {
                            data.settings.e2ee_key_id = key_id;
                            settings_changed = true;
                        }
                    }
                    Err(_) => {
                        data.settings.e2ee_enabled = false;
                        data.settings.e2ee_key_id.clear();
                        settings_changed = true;
                    }
                }
            }
            normalize_deleted_markers(&mut data);
            let files_migrated = crate::history::drop_file_payloads(&mut data);
            refresh_cloud_state(&mut data.cloud);
            if VAULT_WRITABLE.load(Ordering::Acquire)
                && (migrated_from_plaintext || settings_changed || files_migrated)
            {
                flush_state_now(&data);
            }
            return data;
        }
    }
    let mut data = empty_state();
    let vault_path = get_history_vault_path();
    if vault_path.exists() || vault_path.with_extension("bak").exists() {
        let loaded = vault::read_protected(&vault_path)
            .and_then(|plain| {
                serde_json::from_slice::<SensitiveState>(&plain).map_err(|error| error.to_string())
            })
            .and_then(|sensitive| sensitive.hydrate(&mut data));
        if let Err(error) = loaded {
            VAULT_WRITABLE.store(false, Ordering::Release);
            data.cloud.status = format!("History vault cần khôi phục: {error}");
        }
    }
    crate::history::drop_file_payloads(&mut data);
    if VAULT_WRITABLE.load(Ordering::Acquire) {
        flush_state_now(&data);
    }
    data
}

/// Enumerate process/icon là công việc đắt; chạy sau khi cửa sổ và state thật
/// đã xuất hiện thay vì chặn lần hiển thị đầu tiên.
pub(crate) fn hydrate_icons_later(data: &Mutex<AppStateData>) -> bool {
    let mut guard = data.lock().unwrap();
    let changed = hydrate_running_app_icons(&mut guard);
    if changed {
        save_state();
    }
    changed
}

pub(crate) fn refresh_cloud_state(cloud_state: &mut cloud::CloudUiState) {
    cloud_state.configured = cloud::is_configured();
    cloud_state.signed_in = cloud::is_signed_in();
    cloud_state.account_email = cloud::signed_in_email();

    if !cloud_state.configured {
        cloud_state.status = "Chưa bật đồng bộ Google trong bản build này.".to_string();
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

fn should_emit(last_at: i64, now: i64) -> bool {
    now - last_at >= BROADCAST_COALESCE_MS as i64
}

pub(crate) fn broadcast_state(app: &AppHandle) {
    let now = chrono::Utc::now().timestamp_millis();
    let last_at = LAST_BROADCAST_AT.load(Ordering::Acquire);
    if should_emit(last_at, now) {
        broadcast_state_now(app);
        return;
    }

    if !TRAILING_BROADCAST_PENDING.swap(true, Ordering::AcqRel) {
        let app = app.clone();
        tauri::async_runtime::spawn(async move {
            tokio::time::sleep(std::time::Duration::from_millis(BROADCAST_COALESCE_MS)).await;
            TRAILING_BROADCAST_PENDING.store(false, Ordering::Release);
            broadcast_state_now(&app);
        });
    }
}

pub(crate) fn broadcast_state_now(app: &AppHandle) {
    let state = app.state::<AppState>();
    let mut data = state.0.lock().unwrap().clone();
    for item in &mut data.history {
        if let Some(payload) = &item.payload {
            item.payload = Some(payload.sanitized_for_ui());
        }
    }
    if let Some(backup) = &mut data.history_backup {
        for item in &mut backup.items {
            if let Some(payload) = &item.payload {
                item.payload = Some(payload.sanitized_for_ui());
            }
        }
    }
    LAST_BROADCAST_AT.store(chrono::Utc::now().timestamp_millis(), Ordering::Release);
    let _ = app.emit("update_state", data);
}

pub(crate) fn broadcast_transfers(app: &AppHandle) {
    let state = app.state::<AppState>();
    let transfers = state.0.lock().unwrap().transfers.clone();
    let _ = app.emit("update_transfers", transfers);
}

#[cfg(test)]
mod persistence_tests {
    use super::*;

    #[test]
    fn dirty_marker_is_consumed_once() {
        DIRTY.store(false, Ordering::Release);
        save_state();
        assert!(take_dirty());
        assert!(!take_dirty());
    }
}

#[cfg(test)]
mod coalesce_tests {
    use super::*;

    #[test]
    fn first_emit_always_passes() {
        assert!(should_emit(0, 1_000));
    }

    #[test]
    fn emits_inside_the_window_are_dropped() {
        assert!(!should_emit(1_000, 1_050));
    }

    #[test]
    fn emits_after_the_window_pass() {
        assert!(should_emit(1_000, 1_000 + BROADCAST_COALESCE_MS as i64));
    }
}

pub(crate) fn queue_cloud_sync(app: &AppHandle) {
    if let Some(tx) = app.try_state::<tokio::sync::mpsc::UnboundedSender<()>>() {
        let _ = tx.send(());
    }
}

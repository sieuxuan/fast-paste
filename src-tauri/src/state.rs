use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter, Manager};

use crate::cloud;
use crate::history::{normalize_deleted_markers, DeletedMarker, HistoryItem};
use crate::hotkeys::{
    default_edit_hotkey, default_hotkey, default_pinned_hotkey, default_quick_slot_hotkey,
    normalize_settings,
};

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
    #[serde(default)]
    pub(crate) cloud: cloud::CloudUiState,
}

pub(crate) struct AppState(pub(crate) Arc<Mutex<AppStateData>>);

pub(crate) fn get_settings_path() -> std::path::PathBuf {
    std::env::current_exe()
        .map(|p| p.parent().unwrap().join("settings.json"))
        .unwrap_or_else(|_| std::path::PathBuf::from("settings.json"))
}

pub(crate) fn save_state(data: &AppStateData) {
    let mut persisted = data.clone();
    persisted.clients.clear();
    persisted.ips.clear();
    persisted.cloud.syncing = false;

    if let Ok(json) = serde_json::to_string(&persisted) {
        let _ = std::fs::write(get_settings_path(), json);
    }
}

pub(crate) fn load_state() -> AppStateData {
    if let Ok(json) = std::fs::read_to_string(get_settings_path()) {
        if let Ok(mut data) = serde_json::from_str::<AppStateData>(&json) {
            data.clients.clear();
            data.ips.clear();
            data.cloud.syncing = false;
            let settings_changed = normalize_settings(&mut data.settings);
            normalize_deleted_markers(&mut data);
            refresh_cloud_state(&mut data.cloud);
            if settings_changed {
                save_state(&data);
            }
            return data;
        }
    }
    let data = AppStateData {
        settings: default_settings(),
        history: vec![],
        ips: vec![],
        clients: vec![],
        deleted_markers: vec![],
        clear_history_at: None,
        cloud: cloud::CloudUiState::default(),
    };
    save_state(&data);
    data
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

pub(crate) fn broadcast_state(app: &AppHandle) {
    let state = app.state::<AppState>();
    let data = state.0.lock().unwrap().clone();
    let _ = app.emit("update_state", data);
}

pub(crate) fn queue_cloud_sync(app: &AppHandle) {
    if let Some(tx) = app.try_state::<tokio::sync::mpsc::UnboundedSender<()>>() {
        let _ = tx.send(());
    }
}

use std::time::Duration;
use tauri::{AppHandle, Manager, State};
use tauri_plugin_clipboard_manager::ClipboardExt;
use tauri_plugin_global_shortcut::{GlobalShortcutExt, Shortcut, ShortcutState};

use crate::state::{broadcast_state, queue_cloud_sync, save_state, AppSettings, AppState};

pub(crate) fn default_hotkey() -> String {
    "CommandOrControl+Alt+Z".to_string()
}

pub(crate) fn default_edit_hotkey() -> String {
    "CommandOrControl+Alt+E".to_string()
}

pub(crate) fn default_pinned_hotkey() -> String {
    "CommandOrControl+Alt+P".to_string()
}

pub(crate) fn default_quick_slot_hotkey() -> String {
    "CommandOrControl+Alt".to_string()
}

fn normalize_hotkey_setting(value: &mut String, default_value: String) -> bool {
    let needs_default = value.trim().is_empty() || value.parse::<Shortcut>().is_err();
    if needs_default {
        *value = default_value;
        true
    } else {
        false
    }
}

pub(crate) fn normalize_settings(settings: &mut AppSettings) -> bool {
    let mut changed = false;
    changed |= normalize_hotkey_setting(&mut settings.hotkey, default_hotkey());
    changed |= normalize_hotkey_setting(&mut settings.edit_hotkey, default_edit_hotkey());
    changed |= normalize_hotkey_setting(&mut settings.pinned_hotkey, default_pinned_hotkey());

    if validate_quick_slot_prefix(&settings.quick_slot_hotkey).is_err() {
        settings.quick_slot_hotkey = default_quick_slot_hotkey();
        changed = true;
    }
    if normalize_hotkey_text(&settings.quick_slot_hotkey) == "alt" {
        settings.quick_slot_hotkey = default_quick_slot_hotkey();
        changed = true;
    }

    if is_quick_slot_hotkey(&settings.hotkey, &settings.quick_slot_hotkey) {
        settings.hotkey = default_hotkey();
        changed = true;
    }
    if settings.edit_hotkey == settings.hotkey
        || is_quick_slot_hotkey(&settings.edit_hotkey, &settings.quick_slot_hotkey)
    {
        settings.edit_hotkey = default_edit_hotkey();
        changed = true;
    }
    if settings.pinned_hotkey == settings.hotkey
        || settings.pinned_hotkey == settings.edit_hotkey
        || is_quick_slot_hotkey(&settings.pinned_hotkey, &settings.quick_slot_hotkey)
    {
        settings.pinned_hotkey = default_pinned_hotkey();
        changed = true;
    }

    changed
}

pub(crate) fn normalize_hotkey_text(hotkey: &str) -> String {
    hotkey
        .split('+')
        .map(|part| part.trim().to_ascii_lowercase())
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>()
        .join("+")
}

pub(crate) fn quick_slot_hotkey(prefix: &str, slot: usize) -> String {
    let prefix = prefix.trim().trim_end_matches('+').trim();
    if prefix.is_empty() {
        slot.to_string()
    } else {
        format!("{prefix}+{slot}")
    }
}

pub(crate) fn is_quick_slot_hotkey(hotkey: &str, prefix: &str) -> bool {
    let normalized = normalize_hotkey_text(hotkey);
    (1..=9).any(|slot| normalized == normalize_hotkey_text(&quick_slot_hotkey(prefix, slot)))
}

pub(crate) fn validate_quick_slot_prefix(prefix: &str) -> Result<String, String> {
    let prefix = prefix.trim().trim_end_matches('+').trim().to_string();
    if prefix.is_empty() {
        return Err("Phím dán nhanh không được để trống. Ví dụ: CommandOrControl+Alt.".to_string());
    }

    let has_slot_number = prefix.split('+').any(|part| {
        let part = part.trim();
        part.len() == 1
            && part
                .chars()
                .next()
                .is_some_and(|ch| ('1'..='9').contains(&ch))
    });
    if has_slot_number {
        return Err("Chỉ nhập phần phím trước số. Ví dụ: CommandOrControl+Alt.".to_string());
    }

    quick_slot_hotkey(&prefix, 1)
        .parse::<Shortcut>()
        .map_err(|_| "Phím dán nhanh không hợp lệ. Ví dụ: CommandOrControl+Alt.".to_string())?;

    Ok(prefix)
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum HotkeyRole {
    Toggle,
    Edit,
    Pinned,
}

impl HotkeyRole {
    fn current(self, settings: &AppSettings) -> &str {
        match self {
            Self::Toggle => &settings.hotkey,
            Self::Edit => &settings.edit_hotkey,
            Self::Pinned => &settings.pinned_hotkey,
        }
    }

    fn set(self, settings: &mut AppSettings, hotkey: String) {
        match self {
            Self::Toggle => settings.hotkey = hotkey,
            Self::Edit => settings.edit_hotkey = hotkey,
            Self::Pinned => settings.pinned_hotkey = hotkey,
        }
    }

    fn invalid_message(self) -> &'static str {
        match self {
            Self::Toggle => "Phím tắt không hợp lệ. Ví dụ: CommandOrControl+Alt+Z",
            Self::Edit => "Phím sửa nhanh không hợp lệ. Ví dụ: CommandOrControl+Alt+E",
            Self::Pinned => "Phím mở bảng ghim không hợp lệ. Ví dụ: CommandOrControl+Alt+P",
        }
    }

    fn register_error_prefix(self) -> &'static str {
        match self {
            Self::Toggle => "Không thể đăng ký hotkey",
            Self::Edit => "Không thể đăng ký phím sửa nhanh",
            Self::Pinned => "Không thể đăng ký phím mở bảng ghim",
        }
    }

    fn conflict_message(self) -> &'static str {
        match self {
            Self::Toggle => "Phím tắt này đang dùng để ẩn/hiện FastPaste.",
            Self::Edit => "Phím tắt này đang dùng để sửa clipboard mới nhất.",
            Self::Pinned => "Phím tắt này đang dùng để mở danh sách đã ghim.",
        }
    }
}

pub(crate) fn register_hotkey_role(
    app: &AppHandle,
    shortcut: Shortcut,
    role: HotkeyRole,
) -> Result<(), String> {
    app.global_shortcut()
        .on_shortcut(shortcut, move |app, _, event| {
            if event.state != ShortcutState::Pressed {
                return;
            }

            match role {
                HotkeyRole::Toggle => crate::toggle_window(app),
                HotkeyRole::Edit => crate::open_last_clipboard_editor(app),
                HotkeyRole::Pinned => crate::open_pinned_clipboard_list(app),
            }
        })
        .map_err(|error| error.to_string())
}

fn hotkey_conflict_message(
    settings: &AppSettings,
    hotkey: &str,
    role: HotkeyRole,
) -> Option<String> {
    let normalized = normalize_hotkey_text(hotkey);
    if is_quick_slot_hotkey(hotkey, &settings.quick_slot_hotkey) {
        return Some("Phím tắt này đang dùng cho dán nhanh mục ghim 1..9.".to_string());
    }

    [HotkeyRole::Toggle, HotkeyRole::Edit, HotkeyRole::Pinned]
        .into_iter()
        .find(|other| {
            *other != role && normalize_hotkey_text(other.current(settings)) == normalized
        })
        .map(|other| other.conflict_message().to_string())
}

pub(crate) fn save_role_hotkey(
    hotkey: String,
    role: HotkeyRole,
    state: State<'_, AppState>,
    app: AppHandle,
) -> Result<(), String> {
    let new_shortcut = hotkey
        .parse::<Shortcut>()
        .map_err(|_| role.invalid_message().to_string())?;

    let mut data = state.0.lock().unwrap();
    let old_hotkey = role.current(&data.settings).to_string();

    if normalize_hotkey_text(&old_hotkey) == normalize_hotkey_text(&hotkey) {
        return Ok(());
    }

    if let Some(message) = hotkey_conflict_message(&data.settings, &hotkey, role) {
        return Err(message);
    }

    let old_shortcut = old_hotkey.parse::<Shortcut>().ok();
    if let Some(old) = old_shortcut {
        let _ = app.global_shortcut().unregister(old);
    }

    if let Err(error) = register_hotkey_role(&app, new_shortcut, role) {
        if let Ok(old) = old_hotkey.parse::<Shortcut>() {
            let _ = register_hotkey_role(&app, old, role);
        }
        return Err(format!("{}: {error}", role.register_error_prefix()));
    }

    role.set(&mut data.settings, hotkey);
    save_state(&data);
    drop(data);
    broadcast_state(&app);
    Ok(())
}

fn copy_pinned_slot(app: &AppHandle, slot: usize) {
    let result = app.try_state::<AppState>().and_then(|state| {
        let mut data = state.0.lock().ok()?;
        let index = data
            .history
            .iter()
            .position(|item| item.pinned && item.quick_slot == Some(slot as u8))?;
        let text = data.history[index].text.clone();
        let changed = if index == 0 {
            false
        } else {
            let mut item = data.history.remove(index);
            item.timestamp = chrono::Utc::now().to_rfc3339();
            data.history.insert(0, item);
            true
        };
        if changed {
            save_state(&data);
        }
        Some((text, changed))
    });

    if let Some((text, history_changed)) = result {
        let _ = app.clipboard().write_text(text.clone());
        paste_clipboard_after_hotkey();
        if let Some(tx) = app.try_state::<tokio::sync::broadcast::Sender<String>>() {
            let _ = tx.send(text);
        }
        if history_changed {
            broadcast_state(app);
            queue_cloud_sync(app);
        }
    }
}

pub(crate) fn unregister_quick_paste_slots(app: &AppHandle, prefix: &str) {
    for slot in 1..=9 {
        let hotkey = quick_slot_hotkey(prefix, slot);
        if let Ok(shortcut) = hotkey.parse::<Shortcut>() {
            let _ = app.global_shortcut().unregister(shortcut);
        }
    }
}

pub(crate) fn register_quick_paste_slots(app: &AppHandle, prefix: &str) -> Result<(), String> {
    for slot in 1..=9 {
        let hotkey = quick_slot_hotkey(prefix, slot);
        if let Ok(shortcut) = hotkey.parse::<Shortcut>() {
            app.global_shortcut()
                .on_shortcut(shortcut, move |app, _, event| {
                    if event.state == ShortcutState::Pressed {
                        copy_pinned_slot(app, slot);
                    }
                })
                .map_err(|error| format!("Không thể đăng ký phím dán nhanh {hotkey}: {error}"))?;
        } else {
            return Err(format!("Phím dán nhanh không hợp lệ: {hotkey}"));
        }
    }

    Ok(())
}

pub(crate) fn register_initial_hotkeys(app: &AppHandle, settings: &AppSettings) {
    if let Ok(shortcut) = settings.hotkey.parse::<Shortcut>() {
        let _ = register_hotkey_role(app, shortcut, HotkeyRole::Toggle);
    }
    if settings.edit_hotkey != settings.hotkey {
        if let Ok(shortcut) = settings.edit_hotkey.parse::<Shortcut>() {
            let _ = register_hotkey_role(app, shortcut, HotkeyRole::Edit);
        }
    }
    if settings.pinned_hotkey != settings.hotkey && settings.pinned_hotkey != settings.edit_hotkey {
        if let Ok(shortcut) = settings.pinned_hotkey.parse::<Shortcut>() {
            let _ = register_hotkey_role(app, shortcut, HotkeyRole::Pinned);
        }
    }
    let _ = register_quick_paste_slots(app, &settings.quick_slot_hotkey);
}

#[cfg(windows)]
fn paste_clipboard_after_hotkey() {
    std::thread::spawn(|| {
        use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
            keybd_event, KEYEVENTF_KEYUP, VK_CONTROL,
        };

        std::thread::sleep(Duration::from_millis(120));
        unsafe {
            keybd_event(VK_CONTROL as u8, 0, 0, 0);
            keybd_event(b'V', 0, 0, 0);
            keybd_event(b'V', 0, KEYEVENTF_KEYUP, 0);
            keybd_event(VK_CONTROL as u8, 0, KEYEVENTF_KEYUP, 0);
        }
    });
}

#[cfg(not(windows))]
fn paste_clipboard_after_hotkey() {}

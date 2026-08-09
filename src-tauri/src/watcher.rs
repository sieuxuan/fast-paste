use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use crate::clipboard::{self, ClipboardPayload};

const POLL_INTERVAL_MS: u64 = 200;

fn cache() -> &'static Mutex<Option<ClipboardPayload>> {
    static CACHE: OnceLock<Mutex<Option<ClipboardPayload>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(None))
}

fn store_latest_payload(payload: ClipboardPayload) {
    *cache().lock().unwrap() = Some(payload);
}

pub(crate) fn latest_payload() -> Option<ClipboardPayload> {
    cache().lock().unwrap().clone()
}

/// Theo dõi clipboard trên OS thread riêng. Win32, decode ảnh và encode PNG
/// đều blocking nên tuyệt đối không chạy trên worker của tokio.
pub(crate) fn spawn_clipboard_watcher() -> tokio::sync::mpsc::UnboundedReceiver<ClipboardPayload> {
    let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
    std::thread::Builder::new()
        .name("fastpaste-clipboard".into())
        .spawn(move || {
            let mut last_seen = clipboard::clipboard_sequence();
            if let Some(payload) = clipboard::read_clipboard() {
                store_latest_payload(payload);
            }
            loop {
                std::thread::sleep(Duration::from_millis(POLL_INTERVAL_MS));
                let current = clipboard::clipboard_sequence();
                let previous = last_seen;
                if current != previous {
                    last_seen = current;
                }
                if !clipboard::should_read(current, previous, clipboard::self_write_sequence()) {
                    continue;
                }
                let Some(payload) = clipboard::read_clipboard() else {
                    continue;
                };
                if payload.text.is_empty() {
                    continue;
                }
                store_latest_payload(payload.clone());
                if tx.send(payload).is_err() {
                    break;
                }
            }
        })
        .expect("không tạo được clipboard watcher thread");
    rx
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn latest_payload_cache_round_trips() {
        let payload = ClipboardPayload::text("watcher cache".into());
        store_latest_payload(payload.clone());
        assert!(latest_payload().as_ref() == Some(&payload));
    }
}

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use p256::ecdh::EphemeralSecret;
use p256::elliptic_curve::rand_core::{OsRng, RngCore};
use p256::elliptic_curve::sec1::ToEncodedPoint;
use p256::PublicKey;
use qrcode::render::svg;
use qrcode::QrCode;
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};
use zeroize::Zeroizing;

use crate::vault;

const PAIRING_TTL_MS: i64 = 5 * 60 * 1_000;
const SESSION_KEY_BYTES: usize = 32;
type HmacSha256 = Hmac<Sha256>;

#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct PairedDevice {
    #[serde(rename = "deviceId")]
    pub(crate) device_id: String,
    pub(crate) name: String,
    #[serde(rename = "rootKey")]
    root_key: String,
    #[serde(default, rename = "lastSeenAt")]
    pub(crate) last_seen_at: i64,
    #[serde(default, rename = "syncCursor")]
    pub(crate) sync_cursor: i64,
}

#[derive(Clone, Serialize)]
pub(crate) struct PairedDeviceInfo {
    #[serde(rename = "deviceId")]
    pub(crate) device_id: String,
    pub(crate) name: String,
    #[serde(rename = "lastSeenAt")]
    pub(crate) last_seen_at: i64,
}

#[derive(Serialize, Deserialize)]
struct PairingVault {
    version: u8,
    #[serde(rename = "desktopId")]
    desktop_id: String,
    devices: Vec<PairedDevice>,
}

#[derive(Clone)]
struct PendingPair {
    pair_id: String,
    secret: Zeroizing<Vec<u8>>,
    expires_at: i64,
}

struct PairingManager {
    vault: PairingVault,
    pending: Option<PendingPair>,
    writable: bool,
}

#[derive(Serialize)]
pub(crate) struct PairingQr {
    #[serde(rename = "desktopId")]
    pub(crate) desktop_id: String,
    #[serde(rename = "expiresAt")]
    pub(crate) expires_at: i64,
    pub(crate) uri: String,
    pub(crate) svg: String,
}

#[derive(Deserialize)]
pub(crate) struct PairRequest {
    #[serde(rename = "pairId")]
    pair_id: String,
    #[serde(rename = "deviceId")]
    device_id: String,
    #[serde(rename = "deviceName")]
    device_name: String,
    nonce: String,
    proof: String,
}

#[derive(Serialize)]
pub(crate) struct PairAccept {
    app: &'static str,
    #[serde(rename = "type")]
    kind: &'static str,
    version: u8,
    #[serde(rename = "pairId")]
    pair_id: String,
    #[serde(rename = "desktopId")]
    desktop_id: String,
    #[serde(rename = "deviceId")]
    device_id: String,
    nonce: String,
    proof: String,
}

#[derive(Deserialize)]
pub(crate) struct SessionHello {
    #[serde(rename = "deviceId")]
    device_id: String,
    #[serde(rename = "ephemeralPublic")]
    ephemeral_public: String,
    nonce: String,
    proof: String,
    #[serde(default, rename = "syncCursor")]
    sync_cursor: i64,
}

#[derive(Serialize)]
pub(crate) struct SessionAccept {
    app: &'static str,
    #[serde(rename = "type")]
    kind: &'static str,
    version: u8,
    #[serde(rename = "desktopId")]
    desktop_id: String,
    #[serde(rename = "deviceId")]
    device_id: String,
    #[serde(rename = "ephemeralPublic")]
    ephemeral_public: String,
    nonce: String,
    proof: String,
    #[serde(rename = "syncCursor")]
    sync_cursor: i64,
}

pub(crate) struct SessionCipher {
    key: Zeroizing<[u8; SESSION_KEY_BYTES]>,
    send_sequence: u64,
    receive_sequence: u64,
    pub(crate) device_id: String,
    pub(crate) sync_cursor: i64,
}

#[derive(Serialize, Deserialize)]
struct SecureEnvelope {
    app: String,
    #[serde(rename = "type")]
    kind: String,
    version: u8,
    seq: u64,
    ciphertext: String,
}

pub(crate) fn begin_pairing(host: &str, port: u16) -> Result<PairingQr, String> {
    if host.trim().is_empty() {
        return Err("Không có địa chỉ LAN để tạo QR ghép đôi.".to_string());
    }
    let pair_id = random_id(16);
    let mut secret = Zeroizing::new(vec![0u8; 32]);
    OsRng.fill_bytes(secret.as_mut_slice());
    let expires_at = now_millis() + PAIRING_TTL_MS;
    let mut manager = manager().lock().unwrap();
    if !manager.writable {
        return Err(
            "Devices vault bị hỏng hoặc thuộc tài khoản Windows khác; không ghi đè khoá thiết bị."
                .to_string(),
        );
    }
    manager.pending = Some(PendingPair {
        pair_id: pair_id.clone(),
        secret,
        expires_at,
    });
    let uri = format!(
        "fastpaste://pair?v=2&host={}&port={}&pairId={}&secret={}&desktopId={}",
        host,
        port,
        pair_id,
        URL_SAFE_NO_PAD.encode(manager.pending.as_ref().unwrap().secret.as_slice()),
        manager.vault.desktop_id
    );
    let code = QrCode::new(uri.as_bytes()).map_err(|error| error.to_string())?;
    let svg = code
        .render::<svg::Color>()
        .min_dimensions(256, 256)
        .dark_color(svg::Color("#111827"))
        .light_color(svg::Color("#ffffff"))
        .build();
    Ok(PairingQr {
        desktop_id: manager.vault.desktop_id.clone(),
        expires_at,
        uri,
        svg,
    })
}

pub(crate) fn paired_devices() -> Vec<PairedDeviceInfo> {
    manager()
        .lock()
        .unwrap()
        .vault
        .devices
        .iter()
        .map(|device| PairedDeviceInfo {
            device_id: device.device_id.clone(),
            name: device.name.clone(),
            last_seen_at: device.last_seen_at,
        })
        .collect()
}

pub(crate) fn desktop_id() -> String {
    manager().lock().unwrap().vault.desktop_id.clone()
}

pub(crate) fn forget_device(device_id: &str) -> Result<(), String> {
    let mut manager = manager().lock().unwrap();
    let before = manager.vault.devices.len();
    manager
        .vault
        .devices
        .retain(|device| device.device_id != device_id);
    if before == manager.vault.devices.len() {
        return Err("Không tìm thấy thiết bị đã ghép đôi.".to_string());
    }
    save_manager(&manager)?;
    // Revocation must also purge the rollback copy containing the old root.
    let path = pairing_path();
    std::fs::copy(&path, path.with_extension("bak"))
        .map(|_| ())
        .map_err(|error| format!("Không làm sạch devices backup cũ: {error}"))
}

pub(crate) fn update_sync_cursor(device_id: &str, cursor: i64) {
    let mut manager = manager().lock().unwrap();
    if let Some(device) = manager
        .vault
        .devices
        .iter_mut()
        .find(|item| item.device_id == device_id)
    {
        if cursor > device.sync_cursor {
            device.sync_cursor = cursor;
            let _ = save_manager(&manager);
        }
    }
}

pub(crate) fn accept_pair(request: PairRequest) -> Result<PairAccept, String> {
    let mut manager = manager().lock().unwrap();
    let pending = manager
        .pending
        .clone()
        .ok_or("Không có yêu cầu ghép đôi đang mở.")?;
    if pending.expires_at < now_millis() || pending.pair_id != request.pair_id {
        manager.pending = None;
        return Err("QR ghép đôi đã hết hạn.".to_string());
    }
    validate_identifier(&request.device_id)?;
    let device_name = request
        .device_name
        .trim()
        .chars()
        .take(80)
        .collect::<String>();
    let transcript = format!(
        "pair_request|{}|{}|{}|{}",
        request.pair_id, request.device_id, device_name, request.nonce
    );
    verify_mac(&pending.secret, transcript.as_bytes(), &request.proof)?;

    let root = derive_root_key(
        &pending.secret,
        &request.pair_id,
        &request.device_id,
        &manager.vault.desktop_id,
        &request.nonce,
    )?;
    let response_nonce = random_id(16);
    let response_transcript = format!(
        "pair_accept|{}|{}|{}|{}|{}",
        request.pair_id, manager.vault.desktop_id, request.device_id, request.nonce, response_nonce
    );
    let proof = make_mac(root.as_slice(), response_transcript.as_bytes());
    manager
        .vault
        .devices
        .retain(|item| item.device_id != request.device_id);
    manager.vault.devices.push(PairedDevice {
        device_id: request.device_id.clone(),
        name: if device_name.is_empty() {
            "Android".into()
        } else {
            device_name
        },
        root_key: URL_SAFE_NO_PAD.encode(root.as_slice()),
        last_seen_at: now_millis(),
        sync_cursor: 0,
    });
    manager.pending = None;
    save_manager(&manager)?;
    Ok(PairAccept {
        app: "fastpaste",
        kind: "pair_accept",
        version: 2,
        pair_id: request.pair_id,
        desktop_id: manager.vault.desktop_id.clone(),
        device_id: request.device_id,
        nonce: response_nonce,
        proof,
    })
}

pub(crate) fn accept_session(
    hello: SessionHello,
) -> Result<(SessionAccept, SessionCipher), String> {
    let mut manager = manager().lock().unwrap();
    let desktop_id = manager.vault.desktop_id.clone();
    let device = manager
        .vault
        .devices
        .iter_mut()
        .find(|device| device.device_id == hello.device_id)
        .ok_or("Thiết bị chưa được ghép đôi.")?;
    let desktop_sync_cursor = device.sync_cursor;
    let root = URL_SAFE_NO_PAD
        .decode(&device.root_key)
        .map_err(|_| "Khoá thiết bị trong vault bị hỏng.".to_string())?;
    let hello_transcript = format!(
        "session_hello|{}|{}|{}|{}|{}",
        hello.device_id, desktop_id, hello.ephemeral_public, hello.nonce, hello.sync_cursor
    );
    verify_mac(&root, hello_transcript.as_bytes(), &hello.proof)?;
    let peer_bytes = URL_SAFE_NO_PAD
        .decode(&hello.ephemeral_public)
        .map_err(|_| "Public key Android không hợp lệ.".to_string())?;
    let peer_public = PublicKey::from_sec1_bytes(&peer_bytes)
        .map_err(|_| "Public key Android không nằm trên P-256.".to_string())?;
    let secret = EphemeralSecret::random(&mut OsRng);
    let public = PublicKey::from(&secret);
    let public_encoded = URL_SAFE_NO_PAD.encode(public.to_encoded_point(false).as_bytes());
    let response_nonce = random_id(16);
    let transcript = format!(
        "session_v2|{}|{}|{}|{}|{}|{}|{}",
        hello.device_id,
        desktop_id,
        hello.ephemeral_public,
        public_encoded,
        hello.nonce,
        response_nonce,
        desktop_sync_cursor
    );
    let shared = secret.diffie_hellman(&peer_public);
    let key = derive_session_key(
        &root,
        shared.raw_secret_bytes().as_slice(),
        transcript.as_bytes(),
    )?;
    let proof = make_mac(&root, transcript.as_bytes());
    device.last_seen_at = now_millis();
    save_manager(&manager)?;
    Ok((
        SessionAccept {
            app: "fastpaste",
            kind: "session_accept",
            version: 2,
            desktop_id,
            device_id: hello.device_id.clone(),
            ephemeral_public: public_encoded,
            nonce: response_nonce,
            proof,
            sync_cursor: desktop_sync_cursor,
        },
        SessionCipher {
            key: Zeroizing::new(key),
            send_sequence: 0,
            receive_sequence: 0,
            device_id: hello.device_id,
            sync_cursor: hello.sync_cursor,
        },
    ))
}

impl SessionCipher {
    pub(crate) fn encrypt(&mut self, plain: &str) -> Result<String, String> {
        self.send_sequence = self
            .send_sequence
            .checked_add(1)
            .ok_or("Session sequence đã đầy.")?;
        let seq = self.send_sequence;
        let cipher =
            Aes256Gcm::new_from_slice(self.key.as_slice()).map_err(|error| error.to_string())?;
        let nonce = wire_nonce(*b"PCV2", seq);
        let aad = format!("fastpaste-secure-v2|{}|{}", self.device_id, seq);
        let ciphertext = cipher
            .encrypt(
                Nonce::from_slice(&nonce),
                Payload {
                    msg: plain.as_bytes(),
                    aad: aad.as_bytes(),
                },
            )
            .map_err(|_| "Không mã hoá được session message.".to_string())?;
        serde_json::to_string(&SecureEnvelope {
            app: "fastpaste".into(),
            kind: "secure_v2".into(),
            version: 2,
            seq,
            ciphertext: URL_SAFE_NO_PAD.encode(ciphertext),
        })
        .map_err(|error| error.to_string())
    }

    pub(crate) fn decrypt(&mut self, wire: &str) -> Result<Option<String>, String> {
        let Ok(envelope) = serde_json::from_str::<SecureEnvelope>(wire) else {
            return Ok(None);
        };
        if envelope.app != "fastpaste" || envelope.kind != "secure_v2" || envelope.version != 2 {
            return Ok(None);
        }
        if envelope.seq <= self.receive_sequence {
            return Err("Đã chặn session message bị phát lại.".to_string());
        }
        let ciphertext = URL_SAFE_NO_PAD
            .decode(envelope.ciphertext)
            .map_err(|_| "Session ciphertext không hợp lệ.".to_string())?;
        let cipher =
            Aes256Gcm::new_from_slice(self.key.as_slice()).map_err(|error| error.to_string())?;
        let nonce = wire_nonce(*b"ANV2", envelope.seq);
        let aad = format!("fastpaste-secure-v2|{}|{}", self.device_id, envelope.seq);
        let plain = cipher
            .decrypt(
                Nonce::from_slice(&nonce),
                Payload {
                    msg: &ciphertext,
                    aad: aad.as_bytes(),
                },
            )
            .map_err(|_| "Session authentication thất bại.".to_string())?;
        self.receive_sequence = envelope.seq;
        String::from_utf8(plain)
            .map(Some)
            .map_err(|_| "Session message không phải UTF-8.".to_string())
    }

    pub(crate) fn encrypt_binary(&mut self, plain: &[u8]) -> Result<Vec<u8>, String> {
        self.send_sequence = self.send_sequence.checked_add(1).ok_or("Session sequence đã đầy.")?;
        let seq = self.send_sequence;
        let cipher = Aes256Gcm::new_from_slice(self.key.as_slice()).map_err(|error| error.to_string())?;
        let nonce = wire_nonce(*b"PCV2", seq);
        let aad = format!("fastpaste-secure-v2|{}|{}", self.device_id, seq);
        let ciphertext = cipher
            .encrypt(Nonce::from_slice(&nonce), Payload { msg: plain, aad: aad.as_bytes() })
            .map_err(|_| "Không mã hoá được binary session message.".to_string())?;
        let mut wire = Vec::with_capacity(12 + ciphertext.len());
        wire.extend_from_slice(b"FPS3");
        wire.extend_from_slice(&seq.to_be_bytes());
        wire.extend_from_slice(&ciphertext);
        Ok(wire)
    }

    pub(crate) fn decrypt_binary(&mut self, wire: &[u8]) -> Result<Option<Vec<u8>>, String> {
        if wire.len() < 12 || &wire[..4] != b"FPS3" {
            return Ok(None);
        }
        let seq = u64::from_be_bytes(wire[4..12].try_into().unwrap());
        if seq <= self.receive_sequence {
            return Err("Đã chặn binary session message bị phát lại.".to_string());
        }
        let cipher = Aes256Gcm::new_from_slice(self.key.as_slice()).map_err(|error| error.to_string())?;
        let nonce = wire_nonce(*b"ANV2", seq);
        let aad = format!("fastpaste-secure-v2|{}|{}", self.device_id, seq);
        let plain = cipher
            .decrypt(
                Nonce::from_slice(&nonce),
                Payload { msg: &wire[12..], aad: aad.as_bytes() },
            )
            .map_err(|_| "Binary session authentication thất bại.".to_string())?;
        self.receive_sequence = seq;
        Ok(Some(plain))
    }
}

fn manager() -> &'static Mutex<PairingManager> {
    static MANAGER: OnceLock<Mutex<PairingManager>> = OnceLock::new();
    MANAGER.get_or_init(|| Mutex::new(load_manager()))
}

fn load_manager() -> PairingManager {
    let path = pairing_path();
    let existed = path.exists() || path.with_extension("bak").exists();
    let loaded = vault::read_protected(&path)
        .ok()
        .and_then(|plain| serde_json::from_slice::<PairingVault>(&plain).ok())
        .filter(|vault| vault.version == 1);
    let writable = loaded.is_some() || !existed;
    let vault = loaded.unwrap_or_else(|| PairingVault {
        version: 1,
        desktop_id: random_id(16),
        devices: vec![],
    });
    let manager = PairingManager {
        vault,
        pending: None,
        writable,
    };
    if manager.writable {
        let _ = save_manager(&manager);
    }
    manager
}

fn save_manager(manager: &PairingManager) -> Result<(), String> {
    if !manager.writable {
        return Err(
            "Devices vault đang khoá để tránh ghi đè dữ liệu không giải mã được.".to_string(),
        );
    }
    let json = serde_json::to_vec(&manager.vault).map_err(|error| error.to_string())?;
    vault::write_protected_atomic(&pairing_path(), &json)
}

fn pairing_path() -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(|parent| parent.join("devices.vault")))
        .unwrap_or_else(|| PathBuf::from("devices.vault"))
}

fn derive_root_key(
    secret: &[u8],
    pair_id: &str,
    device_id: &str,
    desktop_id: &str,
    nonce: &str,
) -> Result<Zeroizing<[u8; 32]>, String> {
    let hkdf = Hkdf::<Sha256>::new(Some(pair_id.as_bytes()), secret);
    let info = format!("fastpaste-pair-v2|{}|{}|{}", device_id, desktop_id, nonce);
    let mut output = Zeroizing::new([0u8; 32]);
    hkdf.expand(info.as_bytes(), output.as_mut())
        .map_err(|_| "Không dẫn xuất được khoá thiết bị.".to_string())?;
    Ok(output)
}

fn derive_session_key(root: &[u8], shared: &[u8], transcript: &[u8]) -> Result<[u8; 32], String> {
    let hkdf = Hkdf::<Sha256>::new(Some(root), shared);
    let mut output = [0u8; 32];
    hkdf.expand(transcript, &mut output)
        .map_err(|_| "Không dẫn xuất được session key.".to_string())?;
    Ok(output)
}

fn make_mac(key: &[u8], data: &[u8]) -> String {
    let mut mac = <HmacSha256 as Mac>::new_from_slice(key).expect("HMAC accepts any key size");
    mac.update(data);
    URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes())
}

fn verify_mac(key: &[u8], data: &[u8], proof: &str) -> Result<(), String> {
    let bytes = URL_SAFE_NO_PAD
        .decode(proof)
        .map_err(|_| "Pairing proof không hợp lệ.".to_string())?;
    let mut mac = <HmacSha256 as Mac>::new_from_slice(key).map_err(|error| error.to_string())?;
    mac.update(data);
    mac.verify_slice(&bytes)
        .map_err(|_| "Pairing authentication thất bại.".to_string())
}

fn wire_nonce(prefix: [u8; 4], sequence: u64) -> [u8; 12] {
    let mut nonce = [0u8; 12];
    nonce[..4].copy_from_slice(&prefix);
    nonce[4..].copy_from_slice(&sequence.to_be_bytes());
    nonce
}

fn random_id(bytes: usize) -> String {
    let mut value = vec![0u8; bytes];
    OsRng.fill_bytes(&mut value);
    URL_SAFE_NO_PAD.encode(value)
}

fn validate_identifier(value: &str) -> Result<(), String> {
    if value.len() < 8
        || value.len() > 128
        || !value
            .chars()
            .all(|ch| ch.is_ascii_alphanumeric() || matches!(ch, '-' | '_'))
    {
        return Err("Định danh thiết bị không hợp lệ.".to_string());
    }
    Ok(())
}

fn now_millis() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_cipher_round_trip_and_replay_protection() {
        let key = [7u8; 32];
        let mut desktop = SessionCipher {
            key: Zeroizing::new(key),
            send_sequence: 0,
            receive_sequence: 0,
            device_id: "android-test".into(),
            sync_cursor: 0,
        };
        // Mirror the directions for a peer without depending on Android code.
        let wire = desktop.encrypt("delta ✓").unwrap();
        let envelope: SecureEnvelope = serde_json::from_str(&wire).unwrap();
        let ciphertext = URL_SAFE_NO_PAD.decode(&envelope.ciphertext).unwrap();
        let cipher = Aes256Gcm::new_from_slice(&key).unwrap();
        let nonce = wire_nonce(*b"PCV2", envelope.seq);
        let aad = format!("fastpaste-secure-v2|android-test|{}", envelope.seq);
        let plain = cipher
            .decrypt(
                Nonce::from_slice(&nonce),
                Payload {
                    msg: &ciphertext,
                    aad: aad.as_bytes(),
                },
            )
            .unwrap();
        assert_eq!(plain, "delta ✓".as_bytes());

        let mut incoming = SecureEnvelope {
            app: "fastpaste".into(),
            kind: "secure_v2".into(),
            version: 2,
            seq: 1,
            ciphertext: String::new(),
        };
        let nonce = wire_nonce(*b"ANV2", 1);
        let aad = "fastpaste-secure-v2|android-test|1";
        incoming.ciphertext = URL_SAFE_NO_PAD.encode(
            cipher
                .encrypt(
                    Nonce::from_slice(&nonce),
                    Payload {
                        msg: b"ack",
                        aad: aad.as_bytes(),
                    },
                )
                .unwrap(),
        );
        let wire = serde_json::to_string(&incoming).unwrap();
        assert_eq!(desktop.decrypt(&wire).unwrap().as_deref(), Some("ack"));
        assert!(desktop.decrypt(&wire).is_err());
    }

    #[test]
    fn pairing_and_session_hkdf_match_android_vector() {
        let secret = (0u8..32).collect::<Vec<_>>();
        let root = derive_root_key(
            &secret,
            "pair-test",
            "android-test",
            "desktop-test",
            "nonce-test",
        )
        .unwrap();
        assert_eq!(
            hex::encode(root.as_slice()),
            "00cb8a0a16c6f19767b5d891603d3fb50c3e50b132b042bef695f254237a982d"
        );
        let shared = (32u8..64).collect::<Vec<_>>();
        let session = derive_session_key(root.as_slice(), &shared, b"session-vector").unwrap();
        assert_eq!(
            hex::encode(session),
            "1e37c14317667d6c6c2c0f460d09470e7f986c2ad7c8e0ec8882e5cc1d725d46"
        );
    }
}

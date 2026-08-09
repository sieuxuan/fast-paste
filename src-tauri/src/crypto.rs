use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::{engine::general_purpose::STANDARD, Engine as _};
use pbkdf2::pbkdf2_hmac;
use rand::{rngs::OsRng, RngCore};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::vault;

const KEY_BYTES: usize = 32;
const NONCE_BYTES: usize = 12;
const PBKDF2_ROUNDS: u32 = 210_000;
const SALT: &[u8] = b"FastPaste E2EE v1";

#[derive(Serialize, Deserialize)]
struct EncryptedEnvelope {
    app: String,
    #[serde(rename = "type")]
    kind: String,
    version: u8,
    #[serde(rename = "keyId")]
    key_id: String,
    nonce: String,
    ciphertext: String,
}

pub(crate) fn derive_key(passphrase: &str) -> Result<[u8; KEY_BYTES], String> {
    if passphrase.chars().count() < 10 {
        return Err("Mật khẩu mã hoá cần ít nhất 10 ký tự.".to_string());
    }
    let mut key = [0u8; KEY_BYTES];
    pbkdf2_hmac::<Sha256>(passphrase.as_bytes(), SALT, PBKDF2_ROUNDS, &mut key);
    Ok(key)
}

pub(crate) fn key_id(key: &[u8; KEY_BYTES]) -> String {
    let digest = Sha256::digest(key);
    format!("{:x}", digest)[..16].to_string()
}

pub(crate) fn save_key(key: &[u8; KEY_BYTES]) -> Result<(), String> {
    vault::write_protected_atomic(&key_path(), key)
}

pub(crate) fn load_key() -> Result<[u8; KEY_BYTES], String> {
    let path = key_path();
    let bytes = if path.exists() || path.with_extension("bak").exists() {
        vault::read_protected(&path).map_err(|_| {
            "Khoá E2EE trên máy bị hỏng hoặc thuộc tài khoản Windows khác.".to_string()
        })?
    } else {
        let legacy = legacy_key_path();
        let encoded = std::fs::read_to_string(&legacy).map_err(|_| {
            "Thiết bị này chưa có khoá E2EE. Hãy nhập cùng mật khẩu mã hoá.".to_string()
        })?;
        let decoded = STANDARD
            .decode(encoded.trim())
            .map_err(|_| "Khoá E2EE cũ trên máy bị hỏng.".to_string())?;
        let key: [u8; KEY_BYTES] = decoded
            .try_into()
            .map_err(|_| "Khoá E2EE không đúng độ dài.".to_string())?;
        save_key(&key)?;
        let _ = std::fs::remove_file(legacy);
        return Ok(key);
    };
    bytes
        .try_into()
        .map_err(|_| "Khoá E2EE không đúng độ dài.".to_string())
}

pub(crate) fn encrypt(plain: &str, envelope_type: &str) -> Result<String, String> {
    encrypt_with_key(plain, envelope_type, &load_key()?)
}

pub(crate) fn encrypt_with_key(
    plain: &str,
    envelope_type: &str,
    key: &[u8; KEY_BYTES],
) -> Result<String, String> {
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|error| error.to_string())?;
    let mut nonce_bytes = [0u8; NONCE_BYTES];
    OsRng.fill_bytes(&mut nonce_bytes);
    let encrypted = cipher
        .encrypt(Nonce::from_slice(&nonce_bytes), plain.as_bytes())
        .map_err(|_| "Không mã hoá được dữ liệu.".to_string())?;
    serde_json::to_string(&EncryptedEnvelope {
        app: "fastpaste".to_string(),
        kind: envelope_type.to_string(),
        version: 1,
        key_id: key_id(key),
        nonce: STANDARD.encode(nonce_bytes),
        ciphertext: STANDARD.encode(encrypted),
    })
    .map_err(|error| error.to_string())
}

/// Returns None when the input is not an encrypted FastPaste envelope.
pub(crate) fn decrypt_if_encrypted(input: &str) -> Result<Option<String>, String> {
    let Ok(envelope) = serde_json::from_str::<EncryptedEnvelope>(input) else {
        return Ok(None);
    };
    if envelope.app != "fastpaste"
        || envelope.version != 1
        || !matches!(
            envelope.kind.as_str(),
            "encrypted" | "encrypted_drive" | "encrypted_drive_blob"
        )
    {
        return Ok(None);
    }
    let key = load_key()?;
    decrypt_envelope(envelope, &key)
}

fn decrypt_envelope(
    envelope: EncryptedEnvelope,
    key: &[u8; KEY_BYTES],
) -> Result<Option<String>, String> {
    if envelope.key_id != key_id(key) {
        return Err("Khoá E2EE không khớp giữa các thiết bị.".to_string());
    }
    let nonce = STANDARD
        .decode(envelope.nonce)
        .map_err(|_| "Nonce E2EE không hợp lệ.".to_string())?;
    if nonce.len() != NONCE_BYTES {
        return Err("Nonce E2EE không đúng độ dài.".to_string());
    }
    let ciphertext = STANDARD
        .decode(envelope.ciphertext)
        .map_err(|_| "Dữ liệu E2EE không hợp lệ.".to_string())?;
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|error| error.to_string())?;
    let plain = cipher
        .decrypt(Nonce::from_slice(&nonce), ciphertext.as_ref())
        .map_err(|_| "Không giải mã được dữ liệu; hãy kiểm tra mật khẩu E2EE.".to_string())?;
    String::from_utf8(plain)
        .map(Some)
        .map_err(|_| "Dữ liệu giải mã không phải UTF-8.".to_string())
}

fn key_path() -> std::path::PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|path| {
            path.parent()
                .map(|parent| parent.join("fastpaste-e2ee.vault"))
        })
        .unwrap_or_else(|| std::path::PathBuf::from("fastpaste-e2ee.vault"))
}

fn legacy_key_path() -> std::path::PathBuf {
    key_path().with_file_name("fastpaste-e2ee.key")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn key_derivation_matches_cross_platform_vector() {
        let key = derive_key("FastPaste-test-2026").unwrap();
        assert_eq!(
            STANDARD.encode(key),
            "GJW41e0IWKOV5UbsSKyKj8J31pbwkPyu0qBK3FNFIsA="
        );
        assert_eq!(key_id(&key), "fb628f9d017d616c");
    }

    #[test]
    fn aes_gcm_envelope_round_trips() {
        let key = derive_key("FastPaste-test-2026").unwrap();
        let wire = encrypt_with_key("clipboard ảnh ✓", "encrypted", &key).unwrap();
        let envelope: EncryptedEnvelope = serde_json::from_str(&wire).unwrap();
        assert_eq!(
            decrypt_envelope(envelope, &key).unwrap().as_deref(),
            Some("clipboard ảnh ✓")
        );
    }
}

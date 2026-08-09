use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use crate::clipboard::ClipboardPayload;

pub(crate) const DEFAULT_CHUNK_BYTES: usize = 48 * 1024;
pub(crate) const DEFAULT_WINDOW_SIZE: usize = 4;
pub(crate) const MAX_WINDOW_SIZE: usize = 8;
const BINARY_MAGIC: &[u8; 4] = b"FPB3";
const MAX_TRANSFER_JSON_BYTES: usize = 96 * 1024 * 1024;

#[derive(Deserialize)]
pub(crate) struct BlobRequest {
    #[serde(rename = "transferId")]
    pub(crate) transfer_id: String,
    #[serde(rename = "blobId")]
    pub(crate) blob_id: String,
    #[serde(default, rename = "nextOffset", alias = "offset")]
    pub(crate) next_offset: usize,
    #[serde(default, rename = "chunkSize")]
    pub(crate) chunk_size: usize,
    #[serde(default = "default_window_size", rename = "windowSize")]
    pub(crate) window_size: usize,
}

#[derive(Serialize, Deserialize)]
pub(crate) struct BlobChunk {
    pub(crate) app: String,
    #[serde(rename = "type")]
    pub(crate) kind: String,
    pub(crate) version: u8,
    #[serde(rename = "transferId")]
    pub(crate) transfer_id: String,
    #[serde(rename = "blobId")]
    pub(crate) blob_id: String,
    pub(crate) offset: usize,
    pub(crate) total: usize,
    pub(crate) hash: String,
    pub(crate) data: String,
    pub(crate) eof: bool,
}

struct InboundBlob {
    data: Vec<u8>,
    total: usize,
    hash: String,
    chunks_since_ack: usize,
    window_size: usize,
}

pub(crate) struct ReceiveOutcome {
    pub(crate) control: Option<String>,
    pub(crate) payload: Option<ClipboardPayload>,
}

pub(crate) fn make_request(blob_id: &str) -> String {
    let offset = {
        let mut map = inbound().lock().unwrap();
        map.entry(blob_id.to_string())
            .or_insert_with(|| InboundBlob {
                data: vec![],
                total: 0,
                hash: String::new(),
                chunks_since_ack: 0,
                window_size: DEFAULT_WINDOW_SIZE,
            })
            .data
            .len()
    };
    make_resume_request(blob_id, &random_id(), offset)
}

pub(crate) fn make_resume_request(blob_id: &str, transfer_id: &str, offset: usize) -> String {
    serde_json::json!({
        "app": "fastpaste",
        "type": "blob_request",
        "version": 2,
        "transferId": transfer_id,
        "blobId": blob_id,
        "offset": offset,
        "nextOffset": offset,
        "chunkSize": DEFAULT_CHUNK_BYTES,
        "windowSize": DEFAULT_WINDOW_SIZE,
    })
    .to_string()
}

pub(crate) fn current_offset(blob_id: &str) -> Option<usize> {
    inbound()
        .lock()
        .unwrap()
        .get(blob_id)
        .map(|item| item.data.len())
}

pub(crate) fn make_chunk(
    request: &BlobRequest,
    payload: &ClipboardPayload,
) -> Result<String, String> {
    let bytes = serde_json::to_vec(payload).map_err(|error| error.to_string())?;
    if bytes.len() > MAX_TRANSFER_JSON_BYTES {
        return Err("Payload lớn hơn giới hạn transfer 96 MB.".to_string());
    }
    if request.next_offset > bytes.len() {
        return Err("Resume offset lớn hơn payload.".to_string());
    }
    let chunk_size = request.chunk_size.clamp(8 * 1024, 64 * 1024);
    let end = (request.next_offset + chunk_size).min(bytes.len());
    let chunk = BlobChunk {
        app: "fastpaste".into(),
        kind: "blob_chunk".into(),
        version: 2,
        transfer_id: request.transfer_id.clone(),
        blob_id: request.blob_id.clone(),
        offset: request.next_offset,
        total: bytes.len(),
        hash: hex::encode(Sha256::digest(&bytes)),
        data: URL_SAFE_NO_PAD.encode(&bytes[request.next_offset..end]),
        eof: end == bytes.len(),
    };
    serde_json::to_string(&chunk).map_err(|error| error.to_string())
}

pub(crate) fn make_binary_chunks(
    request: &BlobRequest,
    payload: &ClipboardPayload,
) -> Result<Vec<Vec<u8>>, String> {
    let bytes = serde_json::to_vec(payload).map_err(|error| error.to_string())?;
    if bytes.len() > MAX_TRANSFER_JSON_BYTES || request.next_offset > bytes.len() {
        return Err("Kích thước hoặc resume offset blob không hợp lệ.".to_string());
    }
    let chunk_size = request.chunk_size.clamp(8 * 1024, 64 * 1024);
    let window_size = request.window_size.clamp(1, MAX_WINDOW_SIZE);
    let hash = Sha256::digest(&bytes);
    let mut offset = request.next_offset;
    let mut frames = Vec::with_capacity(window_size);
    while offset < bytes.len() && frames.len() < window_size {
        let end = (offset + chunk_size).min(bytes.len());
        frames.push(encode_binary_chunk(
            &request.transfer_id,
            &request.blob_id,
            offset,
            bytes.len(),
            &hash,
            end == bytes.len(),
            window_size,
            &bytes[offset..end],
        )?);
        offset = end;
    }
    Ok(frames)
}

pub(crate) fn serialized_size(payload: &ClipboardPayload) -> Result<usize, String> {
    serde_json::to_vec(payload)
        .map(|bytes| bytes.len())
        .map_err(|error| error.to_string())
}

pub(crate) fn receive_chunk(chunk: BlobChunk) -> Result<ReceiveOutcome, String> {
    if chunk.total > MAX_TRANSFER_JSON_BYTES || chunk.offset > chunk.total {
        return Err("Kích thước blob không hợp lệ.".to_string());
    }
    let decoded = URL_SAFE_NO_PAD
        .decode(&chunk.data)
        .map_err(|_| "Chunk base64url không hợp lệ.".to_string())?;
    let mut map = inbound().lock().unwrap();
    let item = map
        .entry(chunk.blob_id.clone())
        .or_insert_with(|| InboundBlob {
            data: Vec::with_capacity(chunk.total.min(4 * 1024 * 1024)),
            total: chunk.total,
            hash: chunk.hash.clone(),
            chunks_since_ack: 0,
            window_size: 1,
        });
    if item.total != chunk.total || item.hash != chunk.hash {
        item.data.clear();
        item.total = chunk.total;
        item.hash = chunk.hash.clone();
    }
    if chunk.offset < item.data.len() {
        // Duplicate after an ACK was lost: do not append twice; ACK the
        // durable offset again so the sender can resume.
    } else if chunk.offset == item.data.len() {
        item.data.extend_from_slice(&decoded);
    } else {
        return Ok(ReceiveOutcome {
            control: Some(ack(&chunk.transfer_id, &chunk.blob_id, item.data.len(), 1)),
            payload: None,
        });
    }
    if item.data.len() < item.total {
        return Ok(ReceiveOutcome {
            control: Some(ack(&chunk.transfer_id, &chunk.blob_id, item.data.len(), 1)),
            payload: None,
        });
    }
    if item.data.len() != item.total || hex::encode(Sha256::digest(&item.data)) != item.hash {
        item.data.clear();
        return Err("SHA-256 của blob không khớp; transfer sẽ tải lại từ offset 0.".to_string());
    }
    let payload: ClipboardPayload = serde_json::from_slice(&item.data)
        .map_err(|error| format!("Payload blob không hợp lệ: {error}"))?;
    if payload.fingerprint() != chunk.blob_id {
        return Err("Fingerprint blob không khớp metadata.".to_string());
    }
    map.remove(&chunk.blob_id);
    Ok(ReceiveOutcome {
        control: Some(serde_json::json!({
            "app": "fastpaste", "type": "blob_complete", "version": 2,
            "transferId": chunk.transfer_id, "blobId": chunk.blob_id,
            "total": chunk.total,
        })
        .to_string()),
        payload: Some(payload),
    })
}

pub(crate) fn receive_binary_chunk(frame: &[u8]) -> Result<ReceiveOutcome, String> {
    let chunk = decode_binary_chunk(frame)?;
    if chunk.total > MAX_TRANSFER_JSON_BYTES || chunk.offset > chunk.total {
        return Err("Kích thước blob binary không hợp lệ.".to_string());
    }
    let mut map = inbound().lock().unwrap();
    let item = map
        .entry(chunk.blob_id.clone())
        .or_insert_with(|| InboundBlob {
            data: Vec::with_capacity(chunk.total.min(4 * 1024 * 1024)),
            total: chunk.total,
            hash: hex::encode(chunk.hash),
            chunks_since_ack: 0,
            window_size: chunk.window_size,
        });
    if item.total != chunk.total || item.hash != hex::encode(chunk.hash) {
        item.data.clear();
        item.total = chunk.total;
        item.hash = hex::encode(chunk.hash);
        item.chunks_since_ack = 0;
    }
    item.window_size = chunk.window_size.clamp(1, MAX_WINDOW_SIZE);
    if chunk.offset < item.data.len() {
        return Ok(ReceiveOutcome {
            control: Some(ack(
                &chunk.transfer_id,
                &chunk.blob_id,
                item.data.len(),
                item.window_size,
            )),
            payload: None,
        });
    }
    if chunk.offset > item.data.len() {
        return Ok(ReceiveOutcome {
            control: Some(ack(
                &chunk.transfer_id,
                &chunk.blob_id,
                item.data.len(),
                item.window_size,
            )),
            payload: None,
        });
    }
    item.data.extend_from_slice(&chunk.data);
    item.chunks_since_ack += 1;
    if item.data.len() < item.total {
        let should_ack = item.chunks_since_ack >= item.window_size;
        if should_ack {
            item.chunks_since_ack = 0;
        }
        return Ok(ReceiveOutcome {
            control: should_ack.then(|| {
                ack(
                    &chunk.transfer_id,
                    &chunk.blob_id,
                    item.data.len(),
                    (item.window_size + 1).min(MAX_WINDOW_SIZE),
                )
            }),
            payload: None,
        });
    }
    if item.data.len() != item.total || hex::encode(Sha256::digest(&item.data)) != item.hash {
        item.data.clear();
        item.chunks_since_ack = 0;
        return Err("SHA-256 của blob binary không khớp.".to_string());
    }
    let payload: ClipboardPayload = serde_json::from_slice(&item.data)
        .map_err(|error| format!("Payload blob binary không hợp lệ: {error}"))?;
    if payload.fingerprint() != chunk.blob_id {
        return Err("Fingerprint blob binary không khớp metadata.".to_string());
    }
    map.remove(&chunk.blob_id);
    Ok(ReceiveOutcome {
        control: Some(
            serde_json::json!({
                "app": "fastpaste", "type": "blob_complete", "version": 3,
                "transferId": chunk.transfer_id, "blobId": chunk.blob_id,
                "total": chunk.total,
            })
            .to_string(),
        ),
        payload: Some(payload),
    })
}

struct DecodedBinaryChunk {
    transfer_id: String,
    blob_id: String,
    offset: usize,
    total: usize,
    hash: [u8; 32],
    data: Vec<u8>,
    window_size: usize,
}

fn encode_binary_chunk(
    transfer_id: &str,
    blob_id: &str,
    offset: usize,
    total: usize,
    hash: &[u8],
    eof: bool,
    window_size: usize,
    data: &[u8],
) -> Result<Vec<u8>, String> {
    let transfer = transfer_id.as_bytes();
    let blob = blob_id.as_bytes();
    if transfer.len() > u8::MAX as usize || blob.len() > u8::MAX as usize || hash.len() != 32 {
        return Err("Header blob binary quá dài.".to_string());
    }
    let mut frame = Vec::with_capacity(56 + transfer.len() + blob.len() + data.len());
    frame.extend_from_slice(BINARY_MAGIC);
    frame.push(u8::from(eof));
    frame.push(transfer.len() as u8);
    frame.push(blob.len() as u8);
    frame.push(window_size.clamp(1, MAX_WINDOW_SIZE) as u8);
    frame.extend_from_slice(&(offset as u64).to_be_bytes());
    frame.extend_from_slice(&(total as u64).to_be_bytes());
    frame.extend_from_slice(hash);
    frame.extend_from_slice(transfer);
    frame.extend_from_slice(blob);
    frame.extend_from_slice(data);
    Ok(frame)
}

fn decode_binary_chunk(frame: &[u8]) -> Result<DecodedBinaryChunk, String> {
    const FIXED: usize = 4 + 4 + 8 + 8 + 32;
    if frame.len() < FIXED || &frame[..4] != BINARY_MAGIC {
        return Err("Frame blob binary không hợp lệ.".to_string());
    }
    let transfer_len = frame[5] as usize;
    let blob_len = frame[6] as usize;
    let header_end = FIXED + transfer_len + blob_len;
    if header_end > frame.len() {
        return Err("Header blob binary bị cắt.".to_string());
    }
    let offset = u64::from_be_bytes(frame[8..16].try_into().unwrap()) as usize;
    let total = u64::from_be_bytes(frame[16..24].try_into().unwrap()) as usize;
    let hash: [u8; 32] = frame[24..56].try_into().unwrap();
    let transfer_start = FIXED;
    let blob_start = transfer_start + transfer_len;
    Ok(DecodedBinaryChunk {
        transfer_id: String::from_utf8(frame[transfer_start..blob_start].to_vec())
            .map_err(|_| "Transfer ID binary không hợp lệ.".to_string())?,
        blob_id: String::from_utf8(frame[blob_start..header_end].to_vec())
            .map_err(|_| "Blob ID binary không hợp lệ.".to_string())?,
        offset,
        total,
        hash,
        data: frame[header_end..].to_vec(),
        window_size: frame[7].clamp(1, MAX_WINDOW_SIZE as u8) as usize,
    })
}

fn ack(transfer_id: &str, blob_id: &str, next_offset: usize, window_size: usize) -> String {
    serde_json::json!({
        "app": "fastpaste", "type": "blob_ack", "version": 2,
        "transferId": transfer_id, "blobId": blob_id,
        "nextOffset": next_offset, "chunkSize": DEFAULT_CHUNK_BYTES,
        "windowSize": window_size.clamp(1, MAX_WINDOW_SIZE),
    })
    .to_string()
}

fn default_window_size() -> usize {
    DEFAULT_WINDOW_SIZE
}

fn inbound() -> &'static Mutex<HashMap<String, InboundBlob>> {
    static INBOUND: OnceLock<Mutex<HashMap<String, InboundBlob>>> = OnceLock::new();
    INBOUND.get_or_init(|| Mutex::new(HashMap::new()))
}

fn random_id() -> String {
    use rand::RngCore;
    let mut bytes = [0u8; 16];
    rand::rngs::OsRng.fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chunk_ack_resume_and_hash_verification() {
        let payload = ClipboardPayload {
            kind: "image".into(),
            text: "ảnh".into(),
            mime_type: "image/png".into(),
            data: base64::engine::general_purpose::STANDARD.encode(vec![5u8; 140_000]),
            ..ClipboardPayload::default()
        };
        let blob_id = payload.fingerprint();
        let mut offset = 0;
        let transfer_id = "transfer-test".to_string();
        loop {
            let request = BlobRequest {
                transfer_id: transfer_id.clone(),
                blob_id: blob_id.clone(),
                next_offset: offset,
                chunk_size: 32 * 1024,
                window_size: DEFAULT_WINDOW_SIZE,
            };
            let wire = make_chunk(&request, &payload).unwrap();
            let chunk: BlobChunk = serde_json::from_str(&wire).unwrap();
            let result = receive_chunk(chunk).unwrap();
            if let Some(received) = result.payload {
                assert!(received == payload);
                break;
            }
            let control = result.control.expect("chunk chưa đủ thì phải có ACK");
            let ack: serde_json::Value = serde_json::from_str(&control).unwrap();
            offset = ack["nextOffset"].as_u64().unwrap() as usize;
        }
    }
}

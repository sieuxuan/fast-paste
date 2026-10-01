use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use crate::clipboard::ClipboardPayload;

pub(crate) const DEFAULT_CHUNK_BYTES: usize = 48 * 1024;
pub(crate) const DEFAULT_WINDOW_SIZE: usize = 4;
pub(crate) const MAX_WINDOW_SIZE: usize = 8;
const BINARY_MAGIC: &[u8; 4] = b"FPB3";
const MAX_TRANSFER_JSON_BYTES: usize = 96 * 1024 * 1024;
const MAX_ACTIVE_TRANSFERS: usize = 4;
const MAX_BUFFERED_BYTES: usize = 128 * 1024 * 1024;
const TRANSFER_TTL: Duration = Duration::from_secs(120);

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
    last_progress: Instant,
}

pub(crate) struct ReceiveOutcome {
    pub(crate) control: Option<String>,
    pub(crate) payload: Option<ClipboardPayload>,
}

pub(crate) fn make_request(blob_id: &str) -> Result<String, String> {
    let offset = {
        let mut map = inbound().lock().unwrap();
        prune_inbound(&mut map, Instant::now());
        check_slot(&map, blob_id)?;
        map.entry(blob_id.to_string())
            .or_insert_with(|| InboundBlob {
                data: vec![],
                total: 0,
                hash: String::new(),
                chunks_since_ack: 0,
                window_size: DEFAULT_WINDOW_SIZE,
                last_progress: Instant::now(),
            })
            .data
            .len()
    };
    Ok(make_resume_request(blob_id, &random_id(), offset))
}

fn prune_inbound(map: &mut HashMap<String, InboundBlob>, now: Instant) {
    map.retain(|_, item| now.saturating_duration_since(item.last_progress) < TRANSFER_TTL);
}

fn check_slot(map: &HashMap<String, InboundBlob>, blob_id: &str) -> Result<(), String> {
    if !map.contains_key(blob_id) && map.len() >= MAX_ACTIVE_TRANSFERS {
        return Err("Đang tải nhiều ảnh; hãy thử lại khi ảnh trước hoàn tất.".into());
    }
    Ok(())
}

fn check_buffer(
    map: &HashMap<String, InboundBlob>,
    blob_id: &str,
    offset: usize,
    length: usize,
) -> Result<(), String> {
    let additional = match map.get(blob_id) {
        Some(item) if offset != item.data.len() => 0,
        _ => length,
    };
    let buffered = map
        .values()
        .fold(0usize, |size, item| size.saturating_add(item.data.len()));
    if buffered.saturating_add(additional) > MAX_BUFFERED_BYTES {
        return Err("Ảnh tải dở vượt giới hạn bộ nhớ; hãy thử lại sau.".into());
    }
    Ok(())
}

pub(crate) fn cancel_transfer(blob_id: &str) {
    inbound().lock().unwrap().remove(blob_id);
}

pub(crate) fn cleanup_stale_transfers() {
    prune_inbound(&mut inbound().lock().unwrap(), Instant::now());
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

#[cfg(test)]
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
            BinaryChunkMetadata {
                transfer_id: &request.transfer_id,
                blob_id: &request.blob_id,
                offset,
                total: bytes.len(),
                hash: &hash,
                eof: end == bytes.len(),
                window_size,
            },
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
    if chunk.total == 0 || chunk.total > MAX_TRANSFER_JSON_BYTES || chunk.offset > chunk.total {
        return Err("Kích thước blob không hợp lệ.".to_string());
    }
    let decoded = URL_SAFE_NO_PAD
        .decode(&chunk.data)
        .map_err(|_| "Chunk base64url không hợp lệ.".to_string())?;
    validate_chunk_size(chunk.offset, chunk.total, decoded.len())?;
    let mut map = inbound().lock().unwrap();
    prune_inbound(&mut map, Instant::now());
    check_slot(&map, &chunk.blob_id)?;
    check_buffer(&map, &chunk.blob_id, chunk.offset, decoded.len())?;
    let item = map
        .entry(chunk.blob_id.clone())
        .or_insert_with(|| InboundBlob {
            data: Vec::with_capacity(chunk.total.min(4 * 1024 * 1024)),
            total: chunk.total,
            hash: chunk.hash.clone(),
            chunks_since_ack: 0,
            window_size: 1,
            last_progress: Instant::now(),
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
        item.last_progress = Instant::now();
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
    let payload: ClipboardPayload = match serde_json::from_slice(&item.data) {
        Ok(payload) => payload,
        Err(error) => {
            item.data.clear();
            return Err(format!("Payload blob không hợp lệ: {error}"));
        }
    };
    if !payload.is_within_limit() || !payload.matches_fingerprint(&chunk.blob_id) {
        item.data.clear();
        return Err("Fingerprint blob không khớp metadata.".to_string());
    }
    map.remove(&chunk.blob_id);
    Ok(ReceiveOutcome {
        control: Some(
            serde_json::json!({
                "app": "fastpaste", "type": "blob_complete", "version": 2,
                "transferId": chunk.transfer_id, "blobId": chunk.blob_id,
                "total": chunk.total,
            })
            .to_string(),
        ),
        payload: Some(payload),
    })
}

pub(crate) fn receive_binary_chunk(frame: &[u8]) -> Result<ReceiveOutcome, String> {
    let chunk = decode_binary_chunk(frame)?;
    validate_chunk_size(chunk.offset, chunk.total, chunk.data.len())?;
    let mut map = inbound().lock().unwrap();
    prune_inbound(&mut map, Instant::now());
    check_slot(&map, &chunk.blob_id)?;
    check_buffer(&map, &chunk.blob_id, chunk.offset, chunk.data.len())?;
    let item = map
        .entry(chunk.blob_id.clone())
        .or_insert_with(|| InboundBlob {
            data: Vec::with_capacity(chunk.total.min(4 * 1024 * 1024)),
            total: chunk.total,
            hash: hex::encode(chunk.hash),
            chunks_since_ack: 0,
            window_size: chunk.window_size,
            last_progress: Instant::now(),
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
    item.last_progress = Instant::now();
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
    let payload: ClipboardPayload = match serde_json::from_slice(&item.data) {
        Ok(payload) => payload,
        Err(error) => {
            item.data.clear();
            item.chunks_since_ack = 0;
            return Err(format!("Payload blob binary không hợp lệ: {error}"));
        }
    };
    if !payload.is_within_limit() || !payload.matches_fingerprint(&chunk.blob_id) {
        item.data.clear();
        item.chunks_since_ack = 0;
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

fn validate_chunk_size(offset: usize, total: usize, length: usize) -> Result<(), String> {
    if total == 0
        || total > MAX_TRANSFER_JSON_BYTES
        || offset > total
        || length == 0
        || length > total - offset
    {
        return Err("Kích thước chunk vượt giới hạn blob.".to_string());
    }
    Ok(())
}

struct BinaryChunkMetadata<'a> {
    transfer_id: &'a str,
    blob_id: &'a str,
    offset: usize,
    total: usize,
    hash: &'a [u8],
    eof: bool,
    window_size: usize,
}

fn encode_binary_chunk(metadata: BinaryChunkMetadata<'_>, data: &[u8]) -> Result<Vec<u8>, String> {
    let transfer = metadata.transfer_id.as_bytes();
    let blob = metadata.blob_id.as_bytes();
    if transfer.len() > u8::MAX as usize
        || blob.len() > u8::MAX as usize
        || metadata.hash.len() != 32
    {
        return Err("Header blob binary quá dài.".to_string());
    }
    let mut frame = Vec::with_capacity(56 + transfer.len() + blob.len() + data.len());
    frame.extend_from_slice(BINARY_MAGIC);
    frame.push(u8::from(metadata.eof));
    frame.push(transfer.len() as u8);
    frame.push(blob.len() as u8);
    frame.push(metadata.window_size.clamp(1, MAX_WINDOW_SIZE) as u8);
    frame.extend_from_slice(&(metadata.offset as u64).to_be_bytes());
    frame.extend_from_slice(&(metadata.total as u64).to_be_bytes());
    frame.extend_from_slice(metadata.hash);
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
    fn transfer_limits_keep_active_resume_and_release_stale_buffers() {
        let now = Instant::now();
        let mut map = HashMap::new();
        for index in 0..MAX_ACTIVE_TRANSFERS {
            map.insert(
                index.to_string(),
                InboundBlob {
                    data: vec![1; 3],
                    total: 10,
                    hash: String::new(),
                    chunks_since_ack: 0,
                    window_size: 1,
                    last_progress: now,
                },
            );
        }
        assert!(check_slot(&map, "another").is_err());
        assert!(check_slot(&map, "0").is_ok());
        assert!(check_buffer(&map, "0", 3, MAX_BUFFERED_BYTES).is_err());
        assert!(check_buffer(&map, "0", 0, MAX_BUFFERED_BYTES).is_ok());
        map.get_mut("0").unwrap().last_progress = now - TRANSFER_TTL;
        prune_inbound(&mut map, now);
        assert_eq!(map.len(), MAX_ACTIVE_TRANSFERS - 1);
        assert!(check_slot(&map, "another").is_ok());
    }

    #[test]
    fn rejects_chunks_outside_declared_size_before_buffering() {
        for (offset, total, length) in [
            (0, 0, 1),
            (2, 1, 1),
            (0, 1, 2),
            (0, 1, 0),
            (0, MAX_TRANSFER_JSON_BYTES + 1, 1),
        ] {
            assert!(validate_chunk_size(offset, total, length).is_err());
        }
        assert!(validate_chunk_size(2, 4, 2).is_ok());
    }

    #[test]
    fn rejects_json_and_binary_oversized_chunks_without_creating_transfer() {
        let blob_id = "invalid-size-audit-test";
        let json_chunk = BlobChunk {
            app: "fastpaste".into(),
            kind: "blob_chunk".into(),
            version: 2,
            transfer_id: "audit".into(),
            blob_id: blob_id.into(),
            offset: 0,
            total: 1,
            hash: String::new(),
            data: URL_SAFE_NO_PAD.encode([1, 2]),
            eof: true,
        };
        assert!(receive_chunk(json_chunk).is_err());
        let frame = encode_binary_chunk(
            BinaryChunkMetadata {
                transfer_id: "audit",
                blob_id,
                offset: 0,
                total: 1,
                hash: &[0; 32],
                eof: true,
                window_size: 1,
            },
            &[1, 2],
        )
        .unwrap();
        assert!(receive_binary_chunk(&frame).is_err());
        assert!(current_offset(blob_id).is_none());
    }

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

    #[test]
    fn binary_frames_round_trip_across_ack_windows() {
        let payload = ClipboardPayload {
            kind: "image".into(),
            text: "binary image".into(),
            mime_type: "image/png".into(),
            data: base64::engine::general_purpose::STANDARD.encode(vec![7u8; 180_000]),
            ..ClipboardPayload::default()
        };
        let blob_id = payload.fingerprint();
        let transfer_id = "binary-transfer-test".to_string();
        let mut offset = 0;
        let mut window_size = 2;

        loop {
            let request = BlobRequest {
                transfer_id: transfer_id.clone(),
                blob_id: blob_id.clone(),
                next_offset: offset,
                chunk_size: 32 * 1024,
                window_size,
            };
            let frames = make_binary_chunks(&request, &payload).unwrap();
            assert!(!frames.is_empty());
            assert!(frames.len() <= window_size);

            let mut next_control = None;
            let mut completed = None;
            for frame in frames {
                assert_eq!(&frame[..4], b"FPB3");
                let result = receive_binary_chunk(&frame).unwrap();
                if result.control.is_some() {
                    next_control = result.control;
                }
                if result.payload.is_some() {
                    completed = result.payload;
                }
            }

            if let Some(received) = completed {
                assert!(received == payload);
                let complete: serde_json::Value =
                    serde_json::from_str(&next_control.expect("phải báo hoàn tất binary")).unwrap();
                assert_eq!(complete["type"], "blob_complete");
                assert_eq!(complete["version"], 3);
                break;
            }

            let ack: serde_json::Value =
                serde_json::from_str(&next_control.expect("hết cửa sổ binary phải trả ACK"))
                    .unwrap();
            assert_eq!(ack["type"], "blob_ack");
            offset = ack["nextOffset"].as_u64().unwrap() as usize;
            window_size = ack["windowSize"].as_u64().unwrap() as usize;
        }
    }
}

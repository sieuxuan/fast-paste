use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use rand::{distributions::Alphanumeric, Rng};
use reqwest::header::CONTENT_TYPE;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::error::Error;
use std::time::Duration;
use tauri::AppHandle;
use tauri_plugin_opener::OpenerExt;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::time::sleep;

const CLOUD_FILE_NAME: &str = "fastpaste-cloud-history.json";
const DRIVE_APPDATA_SCOPE: &str = "https://www.googleapis.com/auth/drive.appdata";
const USERINFO_EMAIL_SCOPE: &str = "https://www.googleapis.com/auth/userinfo.email";
const MAX_CLOUD_ITEMS: usize = 500;
const HTTP_TIMEOUT_SECONDS: u64 = 60;
const UPLOAD_RETRY_ATTEMPTS: usize = 3;

#[derive(Clone, Serialize, Deserialize)]
pub struct CloudUiState {
    pub configured: bool,
    pub signed_in: bool,
    pub syncing: bool,
    pub account_email: Option<String>,
    pub status: String,
    pub last_sync_at: Option<i64>,
}

impl Default for CloudUiState {
    fn default() -> Self {
        Self {
            configured: is_configured(),
            signed_in: load_token().is_some(),
            syncing: false,
            account_email: load_token().and_then(|token| token.email),
            status: if is_configured() {
                "Đăng nhập Google để đồng bộ qua Drive".to_string()
            } else {
                "Google sync chưa được bật trong bản build này.".to_string()
            },
            last_sync_at: None,
        }
    }
}

#[derive(Clone, Serialize, Deserialize)]
pub struct CloudEntry {
    pub text: String,
    pub timestamp: i64,
    pub source: String,
}

pub struct CloudSyncResult {
    pub entries: Vec<CloudEntry>,
    pub merged_count: usize,
}

pub struct CloudDeleteMarker {
    pub text_hash: String,
    pub deleted_at: i64,
}

#[derive(Deserialize)]
struct GoogleOAuthConfig {
    #[serde(rename = "desktopClientId")]
    desktop_client_id: String,
    #[serde(rename = "desktopClientSecret", default)]
    desktop_client_secret: String,
}

#[derive(Clone, Serialize, Deserialize)]
struct GoogleToken {
    access_token: String,
    refresh_token: Option<String>,
    expires_at: i64,
    email: Option<String>,
}

#[derive(Deserialize)]
struct TokenResponse {
    access_token: String,
    expires_in: Option<i64>,
    refresh_token: Option<String>,
}

#[derive(Deserialize)]
struct UserInfoResponse {
    email: Option<String>,
}

#[derive(Deserialize)]
struct DriveListResponse {
    files: Vec<DriveFile>,
}

#[derive(Deserialize)]
struct DriveFile {
    id: String,
}

#[derive(Deserialize)]
struct CloudFilePayload {
    entries: Vec<CloudEntry>,
}

pub fn is_configured() -> bool {
    load_config()
        .map(|config| !config.desktop_client_id.trim().is_empty())
        .unwrap_or(false)
}

pub fn signed_in_email() -> Option<String> {
    load_token().and_then(|token| token.email)
}

pub fn is_signed_in() -> bool {
    load_token().is_some()
}

pub fn sign_out() {
    let _ = std::fs::remove_file(token_path());
}

pub async fn sign_in(app: &AppHandle) -> Result<Option<String>, String> {
    let config = load_config()
        .ok_or_else(|| "Google sync chưa được bật trong bản build này.".to_string())?;
    if config.desktop_client_id.trim().is_empty() {
        return Err("Google desktopClientId đang trống.".to_string());
    }

    let verifier: String = rand::thread_rng()
        .sample_iter(&Alphanumeric)
        .take(64)
        .map(char::from)
        .collect();
    let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
    let csrf_state: String = rand::thread_rng()
        .sample_iter(&Alphanumeric)
        .take(32)
        .map(char::from)
        .collect();

    let listener = TcpListener::bind("127.0.0.1:0")
        .await
        .map_err(|error| format!("Không mở được OAuth listener: {error}"))?;
    let port = listener
        .local_addr()
        .map_err(|error| error.to_string())?
        .port();
    let redirect_uri = format!("http://127.0.0.1:{port}/oauth2callback");
    let scope = format!("{DRIVE_APPDATA_SCOPE} {USERINFO_EMAIL_SCOPE}");
    let auth_url = format!(
        "https://accounts.google.com/o/oauth2/v2/auth?client_id={}&redirect_uri={}&response_type=code&scope={}&access_type=offline&prompt=consent&include_granted_scopes=true&code_challenge={}&code_challenge_method=S256&state={}",
        urlencoding::encode(&config.desktop_client_id),
        urlencoding::encode(&redirect_uri),
        urlencoding::encode(&scope),
        urlencoding::encode(&challenge),
        urlencoding::encode(&csrf_state)
    );

    app.opener()
        .open_url(auth_url, None::<&str>)
        .map_err(|error| format!("Không mở được Google login: {error}"))?;

    let code = wait_for_oauth_code(listener, &csrf_state).await?;
    let mut token = exchange_code(&config, &redirect_uri, &verifier, &code).await?;
    token.email = fetch_email(&token.access_token).await.ok().flatten();
    save_token(&token)?;
    Ok(token.email)
}

pub async fn sync_pruned(
    entries: Vec<CloudEntry>,
    deleted_markers: Vec<CloudDeleteMarker>,
    clear_history_at: Option<i64>,
) -> Result<CloudSyncResult, String> {
    let access_token = ensure_access_token().await?;
    let client = http_client()?;
    let remote_file_id = find_cloud_file(&client, &access_token).await?;
    let mut remote_entries = match remote_file_id.as_deref() {
        Some(file_id) => download_entries(&client, &access_token, file_id).await?,
        None => vec![],
    };
    let mut local_entries = entries;

    if clear_history_at.is_some() || !deleted_markers.is_empty() {
        remote_entries.retain(|entry| !is_deleted_entry(entry, &deleted_markers, clear_history_at));
        local_entries.retain(|entry| !is_deleted_entry(entry, &deleted_markers, clear_history_at));
    }

    let merged_entries = merge_entries([remote_entries, local_entries].concat());
    upload_entries(
        &client,
        &access_token,
        remote_file_id.as_deref(),
        &merged_entries,
    )
    .await?;

    Ok(CloudSyncResult {
        entries: merged_entries.clone(),
        merged_count: merged_entries.len(),
    })
}

pub async fn replace(entries: Vec<CloudEntry>) -> Result<usize, String> {
    let access_token = ensure_access_token().await?;
    let client = http_client()?;
    let remote_file_id = find_cloud_file(&client, &access_token).await?;
    let entries = merge_entries(entries);
    upload_entries(&client, &access_token, remote_file_id.as_deref(), &entries).await?;
    Ok(entries.len())
}

async fn wait_for_oauth_code(
    listener: TcpListener,
    expected_state: &str,
) -> Result<String, String> {
    let accept_result = tokio::time::timeout(Duration::from_secs(180), listener.accept())
        .await
        .map_err(|_| "Hết thời gian chờ Google login.".to_string())?
        .map_err(|error| format!("OAuth listener lỗi: {error}"))?;

    let (mut stream, _) = accept_result;
    let mut buffer = vec![0; 4096];
    let bytes = stream
        .read(&mut buffer)
        .await
        .map_err(|error| format!("Không đọc được OAuth callback: {error}"))?;
    let request = String::from_utf8_lossy(&buffer[..bytes]);
    let first_line = request.lines().next().unwrap_or_default();
    let path = first_line
        .split_whitespace()
        .nth(1)
        .ok_or_else(|| "OAuth callback không hợp lệ.".to_string())?;
    let url = url::Url::parse(&format!("http://127.0.0.1{path}"))
        .map_err(|error| format!("OAuth callback URL lỗi: {error}"))?;
    let params: std::collections::HashMap<_, _> = url.query_pairs().into_owned().collect();

    let response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n<html><body><h3>FastPaste đã đăng nhập Google. Bạn có thể quay lại app.</h3></body></html>";
    let _ = stream.write_all(response.as_bytes()).await;

    let state = params
        .get("state")
        .ok_or_else(|| "OAuth thiếu state.".to_string())?;
    if state != expected_state {
        return Err("OAuth state không khớp.".to_string());
    }

    params
        .get("code")
        .cloned()
        .ok_or_else(|| "Google không trả authorization code.".to_string())
}

async fn exchange_code(
    config: &GoogleOAuthConfig,
    redirect_uri: &str,
    verifier: &str,
    code: &str,
) -> Result<GoogleToken, String> {
    let client = http_client()?;
    let mut form = vec![
        ("client_id", config.desktop_client_id.as_str()),
        ("code", code),
        ("code_verifier", verifier),
        ("grant_type", "authorization_code"),
        ("redirect_uri", redirect_uri),
    ];
    if !config.desktop_client_secret.trim().is_empty() {
        form.push(("client_secret", config.desktop_client_secret.as_str()));
    }

    let token_response = client
        .post("https://oauth2.googleapis.com/token")
        .form(&form)
        .send()
        .await
        .map_err(|error| format!("Không đổi được Google token: {error}"))?;
    if !token_response.status().is_success() {
        return Err(format!(
            "Google token HTTP {}: {}",
            token_response.status(),
            token_response.text().await.unwrap_or_default()
        ));
    }

    let response: TokenResponse = token_response
        .json()
        .await
        .map_err(|error| format!("Google token JSON lỗi: {error}"))?;
    Ok(GoogleToken {
        access_token: response.access_token,
        refresh_token: response.refresh_token,
        expires_at: now_millis() + response.expires_in.unwrap_or(3600) * 1000,
        email: None,
    })
}

async fn fetch_email(access_token: &str) -> Result<Option<String>, String> {
    let response = http_client()?
        .get("https://www.googleapis.com/oauth2/v2/userinfo")
        .bearer_auth(access_token)
        .send()
        .await
        .map_err(|error| format!("Không lấy được Google account: {error}"))?;
    if !response.status().is_success() {
        return Ok(None);
    }

    let user: UserInfoResponse = response
        .json()
        .await
        .map_err(|error| format!("Google account JSON lỗi: {error}"))?;
    Ok(user.email)
}

async fn ensure_access_token() -> Result<String, String> {
    let mut token = load_token().ok_or_else(|| "Chưa đăng nhập Google.".to_string())?;
    if token.expires_at > now_millis() + 60_000 {
        return Ok(token.access_token);
    }

    let refresh_token = token
        .refresh_token
        .clone()
        .ok_or_else(|| "Google token đã hết hạn, cần đăng nhập lại.".to_string())?;
    let config = load_config()
        .ok_or_else(|| "Google sync chưa được bật trong bản build này.".to_string())?;
    let mut form = vec![
        ("client_id", config.desktop_client_id.as_str()),
        ("refresh_token", refresh_token.as_str()),
        ("grant_type", "refresh_token"),
    ];
    if !config.desktop_client_secret.trim().is_empty() {
        form.push(("client_secret", config.desktop_client_secret.as_str()));
    }

    let response = http_client()?
        .post("https://oauth2.googleapis.com/token")
        .form(&form)
        .send()
        .await
        .map_err(|error| format!("Không refresh được Google token: {error}"))?;
    if !response.status().is_success() {
        return Err(format!(
            "Google refresh HTTP {}: {}",
            response.status(),
            response.text().await.unwrap_or_default()
        ));
    }

    let refreshed: TokenResponse = response
        .json()
        .await
        .map_err(|error| format!("Google refresh JSON lỗi: {error}"))?;
    token.access_token = refreshed.access_token;
    token.expires_at = now_millis() + refreshed.expires_in.unwrap_or(3600) * 1000;
    save_token(&token)?;
    Ok(token.access_token)
}

async fn find_cloud_file(
    client: &reqwest::Client,
    access_token: &str,
) -> Result<Option<String>, String> {
    let response = client
        .get("https://www.googleapis.com/drive/v3/files")
        .bearer_auth(access_token)
        .query(&[
            ("spaces", "appDataFolder"),
            ("q", &format!("name='{CLOUD_FILE_NAME}' and trashed=false")),
            ("fields", "files(id,name,modifiedTime)"),
            ("orderBy", "modifiedTime desc"),
        ])
        .send()
        .await
        .map_err(|error| format!("Drive list lỗi: {error}"))?;
    if !response.status().is_success() {
        return Err(format!(
            "Drive list HTTP {}: {}",
            response.status(),
            response.text().await.unwrap_or_default()
        ));
    }

    let list: DriveListResponse = response
        .json()
        .await
        .map_err(|error| format!("Drive list JSON lỗi: {error}"))?;
    Ok(list.files.first().map(|file| file.id.clone()))
}

async fn download_entries(
    client: &reqwest::Client,
    access_token: &str,
    file_id: &str,
) -> Result<Vec<CloudEntry>, String> {
    let response = client
        .get(format!(
            "https://www.googleapis.com/drive/v3/files/{file_id}?alt=media"
        ))
        .bearer_auth(access_token)
        .send()
        .await
        .map_err(|error| format!("Drive download lỗi: {error}"))?;
    if !response.status().is_success() {
        return Err(format!(
            "Drive download HTTP {}: {}",
            response.status(),
            response.text().await.unwrap_or_default()
        ));
    }

    let payload: CloudFilePayload = response
        .json()
        .await
        .map_err(|error| format!("Drive JSON lỗi: {error}"))?;
    Ok(payload.entries)
}

async fn upload_entries(
    client: &reqwest::Client,
    access_token: &str,
    file_id: Option<&str>,
    entries: &[CloudEntry],
) -> Result<(), String> {
    let payload = serde_json::json!({
        "schema": 1,
        "updatedAt": now_millis(),
        "entries": entries,
    })
    .to_string();

    if let Some(file_id) = file_id {
        if let Err(update_error) = update_cloud_file(client, access_token, file_id, &payload).await
        {
            create_cloud_file(client, access_token, &payload)
                .await
                .map_err(|create_error| {
                    format!(
                        "{update_error}; đã thử tạo file Google Drive mới nhưng cũng lỗi: {create_error}"
                    )
                })?;
            let _ = trash_cloud_file(client, access_token, file_id).await;
        }
    } else {
        create_cloud_file(client, access_token, &payload).await?;
    }

    Ok(())
}

async fn update_cloud_file(
    client: &reqwest::Client,
    access_token: &str,
    file_id: &str,
    payload: &str,
) -> Result<(), String> {
    let metadata = serde_json::json!({
        "name": CLOUD_FILE_NAME,
    })
    .to_string();
    let encoded_file_id = urlencoding::encode(file_id);
    let response = send_upload_with_retry("Drive update", || {
        let boundary = format!("fastpaste_{}", now_millis());
        let body = multipart_body(&boundary, &metadata, payload);
        client
            .patch(format!(
                "https://www.googleapis.com/upload/drive/v3/files/{encoded_file_id}?uploadType=multipart&fields=id"
            ))
            .bearer_auth(access_token)
            .header(CONTENT_TYPE, format!("multipart/related; boundary={boundary}"))
            .body(body)
    })
    .await?;

    drop(response);
    Ok(())
}

async fn create_cloud_file(
    client: &reqwest::Client,
    access_token: &str,
    payload: &str,
) -> Result<(), String> {
    let metadata = serde_json::json!({
        "name": CLOUD_FILE_NAME,
        "parents": ["appDataFolder"],
    })
    .to_string();
    let response =
        send_upload_with_retry("Drive create", || {
            let boundary = format!("fastpaste_{}", now_millis());
            let body = multipart_body(&boundary, &metadata, payload);
            client
            .post("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
            .bearer_auth(access_token)
            .header(CONTENT_TYPE, format!("multipart/related; boundary={boundary}"))
            .body(body)
        })
        .await?;

    drop(response);
    Ok(())
}

async fn trash_cloud_file(
    client: &reqwest::Client,
    access_token: &str,
    file_id: &str,
) -> Result<(), String> {
    let encoded_file_id = urlencoding::encode(file_id);
    let response = client
        .patch(format!(
            "https://www.googleapis.com/drive/v3/files/{encoded_file_id}"
        ))
        .bearer_auth(access_token)
        .json(&serde_json::json!({ "trashed": true }))
        .send()
        .await
        .map_err(|error| format!("Drive dọn file cũ lỗi: {}", describe_reqwest_error(&error)))?;

    if !response.status().is_success() {
        return Err(format!(
            "Drive dọn file cũ HTTP {}: {}",
            response.status(),
            response.text().await.unwrap_or_default()
        ));
    }

    Ok(())
}

async fn send_upload_with_retry<F>(label: &str, mut build: F) -> Result<reqwest::Response, String>
where
    F: FnMut() -> reqwest::RequestBuilder,
{
    let mut last_error = String::new();

    for attempt in 1..=UPLOAD_RETRY_ATTEMPTS {
        match build().send().await {
            Ok(response) => {
                if response.status().is_success() {
                    return Ok(response);
                }

                let status = response.status();
                let body = response.text().await.unwrap_or_default();
                last_error = format!("{label} HTTP {status}: {body}");
                if !status.is_server_error() || attempt == UPLOAD_RETRY_ATTEMPTS {
                    return Err(last_error);
                }
            }
            Err(error) => {
                last_error = format!("{label} lỗi: {}", describe_reqwest_error(&error));
                if attempt == UPLOAD_RETRY_ATTEMPTS {
                    return Err(last_error);
                }
            }
        }

        sleep(Duration::from_millis(700 * attempt as u64)).await;
    }

    Err(last_error)
}

fn multipart_body(boundary: &str, metadata: &str, payload: &str) -> String {
    format!(
        "--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{metadata}\r\n--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{payload}\r\n--{boundary}--\r\n"
    )
}

fn describe_reqwest_error(error: &reqwest::Error) -> String {
    let mut parts = vec![error.to_string()];
    if error.is_timeout() {
        parts.push("timeout".to_string());
    }
    if error.is_connect() {
        parts.push("connect".to_string());
    }
    if let Some(source) = error.source() {
        parts.push(format!("nguồn: {source}"));
    }
    parts.join(" | ")
}

fn merge_entries(entries: Vec<CloudEntry>) -> Vec<CloudEntry> {
    let mut by_text = std::collections::HashMap::<String, CloudEntry>::new();
    for entry in entries {
        if entry.text.trim().is_empty() {
            continue;
        }
        let should_replace = by_text
            .get(&entry.text)
            .map(|current| entry.timestamp > current.timestamp)
            .unwrap_or(true);
        if should_replace {
            by_text.insert(entry.text.clone(), entry);
        }
    }

    let mut merged: Vec<CloudEntry> = by_text.into_values().collect();
    merged.sort_by(|a, b| b.timestamp.cmp(&a.timestamp));
    merged.truncate(MAX_CLOUD_ITEMS);
    merged
}

fn is_deleted_entry(
    entry: &CloudEntry,
    deleted_markers: &[CloudDeleteMarker],
    clear_history_at: Option<i64>,
) -> bool {
    if clear_history_at
        .map(|clear_at| entry.timestamp <= clear_at)
        .unwrap_or(false)
    {
        return true;
    }

    let text_hash = hash_text(&entry.text);
    deleted_markers
        .iter()
        .any(|marker| marker.text_hash == text_hash && entry.timestamp <= marker.deleted_at)
}

fn hash_text(text: &str) -> String {
    format!("{:x}", Sha256::digest(text.as_bytes()))
}

fn load_config() -> Option<GoogleOAuthConfig> {
    let env_client_id = std::env::var("FASTPASTE_GOOGLE_DESKTOP_CLIENT_ID").unwrap_or_default();
    if !env_client_id.trim().is_empty() {
        return Some(GoogleOAuthConfig {
            desktop_client_id: env_client_id,
            desktop_client_secret: std::env::var("FASTPASTE_GOOGLE_DESKTOP_CLIENT_SECRET")
                .unwrap_or_default(),
        });
    }

    let built_client_id = option_env!("FASTPASTE_GOOGLE_DESKTOP_CLIENT_ID").unwrap_or("");
    if !built_client_id.trim().is_empty() {
        return Some(GoogleOAuthConfig {
            desktop_client_id: built_client_id.to_string(),
            desktop_client_secret: option_env!("FASTPASTE_GOOGLE_DESKTOP_CLIENT_SECRET")
                .unwrap_or("")
                .to_string(),
        });
    }

    let path = oauth_config_path();
    let json = std::fs::read_to_string(path).ok()?;
    serde_json::from_str(&json).ok()
}

fn http_client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .timeout(Duration::from_secs(HTTP_TIMEOUT_SECONDS))
        .build()
        .map_err(|error| format!("Không tạo được Google HTTP client: {error}"))
}

fn load_token() -> Option<GoogleToken> {
    let json = std::fs::read_to_string(token_path()).ok()?;
    serde_json::from_str(&json).ok()
}

fn save_token(token: &GoogleToken) -> Result<(), String> {
    let json = serde_json::to_string(token).map_err(|error| error.to_string())?;
    std::fs::write(token_path(), json)
        .map_err(|error| format!("Không lưu được Google token: {error}"))
}

fn oauth_config_path() -> std::path::PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(|parent| parent.join("google_oauth.json")))
        .unwrap_or_else(|| std::path::PathBuf::from("google_oauth.json"))
}

fn token_path() -> std::path::PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(|parent| parent.join("google_token.json")))
        .unwrap_or_else(|| std::path::PathBuf::from("google_token.json"))
}

fn now_millis() -> i64 {
    chrono::Utc::now().timestamp_millis()
}
